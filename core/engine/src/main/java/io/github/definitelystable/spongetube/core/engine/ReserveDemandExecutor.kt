package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.RecoveryConsumerKind
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One caller-owned M4-A1a pass, never a background queue or download job. */
enum class ReserveExecutionStop {
    TARGET_REACHED,
    PLAN_DEPLETED,
    FETCH_FAILED,
    DEPENDENCY_NOT_READY,
}

/**
 * Execution evidence is limited to immutable work identity and coverage.
 * Failure details belong to RecoveryCoordinator; this layer never retries.
 */
data class ReserveExecutionReport(
    val stop: ReserveExecutionStop,
    val initialReserveUs: Long,
    val finalReserveUs: Long,
    val targetEndUs: Long,
    val requestedFetchKeys: List<FetchKey>,
    val skippedReadyFetchKeys: List<FetchKey>,
    val planTruncated: Boolean,
    val terminalFetchOutcome: String?,
)

/**
 * M4-A1a: bounded serial consumer over M4-A0's immutable demand plan.
 *
 * A runtime-scoped mutex makes two callers replan after the prior pass,
 * rather than opening two independent reserve queues. There is at most one
 * active RESERVE lease for this executor. No dispatcher, timer, download
 * worker, route decision or retry policy lives here.
 *
 * Every candidate is rechecked against freshly loaded durable metadata
 * before acquiring a lease. A missing dependency cannot be bypassed.
 * Cancellation propagates and closes the sole open lease in finally.
 */
@OptIn(SpongeBridgeApi::class)
internal class ReserveDemandExecutor(
    private val runtime: PlaybackBridgeRuntime,
) {
    private val mutex = Mutex()
    private val consumerCounter = AtomicLong()

    suspend fun execute(
        requirements: PlaybackRequirementSet,
        playheadUs: Long,
        targetReserveUs: Long,
        maxFetchUnits: Int,
    ): ReserveExecutionReport = mutex.withLock {
        runtime.ensureReserveOpen()
        currentCoroutineContext().ensureActive()
        // M1 requires an authoritative refresh; a prior playback/prefetch
        // publication must not be mistaken for a missing immutable unit.
        runtime.coverageIndex.refresh()
        runtime.ensureReserveOpen()
        currentCoroutineContext().ensureActive()
        val plan = ReserveDemandPlanner.plan(
            playbackPlan = runtime.plan,
            requirements = requirements,
            coverageIndex = runtime.coverageIndex,
            playheadUs = playheadUs,
            targetReserveUs = targetReserveUs,
            maxFetchUnits = maxFetchUnits,
        )
        val requested = mutableListOf<FetchKey>()
        val skipped = mutableListOf<FetchKey>()
        var terminalFailure: String? = null
        var dependencyBlocked = false

        for (key in plan.fetchKeys) {
            currentCoroutineContext().ensureActive()
            runtime.ensureReserveOpen()
            runtime.coverageIndex.refresh()
            currentCoroutineContext().ensureActive()
            runtime.ensureReserveOpen()

            val view = runtime.coverageIndex.captureReserveView(
                requirements,
                playheadUs,
            )
            if (view.snapshot.durableReserveUs >= targetReserveUs) {
                break
            }
            val unit = checkNotNull(runtime.plan.unitForFetchKey(key)) {
                "reserve fetch key disappeared from playback plan: $key"
            }
            when (view.resolveExtent(unit.extentSpec)) {
                ExtentResolution.Ready,
                is ExtentResolution.PublishedNotReady -> {
                    // Never open a second owner for a published immutable row,
                    // including one awaiting its already-planned dependencies.
                    skipped += key
                    continue
                }
                ExtentResolution.IdentityConflict ->
                    error("immutable identity conflict before reserve admission: $key")
                ExtentResolution.Absent -> Unit
            }

            // The plan orders dependencies before the parent, but a late
            // mutation/eviction can invalidate them between planning and
            // admission. Stop this pass rather than fetch a parent prematurely.
            val dependenciesReady = unit.extentSpec.dependencyExtentIds.all { id ->
                val dependency = checkNotNull(runtime.plan.unitForExtent(id)) {
                    "reserve dependency disappeared from playback plan: $id"
                }
                view.resolveExtent(dependency.extentSpec) == ExtentResolution.Ready
            }
            if (!dependenciesReady) {
                dependencyBlocked = true
                break
            }

            currentCoroutineContext().ensureActive()
            runtime.ensureReserveOpen()
            val consumerId = "m4-reserve-" + consumerCounter.incrementAndGet()
            val lease = runtime.acquireReserve(key, consumerId)
            requested += key
            val outcome = try {
                lease.awaitOutcome()
            } finally {
                lease.close()
            }
            runtime.ensureReserveOpen()
            if (outcome != FetchOutcomeKind.SUCCESS.name) {
                terminalFailure = outcome
                break
            }
        }

        currentCoroutineContext().ensureActive()
        runtime.ensureReserveOpen()
        runtime.coverageIndex.refresh()
        currentCoroutineContext().ensureActive()
        runtime.ensureReserveOpen()
        val finalReserve = runtime.coverageIndex
            .snapshot(requirements, playheadUs)
            .durableReserveUs

        val stop = when {
            finalReserve >= targetReserveUs -> ReserveExecutionStop.TARGET_REACHED
            terminalFailure != null -> ReserveExecutionStop.FETCH_FAILED
            dependencyBlocked -> ReserveExecutionStop.DEPENDENCY_NOT_READY
            else -> ReserveExecutionStop.PLAN_DEPLETED
        }
        ReserveExecutionReport(
            stop = stop,
            initialReserveUs = plan.durableReserveUs,
            finalReserveUs = finalReserve,
            targetEndUs = plan.targetEndUs,
            requestedFetchKeys = requested.toList(),
            skippedReadyFetchKeys = skipped.toList(),
            planTruncated = plan.truncated,
            terminalFetchOutcome = terminalFailure,
        )
    }
}
