package io.github.definitelystable.spongetube.core.engine

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

internal data class FetchAttemptBudget(
    val maxAttempts: Int,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be > 0" }
    }
}

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

internal data class FetchOutcome(
    val kind: FetchOutcomeKind,
    val attempts: Int,
    val bytes: FetchByteAccounting,
    val committedExtent: CommittedExtent? = null,
) {
    init {
        require(attempts >= 0)
        require((kind == FetchOutcomeKind.SUCCESS) == (committedExtent != null)) {
            "only successful fetches may expose a committed extent"
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

    override fun close()
}
