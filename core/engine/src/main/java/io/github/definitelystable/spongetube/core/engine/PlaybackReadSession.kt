package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryAcquireDisposition
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryOutcome
import io.github.definitelystable.spongetube.core.storage.ExtentId
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * One open/read/close cycle over a plan resource (M1-E §6).
 *
 * Reads are served only from PUBLISHED + VALID extents. A miss joins or
 * starts the RecoveryChain for the whole FetchUnit as a PLAYBACK consumer,
 * waits for durable publication, refreshes the CoverageIndex and then reads
 * locally; in-flight bytes are never exposed. The chain, not this session or
 * Media3, owns every retry; a terminal chain surfaces as one
 * [PlaybackBridgeException].
 *
 * Blocking API for the Media3 loader thread. Thread interruption during a
 * wait releases the broker lease and surfaces as [InterruptedIOException].
 * Instances are single-use and not thread-safe; [close] may be called at any
 * time, repeatedly, including without a successful [open].
 */
@SpongeBridgeApi
class PlaybackReadSession internal constructor(
    private val runtime: PlaybackBridgeRuntime,
    val readId: String,
) : Closeable {
    private var resource: PlaybackResource? = null
    private var resourceKey: String? = null
    private var requestedStart = 0L
    private var position = 0L
    private var end = 0L
    private var handle: LocalExtentHandle? = null
    private var handleUnit: PlaybackFetchUnit? = null
    private var bytesLocal = 0L
    private var openReadCalls = 0
    private var refreshCalls = 0
    private var opened = false
    private var closed = false

    /** Number of ExtentStore.openRead calls made by this session. */
    val openReadCount: Int
        get() = openReadCalls

    /**
     * Opens [resourceKey] at [position]. [length] may be [LENGTH_UNSET] to read
     * to the end of the resource. Returns the number of readable bytes.
     * Positions beyond the resource fail; they are never clamped.
     */
    @Throws(IOException::class)
    fun open(
        resourceKey: String,
        position: Long,
        length: Long,
    ): Long = blocking { openInternal(resourceKey, position, length) }

    /** Returns bytes read, or [END_OF_INPUT] at the end of the opened range. */
    @Throws(IOException::class)
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = blocking { readInternal(buffer, offset, length) }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        val current = handle
        handle = null
        handleUnit = null
        try {
            current?.close()
        } finally {
            if (opened) {
                emit(
                    event = PlaybackBridgeEventKind.CLOSE,
                    bytesLocal = bytesLocal,
                    openReadCalls = openReadCalls,
                    coverageRefreshCalls = refreshCalls,
                )
            }
        }
    }

    internal suspend fun openInternal(
        key: String,
        startPosition: Long,
        length: Long,
    ): Long {
        check(!opened && !closed) { "read session is single-use" }
        val target = runtime.plan.resource(key)
            ?: throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.UNKNOWN_RESOURCE,
                message = "resource is not part of the playback plan: $key",
            )
        if (startPosition < 0 || startPosition > target.length) {
            throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.POSITION_OUT_OF_RANGE,
                message = "position $startPosition outside $key " +
                    "[0, ${target.length}]",
            )
        }
        val endExclusive = if (length == LENGTH_UNSET) {
            target.length
        } else {
            if (length < 0 || length > target.length - startPosition) {
                throw PlaybackBridgeException(
                    failure = PlaybackBridgeFailure.POSITION_OUT_OF_RANGE,
                    message = "range $startPosition+$length outside $key " +
                        "[0, ${target.length}]",
                )
            }
            startPosition + length
        }

        resource = target
        resourceKey = key
        requestedStart = startPosition
        position = startPosition
        end = endExclusive
        opened = true
        emit(event = PlaybackBridgeEventKind.OPEN)
        return endExclusive - startPosition
    }

    internal suspend fun readInternal(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(opened && !closed) { "read session is not open" }
        require(offset >= 0 && length >= 0 && length <= buffer.size - offset) {
            "invalid buffer range"
        }
        if (length == 0) {
            return 0
        }
        if (position >= end) {
            // End of input only at the end of the opened range, never at an
            // extent boundary inside it.
            return END_OF_INPUT
        }

        when (val target = checkNotNull(resource)) {
            is InlinePlaybackResource -> {
                val count = minOf(length.toLong(), end - position).toInt()
                val read = target.copyInto(position, buffer, offset, count)
                check(read > 0) { "inline resource returned no bytes" }
                position += read
                bytesLocal += read
                return read
            }

            is ExtentPlaybackResource -> return readExtentBacked(
                target,
                buffer,
                offset,
                length,
            )
        }
    }

    private suspend fun readExtentBacked(
        target: ExtentPlaybackResource,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var rounds = 0
        while (true) {
            val unit = target.unitAt(position)
            val current = handle
            if (current != null && handleUnit === unit) {
                val count = minOf(
                    length.toLong(),
                    unit.resourceEndExclusive - position,
                    end - position,
                ).toInt()
                val read = current.readAt(
                    position - unit.resourceStart,
                    buffer,
                    offset,
                    count,
                )
                if (read <= 0) {
                    throw PlaybackBridgeException(
                        failure = PlaybackBridgeFailure.LOCAL_READ_FAILED,
                        message = "published extent ${unit.extentId} ended " +
                            "before its committed length",
                    )
                }
                position += read
                bytesLocal += read
                // A short read at an extent boundary is intentional: the next
                // call continues in the same open range.
                return read
            }

            closeHandle()
            rounds += 1
            if (rounds > MAX_RESOLUTION_ROUNDS) {
                throw PlaybackBridgeException(
                    failure = PlaybackBridgeFailure.UNRESOLVABLE_COVERAGE,
                    message = "extent ${unit.extentId} did not become READY " +
                        "after $MAX_RESOLUTION_ROUNDS resolution rounds",
                )
            }
            makeLocal(unit)
        }
    }

    private suspend fun makeLocal(unit: PlaybackFetchUnit) {
        when (val resolution = runtime.coverageIndex.resolveExtent(unit.extentSpec)) {
            ExtentResolution.Ready -> {
                openReadCalls += 1
                val opened = runtime.reader.openRead(unit.extentId)
                if (opened == null) {
                    // Read-admission quarantine (M1-B2): refresh so the next
                    // round observes ABSENT and repairs through the broker.
                    refresh()
                    return
                }
                if (opened.length != unit.length) {
                    opened.close()
                    throw PlaybackBridgeException(
                        failure = PlaybackBridgeFailure.LOCAL_READ_FAILED,
                        message = "published extent ${unit.extentId} length " +
                            "${opened.length} != plan ${unit.length}",
                    )
                }
                handle = opened
                handleUnit = unit
                emit(
                    event = PlaybackBridgeEventKind.LOCAL_SERVE,
                    extentId = unit.extentId,
                )
            }

            is ExtentResolution.PublishedNotReady -> {
                requireRepairable(unit.extentId, resolution)
                for (dependency in resolution.missingDependencyIds) {
                    ensureDependency(unit.extentId, dependency, depth = 1)
                }
                refresh()
            }

            ExtentResolution.IdentityConflict ->
                throw identityConflict(unit)

            ExtentResolution.Absent -> ensureFetched(unit)
        }
    }

    private suspend fun ensureDependency(
        dependentId: ExtentId,
        dependencyId: ExtentId,
        depth: Int,
    ) {
        if (depth > MAX_DEPENDENCY_DEPTH) {
            throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.UNRESOLVABLE_COVERAGE,
                message = "dependency chain of $dependentId is too deep",
            )
        }
        val dependency = runtime.plan.unitForExtent(dependencyId)
            ?: throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.UNRESOLVABLE_COVERAGE,
                message = "dependency $dependencyId is not in the plan",
            )
        emit(
            event = PlaybackBridgeEventKind.DEPENDENCY_WAIT,
            extentId = dependentId,
            dependencyExtentId = dependencyId,
        )
        when (val resolution = runtime.coverageIndex.resolveExtent(dependency.extentSpec)) {
            ExtentResolution.Ready -> Unit
            is ExtentResolution.PublishedNotReady -> {
                requireRepairable(dependencyId, resolution)
                for (next in resolution.missingDependencyIds) {
                    ensureDependency(dependencyId, next, depth + 1)
                }
                refresh()
            }
            ExtentResolution.IdentityConflict ->
                throw identityConflict(dependency)

            ExtentResolution.Absent -> ensureFetched(dependency)
        }
    }

    private suspend fun ensureFetched(unit: PlaybackFetchUnit) {
        val recovery = try {
            runtime.acquire(
                unit = unit,
                consumerId = "bridge:$readId:${unit.extentId}",
                kind = RecoveryConsumerKind.PLAYBACK,
            )
        } catch (conflict: FetchIdentityConflictException) {
            throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.FETCH_IDENTITY_CONFLICT,
                message = conflict.message ?: "fetch identity conflict",
                cause = conflict,
            )
        } catch (closedRuntime: IllegalStateException) {
            throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.RUNTIME_CLOSED,
                message = "recovery coordinator is closed",
                cause = closedRuntime,
            )
        }

        val acquiredFetchId = recovery.fetchIdAtAcquire?.value
            ?: recovery.recoveryChainId.value
        val acquireEvent = when (recovery.acquireDisposition) {
            RecoveryAcquireDisposition.NEW_CHAIN ->
                PlaybackBridgeEventKind.MISS
            RecoveryAcquireDisposition.JOINED_ACTIVE ->
                PlaybackBridgeEventKind.JOIN
            RecoveryAcquireDisposition.JOINED_CANCELLING ->
                PlaybackBridgeEventKind.WAIT_EXISTING
        }
        emit(
            event = acquireEvent,
            extentId = unit.extentId,
            fetchId = acquiredFetchId,
        )
        val outcome = try {
            recovery.await()
        } finally {
            recovery.close()
        }
        emit(
            event = PlaybackBridgeEventKind.FETCH_WAIT_END,
            extentId = unit.extentId,
            fetchId = outcome.lastFetchId?.value ?: acquiredFetchId,
            fetchOutcome = outcome.lastFetchOutcome?.name
                ?: outcome.terminalReason.name,
        )

        when {
            // Includes a STORAGE_CONFLICT the chain reconciled locally.
            outcome.isSuccess -> refresh()
            outcome.identityConflict -> throw identityConflict(unit)
            else -> throw fetchFailure(unit, outcome)
        }
    }

    private fun identityConflict(
        unit: PlaybackFetchUnit,
    ): PlaybackBridgeException =
        PlaybackBridgeException(
            failure = PlaybackBridgeFailure.FETCH_IDENTITY_CONFLICT,
            message = "published extent ${unit.extentId} does not match playback plan identity",
        )

    private fun fetchFailure(
        unit: PlaybackFetchUnit,
        outcome: RecoveryOutcome,
    ): PlaybackBridgeException =
        PlaybackBridgeException(
            failure = PlaybackBridgeFailure.FETCH_FAILED,
            fetchOutcome = outcome.lastFetchOutcome?.name
                ?: outcome.terminalReason.name,
            recoveryTerminalReason = outcome.terminalReason.name,
            message = "recovery ${outcome.recoveryChainId} of ${unit.extentId} " +
                "ended with ${outcome.terminalReason}" +
                (outcome.classification?.let { " ($it)" } ?: ""),
        )

    private fun requireRepairable(
        extentId: ExtentId,
        resolution: ExtentResolution.PublishedNotReady,
    ) {
        if (resolution.missingDependencyIds.isEmpty()) {
            throw PlaybackBridgeException(
                failure = PlaybackBridgeFailure.UNRESOLVABLE_COVERAGE,
                message = "published extent $extentId can never become READY",
            )
        }
    }

    private suspend fun refresh() {
        refreshCalls += 1
        runtime.coverageIndex.refresh()
    }

    private fun closeHandle() {
        val current = handle ?: return
        handle = null
        handleUnit = null
        current.close()
    }

    private fun emit(
        event: PlaybackBridgeEventKind,
        extentId: ExtentId? = null,
        dependencyExtentId: ExtentId? = null,
        fetchId: String? = null,
        fetchOutcome: String? = null,
        bytesLocal: Long? = null,
        openReadCalls: Int? = null,
        coverageRefreshCalls: Int? = null,
    ) {
        runtime.emit(
            readId = readId,
            resourceKey = resourceKey,
            requestedRangeStart = if (opened) requestedStart else null,
            requestedRangeEndExclusive = if (opened) end else null,
            event = event,
            extentId = extentId,
            dependencyExtentId = dependencyExtentId,
            fetchId = fetchId,
            fetchOutcome = fetchOutcome,
            bytesLocal = bytesLocal,
            openReadCalls = openReadCalls,
            coverageRefreshCalls = coverageRefreshCalls,
        )
    }

    private fun <T> blocking(block: suspend () -> T): T =
        try {
            runBlocking { block() }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(
                "interrupted while waiting for Sponge coverage",
            ).apply { initCause(interrupted) }
        } catch (cancelled: CancellationException) {
            throw InterruptedIOException(
                "cancelled while waiting for Sponge coverage",
            ).apply { initCause(cancelled) }
        }

    companion object {
        /** Same value as Media3 `C.LENGTH_UNSET`. */
        const val LENGTH_UNSET: Long = -1L

        /** Same value as Media3 `C.RESULT_END_OF_INPUT`. */
        const val END_OF_INPUT: Int = -1

        private const val MAX_RESOLUTION_ROUNDS = 8
        private const val MAX_DEPENDENCY_DEPTH = 4
    }
}
