package io.github.definitelystable.spongetube.core.engine.delivery

/**
 * Provider seam for one delivery binding refresh.
 *
 * Contract:
 * - performs exactly ONE provider operation per call; it never retries
 *   internally (retry ownership stays with the recovery chain);
 * - must be cancellable: cancelling the caller abandons the provider
 *   operation instead of completing it;
 * - may throw. A `kotlinx.coroutines.CancellationException` means the
 *   operation was cancelled; every other exception is treated as
 *   [DeliveryMaterialRefresh.Failed];
 * - must return [DeliveryMaterialRefresh.Incompatible] when the refreshed
 *   material cannot serve the same immutable work, and must never return
 *   material that silently changes the work identity.
 */
internal fun interface DeliveryBindingRefresher {
    suspend fun refresh(
        current: DeliveryBindingSnapshot,
        refreshCorrelationId: String,
    ): DeliveryMaterialRefresh
}
