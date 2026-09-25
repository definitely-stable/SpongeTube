package io.github.definitelystable.spongetube.core.engine.recovery

/**
 * Open budget dimension (M2-C). Not a closed enum: M2-D may add
 * `DELIVERY_BINDING_REFRESH` or `PROVIDER_RESOLVE` without changing the ledger.
 */
@JvmInline
internal value class RecoveryBudgetDimension(val value: String) {
    init {
        require(value.matches(DIMENSION_PATTERN)) {
            "budget dimension must match $DIMENSION_PATTERN"
        }
    }

    override fun toString(): String = value

    companion object {
        private val DIMENSION_PATTERN = Regex("^[A-Z][A-Z0-9_]*$")

        /** One physical remote request through a FetchBroker owner. */
        val REMOTE_ATTEMPT = RecoveryBudgetDimension("REMOTE_ATTEMPT")
    }
}

/**
 * Versioned budget policy: the dimensions it declares are the only
 * dimensions that may ever be charged.
 */
internal data class RecoveryBudgetPolicy(
    val policyId: String,
    val limits: Map<RecoveryBudgetDimension, Int>,
) {
    init {
        require(policyId.matches(POLICY_ID_PATTERN)) {
            "policyId must match $POLICY_ID_PATTERN"
        }
        require(limits.isNotEmpty()) { "a budget policy declares dimensions" }
        require(limits.values.all { it > 0 }) { "limits must be > 0" }
    }

    fun limit(dimension: RecoveryBudgetDimension): Int? = limits[dimension]

    companion object {
        private val POLICY_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]*$")
    }
}

/** One explicit spend; the ledger changes by exactly [amount]. */
internal data class RecoveryBudgetCharge(
    val dimension: RecoveryBudgetDimension,
    val amount: Int,
    val spentBefore: Int,
    val spentAfter: Int,
    val limit: Int,
) {
    init {
        require(amount > 0)
        require(spentAfter == spentBefore + amount)
        require(spentAfter <= limit)
    }
}

internal class RecoveryBudgetExhaustedException(
    val dimension: RecoveryBudgetDimension,
) : IllegalStateException("recovery budget exhausted for $dimension")

internal class UndeclaredBudgetDimensionException(
    val dimension: RecoveryBudgetDimension,
) : IllegalArgumentException("budget dimension $dimension is not declared by the policy")

/**
 * Monotonic per-chain ledger (M2.md 11.2). It is created once per
 * RecoveryChain and has no reset operation: a new owner, backoff, priority
 * escalation, route wait or refresh cannot change it except through [charge].
 * Every dimension declared by the policy is always present in [snapshot].
 */
internal class RecoveryBudgetLedger(
    val policy: RecoveryBudgetPolicy,
) {
    private val spent = LinkedHashMap<RecoveryBudgetDimension, Int>().apply {
        policy.limits.keys.forEach { put(it, 0) }
    }

    @Synchronized
    fun spent(dimension: RecoveryBudgetDimension): Int =
        spent[dimension] ?: throw UndeclaredBudgetDimensionException(dimension)

    @Synchronized
    fun remaining(dimension: RecoveryBudgetDimension): Int =
        requireLimit(dimension) - spent(dimension)

    @Synchronized
    fun canCharge(
        dimension: RecoveryBudgetDimension,
        amount: Int = 1,
    ): Boolean {
        require(amount > 0) { "a charge must be positive" }
        return spent(dimension) + amount <= requireLimit(dimension)
    }

    /** Charges or fails closed; never partially applied. */
    @Synchronized
    fun charge(
        dimension: RecoveryBudgetDimension,
        amount: Int = 1,
    ): RecoveryBudgetCharge {
        require(amount > 0) { "a charge must be positive" }
        val limit = requireLimit(dimension)
        val before = spent(dimension)
        if (before + amount > limit) {
            throw RecoveryBudgetExhaustedException(dimension)
        }
        spent[dimension] = before + amount
        return RecoveryBudgetCharge(
            dimension = dimension,
            amount = amount,
            spentBefore = before,
            spentAfter = before + amount,
            limit = limit,
        )
    }

    @Synchronized
    fun snapshot(): Map<RecoveryBudgetDimension, Int> = LinkedHashMap(spent)

    private fun requireLimit(dimension: RecoveryBudgetDimension): Int =
        policy.limit(dimension) ?: throw UndeclaredBudgetDimensionException(dimension)
}
