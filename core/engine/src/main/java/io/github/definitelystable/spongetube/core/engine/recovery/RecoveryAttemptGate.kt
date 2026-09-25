package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchRequest

/** Why an external attempt was permitted; an open, typed label. */
@JvmInline
internal value class RecoveryPermitReason(val value: String) {
    init {
        require(value.matches(Regex("^[A-Z][A-Z0-9_]*$"))) {
            "permit reason must be an upper-case token"
        }
    }

    override fun toString(): String = value

    companion object {
        val ALWAYS_PERMIT = RecoveryPermitReason("ALWAYS_PERMIT")
    }
}

/**
 * Permission to open the next physical attempt. Carries no Android type; a
 * route-aware gate (M2-F) may attach the `routeEpoch` it evaluated.
 */
internal data class RecoveryAttemptPermit(
    val routeEpoch: Long?,
    val reason: RecoveryPermitReason,
) {
    init {
        require(routeEpoch == null || routeEpoch >= 1)
    }
}

/**
 * Seam before every physical attempt of a RecoveryChain (M2-C).
 *
 * Waiting here is not a retry: it charges no budget and must be event-driven
 * (for example a StateFlow condition), never polling. The coordinator cancels
 * the wait when the chain loses its last consumer or the session shuts down.
 * M2-C ships only [ALWAYS_PERMIT]; M2-F connects DefaultRouteMonitor and
 * SessionRouteGuard here.
 */
internal fun interface RecoveryAttemptGate {
    suspend fun awaitPermit(chainId: RecoveryChainId): RecoveryAttemptPermit

    companion object {
        val ALWAYS_PERMIT = RecoveryAttemptGate {
            RecoveryAttemptPermit(
                routeEpoch = null,
                reason = RecoveryPermitReason.ALWAYS_PERMIT,
            )
        }
    }
}

/**
 * Local reconciliation for STORAGE_CONFLICT (M1 semantics): refresh the
 * CoverageIndex and resolve the immutable extent before any network action.
 */
internal fun interface RecoveryLocalReconciler {
    suspend fun reconcile(request: FetchRequest): LocalReconciliation
}
