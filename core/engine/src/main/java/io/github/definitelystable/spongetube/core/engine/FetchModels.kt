package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.CancellationKind
import io.github.definitelystable.spongetube.core.engine.recovery.FailureObservation
import io.github.definitelystable.spongetube.core.engine.recovery.StorageFailureKind
import io.github.definitelystable.spongetube.core.engine.recovery.TransportIoKind
import io.github.definitelystable.spongetube.core.storage.CommittedExtent
import io.github.definitelystable.spongetube.core.storage.ExtentSpec

@JvmInline
value class FetchKey(val value: String) {
    init {
        require(value.isNotBlank()) { "fetch key must not be blank" }
        require(value.length <= 512) { "fetch key must be <= 512 characters" }
    }

    override fun toString(): String = value
}

@JvmInline
internal value class FetchId(val value: String) {
    init {
        require(value.isNotBlank()) { "fetch id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
internal value class FetchConsumerId(val value: String) {
    init {
        require(value.isNotBlank()) { "fetch consumer id must not be blank" }
        require(value.length <= 256) {
            "fetch consumer id must be <= 256 characters"
        }
    }

    override fun toString(): String = value
}

internal enum class FetchPriority {
    RESERVE,
    PLAYBACK,
}

internal enum class FetchConsumerKind {
    RESERVE,
    PLAYBACK;

    internal val priority: FetchPriority
        get() = when (this) {
            RESERVE -> FetchPriority.RESERVE
            PLAYBACK -> FetchPriority.PLAYBACK
        }
}

internal data class FetchConsumer(
    val id: FetchConsumerId,
    val kind: FetchConsumerKind,
)

internal data class FetchRequest(
    val fetchKey: FetchKey,
    val extentSpec: ExtentSpec,
)

internal data class FetchByteAccounting(
    val networkBytes: Long,
    val uniqueRangeBytes: Long,
    val duplicateRangeBytes: Long,
    val rejectedOrUnmappedBytes: Long,
) {
    init {
        require(networkBytes >= 0)
        require(uniqueRangeBytes >= 0)
        require(duplicateRangeBytes >= 0)
        require(rejectedOrUnmappedBytes >= 0)
        require(
            networkBytes ==
                uniqueRangeBytes +
                duplicateRangeBytes +
                rejectedOrUnmappedBytes,
        ) {
            "network byte accounting must balance"
        }
    }

    companion object {
        val ZERO = FetchByteAccounting(0, 0, 0, 0)
    }
}

/**
 * Legacy `fetch-events-v3` outcome vocabulary. Since M2-C it is a compatibility
 * projection of the raw [FailureObservation] (see [legacyOutcomeKind]) kept so
 * historical M1 verifiers keep their meaning. Recovery never classifies from
 * it; the retry-flavoured names do not imply that anything retries.
 */
internal enum class FetchOutcomeKind {
    SUCCESS,
    RETRYABLE_TRANSPORT_FAILURE,
    TERMINAL_TRANSPORT_FAILURE,
    DESCRIPTOR_STALE,
    RANGE_REJECTED,
    CONTENT_INTEGRITY_REJECTED,
    STORAGE_NO_SPACE,
    STORAGE_IO,
    STORAGE_CONFLICT,
    CANCELLED_NO_CONSUMERS,
    CANCELLED_BROKER_SHUTDOWN,
    INTERNAL_FAILURE,
}

/**
 * Terminal result of one FetchBroker owner. Since M2-C one owner is exactly
 * one physical remote attempt: [attempts] is 0 (cancelled or refused before
 * the request) or 1. It is an M1 compatibility field, never a budget.
 */
internal data class FetchOutcome(
    val kind: FetchOutcomeKind,
    val attempts: Int,
    val bytes: FetchByteAccounting,
    val committedExtent: CommittedExtent? = null,
    val failure: FailureObservation? = null,
) {
    init {
        require(attempts in 0..1) { "one owner makes at most one attempt" }
        require((kind == FetchOutcomeKind.SUCCESS) == (committedExtent != null)) {
            "only successful fetches may expose a committed extent"
        }
        require((kind == FetchOutcomeKind.SUCCESS) == (failure == null)) {
            "every non-successful owner carries exactly one raw observation"
        }
        if (failure != null) {
            require(kind == failure.legacyOutcomeKind()) {
                "legacy outcome must be the projection of the observation"
            }
        }
    }

    val isSuccess: Boolean
        get() = kind == FetchOutcomeKind.SUCCESS
}

internal class FetchIdentityConflictException(
    message: String,
) : IllegalArgumentException(message)

internal enum class FetchAcquireDisposition {
    NEW_OWNER,
    JOINED_RUNNING,
    WAITED_CANCELLING,
}

internal interface FetchHandle : AutoCloseable {
    val fetchKey: FetchKey
    val fetchId: FetchId

    val acquireDisposition: FetchAcquireDisposition

    /**
     * Compatibility convenience for harness/tests. Evidence must use
     * [acquireDisposition] so a RUNNING join is never conflated with waiting
     * behind a CANCELLING owner's terminal barrier.
     */
    val joinedExisting: Boolean
        get() = acquireDisposition != FetchAcquireDisposition.NEW_OWNER

    suspend fun await(): FetchOutcome

    /**
     * Awaits the owner's terminal outcome without releasing this consumer.
     * Valid after [close]: a releasing owner (RecoveryCoordinator) uses it to
     * wait for the M1 cancellation barrier before deciding what comes next.
     */
    suspend fun awaitTerminal(): FetchOutcome

    /**
     * Raises the effective priority of the running owner without restarting
     * it. Never lowers priority and never creates a physical attempt.
     */
    fun raisePriority(priority: FetchPriority)

    override fun close()
}

/**
 * M1-compatible projection of a raw observation into `fetch-events-v3`
 * vocabulary. It preserves the M1 meaning of each legacy value exactly (for
 * example M1 already reported 408/429/5xx as RETRYABLE_TRANSPORT_FAILURE);
 * it is evidence compatibility only and is never a classification input.
 */
internal fun FailureObservation.legacyOutcomeKind(): FetchOutcomeKind =
    when (this) {
        is FailureObservation.TransportIo ->
            if (kind == TransportIoKind.TARGET_UNRESOLVED) {
                FetchOutcomeKind.TERMINAL_TRANSPORT_FAILURE
            } else {
                FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE
            }
        is FailureObservation.HttpResponse ->
            if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
                FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE
            } else {
                FetchOutcomeKind.TERMINAL_TRANSPORT_FAILURE
            }
        FailureObservation.DeliveryDescriptorStale ->
            FetchOutcomeKind.DESCRIPTOR_STALE
        is FailureObservation.RangeProtocolFailure ->
            FetchOutcomeKind.RANGE_REJECTED
        FailureObservation.ContentIntegrityFailure ->
            FetchOutcomeKind.CONTENT_INTEGRITY_REJECTED
        is FailureObservation.StorageFailure -> when (kind) {
            StorageFailureKind.NO_SPACE ->
                FetchOutcomeKind.STORAGE_NO_SPACE
            StorageFailureKind.IO ->
                FetchOutcomeKind.STORAGE_IO
            StorageFailureKind.CONFLICT ->
                FetchOutcomeKind.STORAGE_CONFLICT
        }
        is FailureObservation.Cancellation -> when (kind) {
            CancellationKind.NO_CONSUMERS ->
                FetchOutcomeKind.CANCELLED_NO_CONSUMERS
            CancellationKind.SESSION_SHUTDOWN ->
                FetchOutcomeKind.CANCELLED_BROKER_SHUTDOWN
        }
        FailureObservation.InternalFailure ->
            FetchOutcomeKind.INTERNAL_FAILURE
    }
