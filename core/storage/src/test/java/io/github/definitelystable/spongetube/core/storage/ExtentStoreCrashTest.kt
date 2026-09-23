package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtentStoreCrashTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun preDurableCrashNeverPublishesAndRecoveryRemovesPart() = runBlocking {
        val points = listOf(
            ExtentFaultPoint.AFTER_RECEIVING,
            ExtentFaultPoint.AFTER_SEAL,
            ExtentFaultPoint.AFTER_VERIFY,
        )

        for (point in points) {
            val root = File(tempDir, point.name)
            val metadata = FakeExtentMetadataStore()
            val bytes = ("payload-" + point.name).encodeToByteArray()
            val store = openStore(
                root = root,
                metadata = metadata,
                crashAt = point,
            )

            expectThrows<SimulatedProcessCrash> {
                store.writeExtent(spec("extent-" + point.name, bytes)) {
                    write(bytes)
                }
            }

            assertTrue(metadata.snapshot().isEmpty())
            assertTrue(
                root.walkTopDown().any {
                    it.isFile && it.name.endsWith(".part")
                },
            )

            val reopened = openStore(root, metadata)
            assertEquals(
                1,
                reopened.initialRecoveryReport.deletedPartFiles,
                point.name,
            )
            assertTrue(reopened.committedExtents().isEmpty())
            assertFalse(hasPartFiles(root))
            reopened.close()
        }
    }

    @Test
    fun durableBeforePublishCrashLeavesOrphanThenRecoveryDeletesIt() =
        runBlocking {
            val root = File(tempDir, "durable-before-publish")
            val metadata = FakeExtentMetadataStore()
            val bytes = "durable-orphan".encodeToByteArray()
            val store = openStore(
                root = root,
                metadata = metadata,
                crashAt = ExtentFaultPoint.AFTER_DURABLE_BEFORE_PUBLISH,
            )

            expectThrows<SimulatedProcessCrash> {
                store.writeExtent(spec("orphan", bytes)) {
                    write(bytes)
                }
            }

            assertTrue(metadata.snapshot().isEmpty())
            assertEquals(1, finalExtentFiles(root).size)

            val reopened = openStore(root, metadata)
            assertEquals(
                1,
                reopened.initialRecoveryReport.deletedOrphanFiles,
            )
            assertTrue(reopened.committedExtents().isEmpty())
            assertTrue(finalExtentFiles(root).isEmpty())
            reopened.close()
        }

    @Test
    fun crashAfterPublishSurvivesRecovery() = runBlocking {
        val root = File(tempDir, "after-publish")
        val metadata = FakeExtentMetadataStore()
        val bytes = "published".encodeToByteArray()
        val store = openStore(
            root = root,
            metadata = metadata,
            crashAt = ExtentFaultPoint.AFTER_PUBLISH,
        )

        expectThrows<SimulatedProcessCrash> {
            store.writeExtent(spec("published", bytes)) {
                write(bytes)
            }
        }

        assertEquals(1, metadata.snapshot().size)
        assertEquals(1, finalExtentFiles(root).size)

        val reopened = openStore(root, metadata)
        assertEquals(
            1,
            reopened.initialRecoveryReport.verifiedPublishedExtents,
        )
        assertEquals(
            listOf(ExtentId("published")),
            reopened.committedExtents().map(CommittedExtent::extentId),
        )
        assertEquals(1, finalExtentFiles(root).size)
        reopened.close()
    }

    @Test
    fun publishFailureLeavesAmbiguousFinalForRecoveryInsteadOfDeletingIt() =
        runBlocking {
            val root = File(tempDir, "publish-failure")
            val metadata = FakeExtentMetadataStore(
                failNextPublish = true,
            )
            val bytes = "publish-failure".encodeToByteArray()
            val store = openStore(root, metadata)

            expectThrows<IllegalStateException> {
                store.writeExtent(spec("publish-failure", bytes)) {
                    write(bytes)
                }
            }

            assertTrue(metadata.snapshot().isEmpty())
            assertEquals(1, finalExtentFiles(root).size)

            val reopened = openStore(root, metadata)
            assertEquals(
                1,
                reopened.initialRecoveryReport.deletedOrphanFiles,
            )
            assertTrue(reopened.committedExtents().isEmpty())
            assertTrue(finalExtentFiles(root).isEmpty())
            reopened.close()
        }

    @Test
    fun integrityFailurePublishesNothingAndCleansTemp() = runBlocking {
        val root = File(tempDir, "integrity-failure")
        val metadata = FakeExtentMetadataStore()
        val actual = "actual".encodeToByteArray()
        val expected = "other!".encodeToByteArray()
        val store = openStore(root, metadata)

        expectThrows<ExtentIntegrityException> {
            store.writeExtent(
                spec(
                    id = "bad-hash",
                    bytes = expected,
                    expectedLength = actual.size.toLong(),
                ),
            ) {
                write(actual)
            }
        }

        assertTrue(metadata.snapshot().isEmpty())
        assertTrue(finalExtentFiles(root).isEmpty())
        assertFalse(hasPartFiles(root))
        store.close()
    }

    @Test
    fun producerCancellationCleansTempAndReleasesIdentity() = runBlocking {
        val root = File(tempDir, "producer-cancellation")
        val metadata = FakeExtentMetadataStore()
        val bytes = "cancelled-write".encodeToByteArray()
        val store = openStore(root, metadata)
        val spec = spec("cancelled", bytes)

        expectThrows<CancellationException> {
            store.writeExtent(spec) {
                write(bytes)
                throw CancellationException("producer cancelled")
            }
        }

        assertTrue(metadata.snapshot().isEmpty())
        assertFalse(hasPartFiles(root))
        assertTrue(finalExtentFiles(root).isEmpty())

        val committed = store.writeExtent(spec) {
            write(bytes)
        }
        assertEquals(spec.extentId, committed.extentId)
        store.close()
    }

    @Test
    fun closeIsRejectedWhileStructuredWriteOwnsTheStore() = runBlocking {
        val root = File(tempDir, "close-admission")
        val metadata = FakeExtentMetadataStore()
        val bytes = "active-write".encodeToByteArray()
        val store = openStore(root, metadata)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val owner = async {
            store.writeExtent(spec("active", bytes)) {
                entered.complete(Unit)
                release.await()
                write(bytes)
            }
        }

        entered.await()

        expectThrows<ExtentConflictException> {
            store.close()
        }

        release.complete(Unit)
        owner.await()
        store.close()
    }

    @Test
    fun opaqueReadHandleSupportsPositionalReadAndEof() = runBlocking {
        val root = File(tempDir, "read-handle")
        val metadata = FakeExtentMetadataStore()
        val bytes = "0123456789".encodeToByteArray()
        val store = openStore(root, metadata)
        val committed = store.writeExtent(spec("readable", bytes)) {
            write(bytes)
        }

        val handle = checkNotNull(store.openRead(committed.extentId))
        assertEquals(committed, handle.extent)
        assertEquals(bytes.size.toLong(), handle.length)

        val buffer = ByteArray(4)
        assertEquals(4, handle.readAt(3, buffer))
        assertEquals("3456", buffer.decodeToString())
        assertEquals(-1, handle.readAt(bytes.size.toLong(), buffer))

        handle.close()
        expectThrows<ExtentReadHandleClosedException> {
            handle.readAt(0, buffer)
        }
        store.close()
    }

    @Test
    fun openReadQuarantinesFileThatDisappearsAfterStartup() = runBlocking {
        val root = File(tempDir, "read-missing-after-open")
        val metadata = FakeExtentMetadataStore()
        val bytes = "present-at-startup".encodeToByteArray()
        val store = openStore(root, metadata)
        val committed = store.writeExtent(spec("vanished", bytes)) {
            write(bytes)
        }

        val finalFile = ExtentPathLayout(root).finalFile(
            committed.extentId,
            HostDurabilityOps,
        )
        assertTrue(finalFile.delete())

        assertEquals(null, store.openRead(committed.extentId))
        assertTrue(store.committedExtents().isEmpty())
        store.close()
    }

    @Test
    fun runtimeLengthMismatchIsCleanedAndRepairableWithoutRestart() =
        runBlocking {
            val root = File(tempDir, "read-length-repair")
            val metadata = FakeExtentMetadataStore()
            val bytes = "repair-after-read-admission".encodeToByteArray()
            val spec = spec("repair-after-read-admission", bytes)
            val store = openStore(root, metadata)
            val committed = store.writeExtent(spec) {
                write(bytes)
            }

            val finalFile = ExtentPathLayout(root).finalFile(
                committed.extentId,
                HostDurabilityOps,
            )
            finalFile.writeBytes("short".encodeToByteArray())

            assertEquals(null, store.openRead(committed.extentId))
            assertFalse(finalFile.exists())
            assertTrue(store.committedExtents().isEmpty())

            val repaired = store.writeExtent(spec) {
                write(bytes)
            }
            assertEquals(committed.extentId, repaired.extentId)

            store.openRead(repaired.extentId)?.use { handle ->
                val actual = ByteArray(bytes.size)
                assertEquals(bytes.size, handle.readAt(0, actual))
                assertEquals(bytes.toList(), actual.toList())
            } ?: throw AssertionError("repaired extent is not readable")

            store.close()
        }

    @Test
    fun cancelledOpenReadReleasesStoreLease() = runBlocking {
        val root = File(tempDir, "cancelled-read-admission")
        val metadata = FakeExtentMetadataStore()
        val bytes = "cancelled-read-admission".encodeToByteArray()
        val store = openStore(root, metadata)
        val committed = store.writeExtent(
            spec("cancelled-read-admission", bytes),
        ) {
            write(bytes)
        }

        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        metadata.lookupStarted = lookupStarted
        metadata.releaseLookup = releaseLookup

        val opening = async(Dispatchers.Default) {
            store.openRead(committed.extentId)
        }
        lookupStarted.await()
        opening.cancel()
        releaseLookup.complete(Unit)

        expectThrows<CancellationException> {
            opening.await()
        }

        store.close()
    }

    @Test
    fun readHandleOwnsStoreLifetimeUntilClosed() = runBlocking {
        val root = File(tempDir, "read-lifetime")
        val metadata = FakeExtentMetadataStore()
        val bytes = "lease-read".encodeToByteArray()
        val store = openStore(root, metadata)
        val committed = store.writeExtent(spec("lease-read", bytes)) {
            write(bytes)
        }

        val handle = checkNotNull(store.openRead(committed.extentId))
        expectThrows<ExtentConflictException> {
            store.close()
        }

        handle.close()
        store.close()
    }

    @Test
    fun readHandleDoesNotTouchMetadataPerRead() = runBlocking {
        val root = File(tempDir, "read-hot-path")
        val metadata = FakeExtentMetadataStore()
        val bytes = "metadata-on-open-only".encodeToByteArray()
        val store = openStore(root, metadata)
        val committed = store.writeExtent(spec("metadata-on-open-only", bytes)) {
            write(bytes)
        }

        val lookupsBeforeOpen = metadata.extentLookupCount
        val handle = checkNotNull(store.openRead(committed.extentId))
        assertEquals(lookupsBeforeOpen + 1, metadata.extentLookupCount)

        val buffer = ByteArray(4)
        repeat(3) { index ->
            assertTrue(handle.readAt(index.toLong(), buffer) > 0)
        }
        assertEquals(lookupsBeforeOpen + 1, metadata.extentLookupCount)

        handle.close()
        store.close()
    }

    @Test
    fun producerFailureRemainsPrimaryWhenAbortCleanupFails() = runBlocking {
        val root = File(tempDir, "producer-primary-failure")
        val metadata = FakeExtentMetadataStore()
        val bytes = "producer-primary".encodeToByteArray()
        val store = openStore(
            root = root,
            metadata = metadata,
            durabilityOps = DeleteFailingDurabilityOps,
        )

        val failure = expectThrows<IllegalStateException> {
            store.writeExtent(spec("producer-primary", bytes)) {
                write(bytes)
                error("producer failed")
            }
        }

        assertEquals("producer failed", failure.message)
        assertTrue(
            failure.suppressed.any {
                it.message?.contains("simulated delete failure") == true
            },
        )

        // Abort cleanup failed, but writer ownership must still be released.
        store.close()
    }

    @Test
    fun syncFailureReleasesWriterReservationForRetry() = runBlocking {
        val root = File(tempDir, "sync-failure-retry")
        val metadata = FakeExtentMetadataStore()
        val bytes = "sync-failure-retry".encodeToByteArray()
        val durability = FailOnceSyncDurabilityOps()
        val store = openStore(
            root = root,
            metadata = metadata,
            durabilityOps = durability,
        )
        val spec = spec("sync-failure-retry", bytes)

        expectThrows<ExtentStoreException> {
            store.writeExtent(spec) {
                write(bytes)
            }
        }

        assertTrue(metadata.snapshot().isEmpty())
        assertFalse(hasPartFiles(root))

        val committed = store.writeExtent(spec) {
            write(bytes)
        }
        assertEquals(spec.extentId, committed.extentId)
        store.close()
    }

    @Test
    fun extentSinkBoundsCheckCannotOverflow() = runBlocking {
        val root = File(tempDir, "write-bounds-overflow")
        val metadata = FakeExtentMetadataStore()
        val bytes = byteArrayOf(1)
        val store = openStore(root, metadata)

        expectThrows<IllegalArgumentException> {
            store.writeExtent(spec("write-bounds-overflow", bytes)) {
                write(
                    bytes = bytes,
                    offset = Int.MAX_VALUE,
                    length = 1,
                )
            }
        }

        assertTrue(metadata.snapshot().isEmpty())
        assertFalse(hasPartFiles(root))
        store.close()
    }

    @Test
    fun noOriginDigestUsesDigestOfExactlyReceivedBytes() = runBlocking {
        val root = File(tempDir, "received-digest")
        val metadata = FakeExtentMetadataStore()
        val first = "hello ".encodeToByteArray()
        val second = "world".encodeToByteArray()
        val all = first + second
        val store = openStore(root, metadata)
        val spec = spec(
            id = "received-digest",
            bytes = all,
            includeExpectedSha256 = false,
        )

        val committed = store.writeExtent(spec) {
            write(first)
            write(second)
        }

        assertEquals(all.size.toLong(), committed.length)
        assertEquals(sha256(all), committed.sha256)
        assertEquals(
            committed.sha256,
            Sha256.digest(
                ExtentPathLayout(root).finalFile(
                    committed.extentId,
                    HostDurabilityOps,
                ),
            ),
        )
        store.close()
    }

    @Test
    fun concurrentSameExtentHasExactlyOneWriterOwner() = runBlocking {
        val root = File(tempDir, "single-writer")
        val metadata = FakeExtentMetadataStore()
        val bytes = "same".encodeToByteArray()
        val store = openStore(root, metadata)
        val spec = spec("same", bytes)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val owner = async {
            store.writeExtent(spec) {
                entered.complete(Unit)
                release.await()
                write(bytes)
            }
        }

        entered.await()

        expectThrows<ExtentConflictException> {
            store.writeExtent(spec) {
                write(bytes)
            }
        }

        release.complete(Unit)
        owner.await()
        assertEquals(1, store.committedExtents().size)
        store.close()
    }

    @Test
    fun repairWithoutOriginDigestUsesStoredDigestIdentity() = runBlocking {
        val root = File(tempDir, "repair-without-origin-digest")
        val metadata = FakeExtentMetadataStore()
        val bytes = "repairable".encodeToByteArray()
        val store = openStore(root, metadata)
        val repairSpec = spec(
            id = "repairable-no-digest",
            bytes = bytes,
            includeExpectedSha256 = false,
        )

        store.writeExtent(repairSpec) {
            write(bytes)
        }
        metadata.quarantine(
            repairSpec.extentId,
            ExtentQuarantineReason.SHA256_MISMATCH,
        )
        HostDurabilityOps.deleteDurably(
            ExtentPathLayout(root).finalFile(
                repairSpec.extentId,
                HostDurabilityOps,
            ),
        )

        val repaired = store.writeExtent(repairSpec) {
            write(bytes)
        }

        assertEquals(repairSpec.extentId, repaired.extentId)
        assertEquals(sha256(bytes), repaired.sha256)
        assertEquals(1, store.committedExtents().size)
        store.close()
    }

    @Test
    fun changedRepairWithoutOriginDigestIsRejectedBeforeFinalInstall() =
        runBlocking {
            val root = File(tempDir, "changed-repair-no-digest")
            val metadata = FakeExtentMetadataStore()
            val original = "original".encodeToByteArray()
            val changed = "changed!".encodeToByteArray()
            val store = openStore(root, metadata)
            val originalSpec = spec(
                id = "stable-no-digest",
                bytes = original,
                includeExpectedSha256 = false,
            )

            store.writeExtent(originalSpec) {
                write(original)
            }
            metadata.quarantine(
                originalSpec.extentId,
                ExtentQuarantineReason.SHA256_MISMATCH,
            )
            HostDurabilityOps.deleteDurably(
                ExtentPathLayout(root).finalFile(
                    originalSpec.extentId,
                    HostDurabilityOps,
                ),
            )

            expectThrows<ExtentConflictException> {
                store.writeExtent(
                    spec(
                        id = "stable-no-digest",
                        bytes = changed,
                        includeExpectedSha256 = false,
                    ),
                ) {
                    write(changed)
                }
            }

            assertFalse(hasPartFiles(root))
            assertTrue(finalExtentFiles(root).isEmpty())
            assertTrue(store.committedExtents().isEmpty())
            store.close()
        }

    @Test
    fun incompatibleQuarantinedIdentityIsRejectedBeforeTempCreation() =
        runBlocking {
            val root = File(tempDir, "immutable-identity")
            val metadata = FakeExtentMetadataStore()
            val original = "original".encodeToByteArray()
            val changed = "changed!".encodeToByteArray()
            val store = openStore(root, metadata)
            val originalSpec = spec("stable-id", original)

            store.writeExtent(originalSpec) {
                write(original)
            }

            metadata.quarantine(
                originalSpec.extentId,
                ExtentQuarantineReason.SHA256_MISMATCH,
            )
            HostDurabilityOps.deleteDurably(
                ExtentPathLayout(root).finalFile(
                    originalSpec.extentId,
                    HostDurabilityOps,
                ),
            )

            expectThrows<ExtentConflictException> {
                store.writeExtent(spec("stable-id", changed)) {
                    write(changed)
                }
            }

            assertFalse(hasPartFiles(root))
            assertTrue(finalExtentFiles(root).isEmpty())
            store.close()
        }

    private suspend fun openStore(
        root: File,
        metadata: FakeExtentMetadataStore,
        crashAt: ExtentFaultPoint? = null,
        durabilityOps: ExtentDurabilityOps = HostDurabilityOps,
    ): ExtentStore =
        ExtentStore.openForTest(
            rootDirectory = root,
            metadataStore = metadata,
            durabilityOps = durabilityOps,
            ioDispatcher = Dispatchers.Unconfined,
            faultInjector = if (crashAt == null) {
                ExtentFaultInjector.NONE
            } else {
                ExtentFaultInjector { point ->
                    if (point == crashAt) {
                        throw SimulatedProcessCrash(point)
                    }
                }
            },
        )

    private fun spec(
        id: String,
        bytes: ByteArray,
        expectedLength: Long = bytes.size.toLong(),
        includeExpectedSha256: Boolean = true,
    ): ExtentSpec =
        ExtentSpec(
            extentId = ExtentId(id),
            trackId = "video",
            representationId = "v1",
            mediaStartUs = 0,
            mediaEndUs = 10_000_000,
            byteStart = 0,
            byteEndExclusive = expectedLength,
            expectedLength = expectedLength,
            expectedSha256 = if (includeExpectedSha256) {
                sha256(bytes)
            } else {
                null
            },
        )

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val alphabet = "0123456789abcdef"
        digest.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            hex[index * 2] = alphabet[unsigned ushr 4]
            hex[index * 2 + 1] = alphabet[unsigned and 0x0f]
        }
        return Sha256Digest(hex.concatToString())
    }

    private fun hasPartFiles(root: File): Boolean =
        root.exists() &&
            root.walkTopDown().any {
                it.isFile && it.name.endsWith(".part")
            }

    private fun finalExtentFiles(root: File): List<File> =
        if (!root.exists()) {
            emptyList()
        } else {
            root.walkTopDown()
                .filter {
                    it.isFile && it.name.endsWith(".extent")
                }
                .toList()
        }
}

