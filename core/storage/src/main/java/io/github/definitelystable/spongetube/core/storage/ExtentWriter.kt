package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ExtentWriter(
    private val store: ExtentStore,
    private val spec: ExtentSpec,
    private val partFile: File,
    private val finalFile: File,
    private val output: FileOutputStream,
) : ExtentSink {
    private enum class WriterState {
        OPEN,
        TERMINAL,
    }

    private enum class CommitPhase {
        RECEIVING,
        SEALED,
        VERIFIED,
        DURABLE,
        PUBLISHED,
    }

    private val mutex = Mutex()
    private val receivedDigest = Sha256.newDigest()
    private var receivedLength = 0L
    private var state = WriterState.OPEN
    private var outputClosed = false

    override suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        require(offset + length <= bytes.size) {
            "offset + length exceeds byte array size"
        }

        withContext(store.ioDispatcher) {
            mutex.withLock {
                ensureOpen()
                output.write(bytes, offset, length)
                receivedDigest.update(bytes, offset, length)
                receivedLength = Math.addExact(
                    receivedLength,
                    length.toLong(),
                )
            }
        }
    }

    internal suspend fun commit(): CommittedExtent =
        withContext(store.ioDispatcher) {
            mutex.withLock {
                commitLocked()
            }
        }

    internal suspend fun abort() {
        withContext(store.ioDispatcher) {
            mutex.withLock {
                if (state == WriterState.TERMINAL) {
                    return@withLock
                }

                var failure: Throwable? = null
                try {
                    closeOutputWithoutSync()
                } catch (error: Throwable) {
                    failure = error
                }

                try {
                    if (partFile.exists()) {
                        store.durabilityOps.deleteDurably(partFile)
                    }
                } catch (cleanupError: Throwable) {
                    if (failure == null) {
                        failure = cleanupError
                    } else {
                        failure.addSuppressed(cleanupError)
                    }
                } finally {
                    finishTerminal()
                }

                failure?.let { throw it }
            }
        }
    }

    private suspend fun commitLocked(): CommittedExtent {
        ensureOpen()
        var phase = CommitPhase.RECEIVING

        try {
            store.hit(ExtentFaultPoint.AFTER_RECEIVING)

            store.durabilityOps.syncAndClose(output)
            outputClosed = true
            phase = CommitPhase.SEALED
            store.emit(
                ExtentLifecycleEvent(
                    extentId = spec.extentId,
                    state = ExtentLifecycleState.SEALED,
                ),
            )
            store.hit(ExtentFaultPoint.AFTER_SEAL)

            val fact = FileIntegrity.inspect(partFile)
            val actualLength = fact.length ?: 0L
            val actualSha256 = fact.sha256
                ?: throw ExtentStoreException(
                    "sealed temp file disappeared for " + spec.extentId,
                )

            val receivedSha256 = Sha256.finish(receivedDigest)
            val expectedDigestMismatch =
                spec.expectedSha256?.let { it != actualSha256 } ?: false

            if (
                actualLength != spec.expectedLength ||
                actualLength != receivedLength ||
                actualSha256 != receivedSha256 ||
                expectedDigestMismatch
            ) {
                throw ExtentIntegrityException(
                    extentId = spec.extentId,
                    expectedLength = spec.expectedLength,
                    receivedLength = receivedLength,
                    persistedLength = actualLength,
                    expectedSha256 = spec.expectedSha256,
                    receivedSha256 = receivedSha256,
                    persistedSha256 = actualSha256,
                )
            }

            phase = CommitPhase.VERIFIED
            store.emit(
                ExtentLifecycleEvent(
                    extentId = spec.extentId,
                    state = ExtentLifecycleState.VERIFIED,
                    actualLength = actualLength,
                    sha256 = actualSha256,
                ),
            )
            store.hit(ExtentFaultPoint.AFTER_VERIFY)

            val stored = StoredExtent(
                extentId = spec.extentId,
                trackId = spec.trackId,
                representationId = spec.representationId,
                mediaStartUs = spec.mediaStartUs,
                mediaEndUs = spec.mediaEndUs,
                byteStart = spec.byteStart,
                byteEndExclusive = spec.byteEndExclusive,
                dependencyExtentIds = spec.dependencyExtentIds,
                length = actualLength,
                sha256 = actualSha256,
                storagePath = store.layout.finalRelativePath(
                    spec.extentId,
                ),
                publicationState = ExtentPublicationState.PUBLISHED,
                integrityState = ExtentIntegrityState.VALID,
                quarantineReason = null,
            )

            store.assertPublishable(stored)

            store.durabilityOps.installAtomicallyNoReplace(
                partFile,
                finalFile,
            )
            phase = CommitPhase.DURABLE
            store.emit(
                ExtentLifecycleEvent(
                    extentId = spec.extentId,
                    state = ExtentLifecycleState.DURABLE,
                    actualLength = actualLength,
                    sha256 = actualSha256,
                ),
            )
            store.hit(
                ExtentFaultPoint.AFTER_DURABLE_BEFORE_PUBLISH,
            )

            store.publish(stored)
            phase = CommitPhase.PUBLISHED
            finishTerminal()

            store.emit(
                ExtentLifecycleEvent(
                    extentId = spec.extentId,
                    state = ExtentLifecycleState.PUBLISHED,
                    actualLength = actualLength,
                    sha256 = actualSha256,
                ),
            )
            store.hit(ExtentFaultPoint.AFTER_PUBLISH)

            return stored.toCommittedExtent()
        } catch (crash: SimulatedProcessCrash) {
            closeOutputWithoutSync()
            finishTerminal()
            throw crash
        } catch (error: Throwable) {
            closeOutputWithoutSync()

            if (
                phase.ordinal < CommitPhase.DURABLE.ordinal &&
                partFile.exists()
            ) {
                try {
                    store.durabilityOps.deleteDurably(partFile)
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
            }

            finishTerminal()
            throw error
        }
    }

    private fun ensureOpen() {
        if (state != WriterState.OPEN) {
            throw ExtentClosedException()
        }
    }

    private fun finishTerminal() {
        if (state == WriterState.TERMINAL) {
            return
        }

        state = WriterState.TERMINAL
        store.releaseWriter(spec.extentId)
    }

    private fun closeOutputWithoutSync() {
        if (outputClosed) {
            return
        }

        outputClosed = true
        output.close()
    }
}

private fun StoredExtent.toCommittedExtent(): CommittedExtent =
    CommittedExtent(
        extentId = extentId,
        trackId = trackId,
        representationId = representationId,
        mediaStartUs = mediaStartUs,
        mediaEndUs = mediaEndUs,
        byteStart = byteStart,
        byteEndExclusive = byteEndExclusive,
        dependencyExtentIds = dependencyExtentIds,
        length = length,
        sha256 = sha256,
    )
