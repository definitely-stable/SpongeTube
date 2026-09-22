package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
            val writer = store.openWriter(spec("extent-" + point.name, bytes))
            writer.write(bytes)

            assertThrows(SimulatedProcessCrash::class.java) {
                runBlocking { writer.commit() }
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
            assertFalse(
                root.walkTopDown().any {
                    it.isFile && it.name.endsWith(".part")
                },
            )
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
            val writer = store.openWriter(spec("orphan", bytes))
            writer.write(bytes)

            assertThrows(SimulatedProcessCrash::class.java) {
                runBlocking { writer.commit() }
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
        val writer = store.openWriter(spec("published", bytes))
        writer.write(bytes)

        assertThrows(SimulatedProcessCrash::class.java) {
            runBlocking { writer.commit() }
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
            val writer = store.openWriter(spec("publish-failure", bytes))
            writer.write(bytes)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { writer.commit() }
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
        val writer = store.openWriter(
            spec(
                id = "bad-hash",
                bytes = expected,
                expectedLength = actual.size.toLong(),
            ),
        )
        writer.write(actual)

        assertThrows(ExtentIntegrityException::class.java) {
            runBlocking { writer.commit() }
        }

        assertTrue(metadata.snapshot().isEmpty())
        assertTrue(finalExtentFiles(root).isEmpty())
        assertFalse(
            root.walkTopDown().any {
                it.isFile && it.name.endsWith(".part")
            },
        )
        store.close()
    }

    @Test
    fun duplicateActiveWriterForSameExtentIsRejected() = runBlocking {
        val root = File(tempDir, "duplicate-writer")
        val metadata = FakeExtentMetadataStore()
        val bytes = "same".encodeToByteArray()
        val store = openStore(root, metadata)
        val first = store.openWriter(spec("same", bytes))

        assertThrows(ExtentConflictException::class.java) {
            runBlocking {
                store.openWriter(spec("same", bytes))
            }
        }

        first.abort()
        store.close()
    }

    private suspend fun openStore(
        root: File,
        metadata: FakeExtentMetadataStore,
        crashAt: ExtentFaultPoint? = null,
    ): ExtentStore =
        ExtentStore.openForTest(
            rootDirectory = root,
            metadataStore = metadata,
            durabilityOps = HostDurabilityOps,
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
            expectedSha256 = sha256(bytes),
        )

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") {
                "%02x".format(it)
            }
        return Sha256Digest(digest)
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

    override suspend fun publish(extent: StoredExtent) {
        if (failNextPublish) {
            failNextPublish = false
            error("simulated metadata publish failure")
        }
        rows[extent.extentId] = extent.copy(
            publicationState = ExtentPublicationState.PUBLISHED,
            integrityState = ExtentIntegrityState.VALID,
            quarantineReason = null,
        )
    }

    override suspend fun snapshot(): List<StoredExtent> =
        rows.values.toList()

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

    override fun renameAtomically(
        source: File,
        destination: File,
    ) {
        check(!destination.exists()) {
            "destination already exists"
        }
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw AssertionError(
                "test filesystem must support same-filesystem atomic move",
                error,
            )
        }
    }

    override fun deleteDurably(file: File) {
        Files.deleteIfExists(file.toPath())
    }
}