private class FakeExtentMetadataStore(
    var failNextPublish: Boolean = false,
) : ExtentMetadataStore {
    private val rows = linkedMapOf<ExtentId, StoredExtent>()
    var extentLookupCount: Int = 0
        private set
    var lookupStarted: CompletableDeferred<Unit>? = null
    var releaseLookup: CompletableDeferred<Unit>? = null

    override suspend fun assertWritable(
        spec: ExtentSpec,
        storagePath: String,
    ) {
        val existing = rows[spec.extentId] ?: return
        if (!existing.isRepairCompatible(spec, storagePath)) {
            throw ExtentConflictException(
                "extent id is already bound to different or active immutable metadata: " +
                    spec.extentId,
            )
        }
    }

    override suspend fun assertPublishable(extent: StoredExtent) {
        val existing = rows[extent.extentId] ?: return
        if (!existing.isRepairCompatible(extent)) {
            throw ExtentConflictException(
                "extent id is already bound to different or active immutable metadata: " +
                    extent.extentId,
            )
        }
    }

    override suspend fun publish(extent: StoredExtent) {
        if (failNextPublish) {
            failNextPublish = false
            error("simulated metadata publish failure")
        }

        val existing = rows[extent.extentId]
        if (
            existing != null &&
            !existing.isRepairCompatible(extent)
        ) {
            throw ExtentConflictException(
                "extent id is already bound to different or active immutable metadata: " +
                    extent.extentId,
            )
        }

        rows[extent.extentId] = extent.copy(
            publicationState = ExtentPublicationState.PUBLISHED,
            integrityState = ExtentIntegrityState.VALID,
            quarantineReason = null,
        )
    }

    override suspend fun snapshot(): List<StoredExtent> =
        rows.values.toList()

    override suspend fun extentById(
        extentId: ExtentId,
    ): StoredExtent? {
        extentLookupCount += 1
        lookupStarted?.complete(Unit)
        releaseLookup?.await()
        return rows[extentId]
    }

    override suspend fun quarantine(
        extentId: ExtentId,
        reason: ExtentQuarantineReason,
    ) {
        val current = rows.getValue(extentId)
        rows[extentId] = current.copy(
            publicationState = ExtentPublicationState.QUARANTINED,
            integrityState = ExtentIntegrityState.CORRUPT,
            quarantineReason = reason,
        )
    }

    override fun close() = Unit
}

