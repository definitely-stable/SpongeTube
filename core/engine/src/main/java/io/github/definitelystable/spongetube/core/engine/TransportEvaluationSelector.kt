package io.github.definitelystable.spongetube.core.engine

/**
 * Evaluation-only switch. The selection is made BEFORE a FetchBroker is
 * constructed, so both backends traverse the identical existing owner,
 * recovery budget, route permit and extent-publication path.
 *
 * Unavailable never falls back silently and never becomes a performance trial.
 */
internal enum class TransportEvaluationBackend {
    HTTP_URL_CONNECTION_ROUTE_BOUND,
    PLATFORM_HTTP_ENGINE,
}

internal enum class TransportEvaluationEligibility {
    ELIGIBLE,
    UNAVAILABLE_ON_DEVICE,
}

internal data class TransportEvaluationSelection(
    val backend: TransportEvaluationBackend,
    val eligibility: TransportEvaluationEligibility,
    val executor: FetchAttemptExecutor?,
    val backendVersion: String?,
    val implementationId: String?,
)

internal class TransportEvaluationSelector(
    private val control: HttpRangeFetchExecutor,
    private val candidate: FetchAttemptExecutor?,
    private val candidateVersion: String?,
) {
    fun select(backend: TransportEvaluationBackend): TransportEvaluationSelection =
        when (backend) {
            TransportEvaluationBackend.HTTP_URL_CONNECTION_ROUTE_BOUND ->
                TransportEvaluationSelection(
                    backend, TransportEvaluationEligibility.ELIGIBLE,
                    control, "android-http-url-connection", "android-platform-url-connection",
                )
            TransportEvaluationBackend.PLATFORM_HTTP_ENGINE ->
                TransportEvaluationSelection(
                    backend,
                    if (candidate == null) {
                        TransportEvaluationEligibility.UNAVAILABLE_ON_DEVICE
                    } else {
                        TransportEvaluationEligibility.ELIGIBLE
                    },
                    candidate,
                    if (candidate == null) null else candidateVersion,
                    if (candidate == null) null else "android-platform-http-engine",
                )
        }
}
