package io.github.definitelystable.spongetube.core.engine.delivery

private val BINDING_REVISION_PATTERN = Regex("^binding-[1-9][0-9]*$")
private val RECOVERY_CHAIN_ID_PATTERN = Regex("^recovery-[1-9][0-9]*$")
private val FAILURE_ID_PATTERN = Regex("^recovery-[1-9][0-9]*:failure-[1-9][0-9]*$")

/**
 * Local opaque correlation id of mutable delivery material (M2.md 12).
 * Monotonic inside one [DeliveryBindingCoordinator] (`binding-1`, `binding-2`,
 * ...); never persisted, never derived from material.
 */
@JvmInline
internal value class DeliveryBindingRevision(val value: String) {
    init {
        require(BINDING_REVISION_PATTERN.matches(value)) {
            "delivery binding revision must match binding-N: $value"
        }
    }

    override fun toString(): String = value
}

/**
 * Provider-local delivery material. Opaque to core; implementations must never
 * expose secrets through `toString()`.
 */
internal interface DeliveryMaterial

/** One delivery material set paired with the revision it was selected under. */
internal class DeliveryBindingSnapshot(
    val revision: DeliveryBindingRevision,
    val material: DeliveryMaterial,
) {
    override fun toString(): String = "DeliveryBindingSnapshot(revision=$revision)"
}

/**
 * Evidence attribution of one binding request. Plain strings only: this
 * package never depends on recovery types.
 */
internal data class DeliveryBindingCaller(
    val recoveryChainId: String,
    val failureId: String?,
    val fetchKey: String,
    val extentId: String,
) {
    init {
        require(RECOVERY_CHAIN_ID_PATTERN.matches(recoveryChainId)) {
            "recoveryChainId must match recovery-N: $recoveryChainId"
        }
        require(failureId == null || FAILURE_ID_PATTERN.matches(failureId)) {
            "failureId must be null or match recovery-N:failure-N: $failureId"
        }
        require(fetchKey.isNotBlank()) { "fetchKey must not be blank" }
        require(extentId.isNotBlank()) { "extentId must not be blank" }
    }
}

/**
 * Invoked by [DeliveryBindingCoordinator] immediately before it starts ONE
 * actual provider refresh operation. Throwing means "not admitted": no
 * operation starts and no in-flight slot is left behind.
 */
internal fun interface DeliveryBindingRefreshAdmission {
    fun admit(refreshCorrelationId: String)
}

internal enum class DeliveryBindingRefreshResultKind {
    REFRESHED,
    ALREADY_ADVANCED,
    JOINED_REFRESH,
    INCOMPATIBLE,
    FAILED,
    NOT_ADMITTED,
    CLOSED,
}

/** Result of one [DeliveryBindingCoordinator.refresh] call. */
internal sealed interface DeliveryBindingRefreshResult {
    /** The revision the caller asked for. */
    val expected: DeliveryBindingRevision

    /** Discriminator for callers that record actions instead of types. */
    val kind: DeliveryBindingRefreshResultKind

    /** This caller started the operation and it succeeded: expected -> current. */
    data class Refreshed(
        override val expected: DeliveryBindingRevision,
        val current: DeliveryBindingRevision,
        val refreshCorrelationId: String,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.REFRESHED
    }

    /** current != expected at request time; no operation, no charge. */
    data class AlreadyAdvanced(
        override val expected: DeliveryBindingRevision,
        val current: DeliveryBindingRevision,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.ALREADY_ADVANCED
    }

    /** Joined another caller's in-flight operation which succeeded. */
    data class JoinedRefresh(
        override val expected: DeliveryBindingRevision,
        val current: DeliveryBindingRevision,
        val refreshCorrelationId: String,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.JOINED_REFRESH
    }

    /**
     * Operation returned material incompatible with the immutable work; the
     * current revision is unchanged.
     */
    data class Incompatible(
        override val expected: DeliveryBindingRevision,
        val refreshCorrelationId: String,
        val initiatedByCaller: Boolean,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.INCOMPATIBLE
    }

    /**
     * Operation failed, or was cancelled while this caller still waited
     * ([cancelled]: session shutdown or every waiter left); the current
     * revision is unchanged.
     */
    data class Failed(
        override val expected: DeliveryBindingRevision,
        val refreshCorrelationId: String,
        val initiatedByCaller: Boolean,
        val cancelled: Boolean = false,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.FAILED
    }

    /** Admission threw; nothing started. */
    data class NotAdmitted(
        override val expected: DeliveryBindingRevision,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.NOT_ADMITTED
    }

    /** The coordinator is closed; nothing started. */
    data class Closed(
        override val expected: DeliveryBindingRevision,
    ) : DeliveryBindingRefreshResult {
        override val kind: DeliveryBindingRefreshResultKind
            get() = DeliveryBindingRefreshResultKind.CLOSED
    }
}

/** Result of ONE provider operation (refresher output). */
internal sealed interface DeliveryMaterialRefresh {
    data class Material(val material: DeliveryMaterial) : DeliveryMaterialRefresh
    data object Incompatible : DeliveryMaterialRefresh
    data object Failed : DeliveryMaterialRefresh
}