private fun StoredExtent.isRepairCompatible(
    spec: ExtentSpec,
    storagePath: String,
): Boolean =
    publicationState == ExtentPublicationState.QUARANTINED &&
        integrityState == ExtentIntegrityState.CORRUPT &&
        extentId == spec.extentId &&
        trackId == spec.trackId &&
        representationId == spec.representationId &&
        mediaStartUs == spec.mediaStartUs &&
        mediaEndUs == spec.mediaEndUs &&
        byteStart == spec.byteStart &&
        byteEndExclusive == spec.byteEndExclusive &&
        dependencyExtentIds.toSet() == spec.dependencyExtentIds.toSet() &&
        length == spec.expectedLength &&
        (
            spec.expectedSha256 == null ||
                sha256 == spec.expectedSha256
        ) &&
        this.storagePath == storagePath

private fun StoredExtent.isRepairCompatible(
    candidate: StoredExtent,
): Boolean =
    publicationState == ExtentPublicationState.QUARANTINED &&
        integrityState == ExtentIntegrityState.CORRUPT &&
        extentId == candidate.extentId &&
        trackId == candidate.trackId &&
        representationId == candidate.representationId &&
        mediaStartUs == candidate.mediaStartUs &&
        mediaEndUs == candidate.mediaEndUs &&
        byteStart == candidate.byteStart &&
        byteEndExclusive == candidate.byteEndExclusive &&
        dependencyExtentIds.toSet() == candidate.dependencyExtentIds.toSet() &&
        length == candidate.length &&
        sha256 == candidate.sha256 &&
        storagePath == candidate.storagePath

