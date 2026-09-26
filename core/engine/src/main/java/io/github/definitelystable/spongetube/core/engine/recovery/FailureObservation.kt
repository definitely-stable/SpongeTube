package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation

/**
 * Where a raw failure observation originated (M2.md sections 6 and 9).
 *
 * [TRANSPORT] and [PROVIDER] are the M2 fault planes. A received HTTP status is
 * always [PROVIDER]: the transport delivered a response. [RESPONSE_CONTRACT]
 * covers a delivered response that violates the immutable work contract
 * (range or content integrity); it does not attribute the fault to the
 * provider or the network. [LOCAL] is runtime-internal (cancellation,
 * internal failure).
 */
internal enum class ObservationPlane {
    TRANSPORT,
    PROVIDER,
    RESPONSE_CONTRACT,
    STORAGE,
    LOCAL,
}

internal enum class TransportIoKind {
    CONNECT_TIMEOUT,
    READ_TIMEOUT,

    /**
     * An established connection failed with a socket-level error
     * (`java.net.SocketException`). The exact errno is not observable without
     * parsing exception messages, which M2-C forbids.
     */
    CONNECTION_RESET,

    /** The response body ended before the requested range was delivered. */
    PREMATURE_EOF,

    /** Any other `IOException` raised by the transport. */
    IO,

    /** The transport has no locator for the work item; no request was sent. */
    TARGET_UNRESOLVED,
}

internal enum class RangeProtocolKind {
    FULL_BODY_FOR_RANGE_REQUEST,
    CONTENT_RANGE_MISSING,
    CONTENT_RANGE_MISMATCH,
    RESPONSE_LENGTH_MISMATCH,

    /** The transport returned bytes outside the requested range. */
    RESPONSE_BYTES_OUTSIDE_RANGE,

    /** The immutable range lies outside the transport resource; no request sent. */
    REQUEST_OUTSIDE_RESOURCE,
}

internal enum class StorageFailureKind {
    NO_SPACE,
    IO,

    /** Another publisher already committed the same immutable extent (M1). */
    CONFLICT,
}

internal enum class CancellationKind {
    NO_CONSUMERS,
    SESSION_SHUTDOWN,
}

/**
 * Provider-neutral explicit signal from the provider adapter (M2.md 20, M2-D).
 * [BINDING_STALE_CONFIRMED] is present only when the adapter has explicit
 * evidence that the delivery binding is stale; it refines an otherwise
 * non-retryable rejection and never replaces the raw status.
 */
internal enum class ProviderSignal {
    NONE,
    BINDING_STALE_CONFIRMED,
}

/**
 * Raw observation of one failed physical owner (M2-C). It records only what
 * was observed: no classification, no retryability, no exception text, URL or
 * header value. [FailureClassifier] interprets it; [RecoveryPolicy] decides.
 */
internal sealed interface FailureObservation {
    val plane: ObservationPlane

    data class TransportIo(
        val kind: TransportIoKind,
    ) : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.TRANSPORT
    }

    /**
     * An HTTP response was received with a non-success status.
     *
     * The raw fact ([statusCode]) is always retained; [providerSignal] never
     * replaces it. [retryAfter] is the normalized header observation (never
     * the raw header value) and [deliveryBindingRevision] is the mutable
     * delivery material selected for the failed owner, when one was selected.
     */
    data class HttpResponse(
        val statusCode: Int,
        val retryAfter: RetryAfterObservation = RetryAfterObservation.ABSENT,
        val providerSignal: ProviderSignal = ProviderSignal.NONE,
        val deliveryBindingRevision: DeliveryBindingRevision? = null,
    ) : FailureObservation {
        init {
            require(statusCode in 100..599) { "invalid HTTP status $statusCode" }
        }

        override val plane: ObservationPlane
            get() = ObservationPlane.PROVIDER
    }

    /** The M1 descriptor seam reported that delivery material is stale. */
    data object DeliveryDescriptorStale : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.PROVIDER
    }

    data class RangeProtocolFailure(
        val kind: RangeProtocolKind,
    ) : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.RESPONSE_CONTRACT
    }

    data object ContentIntegrityFailure : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.RESPONSE_CONTRACT
    }

    data class StorageFailure(
        val kind: StorageFailureKind,
    ) : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.STORAGE
    }

    data class Cancellation(
        val kind: CancellationKind,
    ) : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.LOCAL
    }

    data object InternalFailure : FailureObservation {
        override val plane: ObservationPlane
            get() = ObservationPlane.LOCAL
    }
}

/** Portable evidence form: typed fields only, never free text. */
internal fun FailureObservation.toArtifactMap(): Map<String, Any?> {
    val type: String
    val kind: String
    var httpStatus: Int? = null
    when (this) {
        is FailureObservation.TransportIo -> {
            type = "TRANSPORT_IO"
            kind = this.kind.name
        }
        is FailureObservation.HttpResponse -> {
            type = "HTTP_RESPONSE"
            kind = "HTTP_STATUS"
            httpStatus = statusCode
        }
        FailureObservation.DeliveryDescriptorStale -> {
            type = "DELIVERY_DESCRIPTOR"
            kind = "DESCRIPTOR_STALE"
        }
        is FailureObservation.RangeProtocolFailure -> {
            type = "RANGE_PROTOCOL"
            kind = this.kind.name
        }
        FailureObservation.ContentIntegrityFailure -> {
            type = "CONTENT_INTEGRITY"
            kind = "DIGEST_OR_LENGTH_MISMATCH"
        }
        is FailureObservation.StorageFailure -> {
            type = "STORAGE"
            kind = this.kind.name
        }
        is FailureObservation.Cancellation -> {
            type = "CANCELLATION"
            kind = this.kind.name
        }
        FailureObservation.InternalFailure -> {
            type = "INTERNAL"
            kind = "INTERNAL_FAILURE"
        }
    }
    return linkedMapOf(
        "plane" to plane.name,
        "type" to type,
        "kind" to kind,
        "httpStatus" to httpStatus,
    )
}

/**
 * `failure-decision-events-v2` projection: the [toArtifactMap] row plus the
 * normalized `Retry-After` observation, the provider signal and the selected
 * delivery binding revision. The three extra keys are null for a non-HTTP
 * observation; the raw status remains the v1 `httpStatus` field.
 */
internal fun FailureObservation.toArtifactMapV2(): Map<String, Any?> {
    val v1 = toArtifactMap()
    val http = this as? FailureObservation.HttpResponse
    return linkedMapOf(
        "plane" to v1.getValue("plane"),
        "type" to v1.getValue("type"),
        "kind" to v1.getValue("kind"),
        "httpStatus" to v1.getValue("httpStatus"),
        "retryAfter" to http?.retryAfter?.toRetryAfterArtifactMap(),
        "providerSignal" to http?.providerSignal?.name,
        "deliveryBindingRevision" to http?.deliveryBindingRevision?.value,
    )
}

/**
 * Normalized `Retry-After` evidence form; never the raw header value.
 * `delaySeconds` is a duration without a clock domain, `notBeforeUtcEpochMs`
 * is a PROVIDER_WALL_CLOCK instant only for [RetryAfterKind.HTTP_DATE].
 */
private fun RetryAfterObservation.toRetryAfterArtifactMap(): Map<String, Any?> =
    linkedMapOf(
        "rawKind" to rawKind.name,
        "delaySeconds" to delaySeconds,
        "notBeforeUtcEpochMs" to notBeforeUtcEpochMs,
        "clockDomain" to if (rawKind == RetryAfterKind.HTTP_DATE) {
            "PROVIDER_WALL_CLOCK"
        } else {
            null
        },
    )
