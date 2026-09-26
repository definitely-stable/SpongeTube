package io.github.definitelystable.spongetube.core.engine.recovery

/**
 * Pure, conservative interpretation of a raw [FailureObservation]
 * (M2.md 9-10, M2-C frozen mappings, M2-D refinement).
 *
 * It never sleeps, retries, spends budget, looks at Media3 or refreshes a
 * provider. It never reads exception text and never derives a classification
 * from the legacy M1 `FetchOutcomeKind`. A route change is not an input: a
 * socket failure observed during a route change stays a transport observation.
 *
 * M2-D refinement: only an explicit [ProviderSignal] refines a rejection. A
 * bare 403 stays [FailureClassification.PROVIDER_REJECTED]; 429 is always rate
 * limiting and 408/5xx stay transient responses regardless of the signal.
 */
internal object FailureClassifier {
    fun classify(observation: FailureObservation): FailureClassification =
        when (observation) {
            is FailureObservation.TransportIo -> when (observation.kind) {
                TransportIoKind.CONNECT_TIMEOUT,
                TransportIoKind.READ_TIMEOUT,
                TransportIoKind.CONNECTION_RESET,
                TransportIoKind.PREMATURE_EOF,
                TransportIoKind.IO,
                -> FailureClassification.TRANSIENT_TRANSPORT
                TransportIoKind.TARGET_UNRESOLVED ->
                    FailureClassification.TERMINAL_TRANSPORT
            }

            is FailureObservation.HttpResponse ->
                classifyHttpStatus(observation.statusCode, observation.providerSignal)

            FailureObservation.DeliveryDescriptorStale ->
                FailureClassification.DELIVERY_BINDING_STALE

            is FailureObservation.RangeProtocolFailure ->
                FailureClassification.RANGE_REJECTED

            FailureObservation.ContentIntegrityFailure ->
                FailureClassification.CONTENT_INTEGRITY

            is FailureObservation.StorageFailure -> when (observation.kind) {
                StorageFailureKind.NO_SPACE,
                StorageFailureKind.IO,
                -> FailureClassification.STORAGE_FAILURE
                StorageFailureKind.CONFLICT ->
                    FailureClassification.PUBLICATION_CONFLICT
            }

            is FailureObservation.Cancellation ->
                FailureClassification.CANCELLED

            FailureObservation.InternalFailure ->
                FailureClassification.INTERNAL
        }

    /**
     * Generic HTTP semantics first. A bare 403 is a provider rejection, never a
     * stale delivery binding (F-14); 429 is rate limiting, never transport;
     * 408/5xx are transient responses. Only a rejection in the remaining 4xx
     * range is refined by an explicit [ProviderSignal.BINDING_STALE_CONFIRMED].
     */
    private fun classifyHttpStatus(
        status: Int,
        signal: ProviderSignal,
    ): FailureClassification =
        when {
            status == HTTP_TOO_MANY_REQUESTS ->
                FailureClassification.PROVIDER_RATE_LIMITED
            status == HTTP_REQUEST_TIMEOUT || status in 500..599 ->
                FailureClassification.PROVIDER_TRANSIENT_RESPONSE
            status in 400..499 ->
                if (signal == ProviderSignal.BINDING_STALE_CONFIRMED) {
                    FailureClassification.DELIVERY_BINDING_STALE
                } else {
                    FailureClassification.PROVIDER_REJECTED
                }
            else -> FailureClassification.UNKNOWN
        }

    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