private object DeleteFailingDurabilityOps : ExtentDurabilityOps {
    override fun ensureDirectory(directory: File) =
        HostDurabilityOps.ensureDirectory(directory)

    override fun syncAndClose(output: FileOutputStream) =
        HostDurabilityOps.syncAndClose(output)

    override fun installAtomically(
        source: File,
        destination: File,
    ) = HostDurabilityOps.installAtomically(source, destination)

    override fun deleteDurably(file: File) {
        throw ExtentStoreException("simulated delete failure")
    }
}

private class FailOnceSyncDurabilityOps : ExtentDurabilityOps {
    private var failNextSync = true

    override fun ensureDirectory(directory: File) =
        HostDurabilityOps.ensureDirectory(directory)

    override fun syncAndClose(output: FileOutputStream) {
        if (failNextSync) {
            failNextSync = false
            throw ExtentStoreException(
                "simulated sync failure before close",
            )
        }
        HostDurabilityOps.syncAndClose(output)
    }

    override fun installAtomically(
        source: File,
        destination: File,
    ) = HostDurabilityOps.installAtomically(source, destination)

    override fun deleteDurably(file: File) =
        HostDurabilityOps.deleteDurably(file)
}

private object HostDurabilityOps : ExtentDurabilityOps {
    override fun ensureDirectory(directory: File) {
        check(directory.mkdirs() || directory.isDirectory) {
            "failed to create " + directory
        }
    }

    override fun syncAndClose(output: FileOutputStream) {
        output.flush()
        output.fd.sync()
        output.close()
    }

    override fun installAtomically(
        source: File,
        destination: File,
    ) = synchronized(this) {
        check(!destination.exists()) {
            "destination already exists"
        }
        check(source.renameTo(destination)) {
            "failed to install test extent"
        }
    }

    override fun deleteDurably(file: File) {
        check(!file.exists() || file.delete()) {
            "failed to delete test extent"
        }
    }
}

private suspend inline fun <reified T : Throwable> expectThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw AssertionError(
            "expected " + T::class.java.name +
                ", got " + error::class.java.name,
            error,
        )
    }

    throw AssertionError("expected " + T::class.java.name)
}
