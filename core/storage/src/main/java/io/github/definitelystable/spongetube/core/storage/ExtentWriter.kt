package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.withContext

class ExtentWriter internal constructor(
    private val store: ExtentStore,
    private val spec: ExtentSpec,
    private val partFile: File,
    private val finalFile: File,
    private val output: FileOutputStream,
) {
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

    private var state = WriterState.OPEN
    private var outputClosed = false

    suspend fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        require(offset + length <= bytes.size) {
            "offset + length exceeds byte array size"
        }

        withContext(store.ioDispatcher) {
            ensureOpen()
            output.write(bytes, offset, length)
        }
    }

    suspend fun commit(): CommittedExtent =
        withContext(store.ioDispatcher) {
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

                if (
                    actualLength != spec.expectedLength ||
                    actualSha256 != spec.expectedSha256
                ) {
                    throw ExtentIntegrityException(
                        extentId = spec.extentId,
                        expectedLength = spec.expectedLength,
                        actualLength = actualLength,
                        expectedSha256 = spec.expectedSha256,
                        actualSha256 = actualSha256,
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

                store.durabilityOps.renameAtomically(
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

                store.publish(stored)
                phase = CommitPhase.PUBLISHED
                store.emit(
                    ExtentLifecycleEvent(
                        extentId = spec.extentId,
                        state = ExtentLifecycleState.PUBLISHED,
                        actualLength = actualLength,
                        sha256 = actualSha256,
                    ),
                )
                store.hit(ExtentFaultPoint.AFTER_PUBLISH)

                state = WriterState.TERMINAL
                store.releaseWriter(spec.extentId)
                stored.toCommittedExtent()
            } catch (crash: SimulatedProcessCrash) {
                closeOutputWithoutSync()
                state = WriterState.TERMINAL
                store.releaseWriter(spec.extentId)
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

                state = WriterState.TERMINAL
                store.releaseWriter(spec.extentId)
                throw error
            }
        }

    suspend fun abort() {
        withContext(store.ioDispatcher) {
            if (state == WriterState.TERMINAL) {
                return@withContext
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
                state = WriterState.TERMINAL
                store.releaseWriter(spec.extentId)
            }

            failure?.let { throw it }
        }
    }

    private fun ensureOpen() {
        if (state != WriterState.OPEN) {
            throw ExtentClosedException()
        }
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
        storagePath = storagePath,
    )
