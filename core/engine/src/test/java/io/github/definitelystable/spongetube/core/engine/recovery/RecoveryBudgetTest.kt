package io.github.definitelystable.spongetube.core.engine.recovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecoveryBudgetTest {
    private val policy = RecoveryBudgetPolicy(
        policyId = "budget-test-v1",
        limits = mapOf(RecoveryBudgetDimension.REMOTE_ATTEMPT to 4),
    )

    @Test
    fun chargesAreExplicitMonotonicAndBoundedByTheLimit() {
        val ledger = RecoveryBudgetLedger(policy)

        val charges = (1..4).map { ledger.charge(RecoveryBudgetDimension.REMOTE_ATTEMPT) }

        assertEquals(listOf(0, 1, 2, 3), charges.map { it.spentBefore })
        assertEquals(listOf(1, 2, 3, 4), charges.map { it.spentAfter })
        assertTrue(charges.all { it.amount == 1 && it.limit == 4 })
        assertFalse(ledger.canCharge(RecoveryBudgetDimension.REMOTE_ATTEMPT))
        assertEquals(0, ledger.remaining(RecoveryBudgetDimension.REMOTE_ATTEMPT))
        assertThrows<RecoveryBudgetExhaustedException> {
            ledger.charge(RecoveryBudgetDimension.REMOTE_ATTEMPT)
        }
        // A refused charge is not partially applied.
        assertEquals(4, ledger.spent(RecoveryBudgetDimension.REMOTE_ATTEMPT))
    }

    @Test
    fun undeclaredDimensionFailsClosed() {
        val ledger = RecoveryBudgetLedger(policy)
        val refresh = RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH

        assertThrows<UndeclaredBudgetDimensionException> { ledger.charge(refresh) }
        assertThrows<UndeclaredBudgetDimensionException> { ledger.canCharge(refresh) }
        assertEquals(
            mapOf(RecoveryBudgetDimension.REMOTE_ATTEMPT to 0),
            ledger.snapshot(),
        )
    }

    @Test
    fun openDimensionsAreDeclaredByThePolicyAndNeverDisappear() {
        val refresh = RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH
        val ledger = RecoveryBudgetLedger(
            RecoveryBudgetPolicy(
                policyId = "budget-test-v2",
                limits = mapOf(RecoveryBudgetDimension.REMOTE_ATTEMPT to 4, refresh to 1),
            ),
        )

        ledger.charge(refresh)

        assertEquals(
            mapOf(RecoveryBudgetDimension.REMOTE_ATTEMPT to 0, refresh to 1),
            ledger.snapshot(),
        )
    }

    @Test
    fun refreshAndRemoteAttemptDimensionsNeverChangeEachOther() {
        val refresh = RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH
        val remote = RecoveryBudgetDimension.REMOTE_ATTEMPT
        val ledger = RecoveryBudgetLedger(
            RecoveryBudgetPolicy(
                policyId = "budget-test-v3",
                limits = mapOf(remote to 4, refresh to 1),
            ),
        )

        assertEquals(1, ledger.remaining(refresh))
        ledger.charge(refresh)

        assertEquals(1, ledger.spent(refresh))
        assertEquals(0, ledger.spent(remote))
        assertEquals(4, ledger.remaining(remote))
        assertFalse(ledger.canCharge(refresh))

        ledger.charge(remote)

        assertEquals(1, ledger.spent(refresh))
        assertEquals(1, ledger.spent(remote))
        assertEquals(
            mapOf(remote to 1, refresh to 1),
            ledger.snapshot(),
        )
    }

    @Test
    fun ledgerHasNoResetOperation() {
        val mutators = RecoveryBudgetLedger::class.java.methods
            .map { it.name.lowercase() }
            .filter { name ->
                listOf("reset", "clear", "refund", "restore", "set").any(name::startsWith)
            }

        assertTrue(mutators.isEmpty(), mutators.toString())
    }

    @Test
    fun policyAndDimensionIdentitiesAreValidated() {
        assertThrows<IllegalArgumentException> { RecoveryBudgetDimension("remote") }
        assertThrows<IllegalArgumentException> {
            RecoveryBudgetPolicy("Bad Id", mapOf(RecoveryBudgetDimension.REMOTE_ATTEMPT to 4))
        }
        assertThrows<IllegalArgumentException> {
            RecoveryBudgetPolicy("ok-v1", mapOf(RecoveryBudgetDimension.REMOTE_ATTEMPT to 0))
        }
        assertThrows<IllegalArgumentException> { RecoveryBudgetPolicy("ok-v1", emptyMap()) }
        assertThrows<IllegalArgumentException> {
            RecoveryPolicy(
                budget = RecoveryBudgetPolicy(
                    "no-remote-v1",
                    mapOf(RecoveryBudgetDimension("PROVIDER_RESOLVE") to 1),
                ),
                backoff = RecoveryBackoff(500, 5_000),
            )
        }
    }

    @Test
    fun productionPolicyIsSpongeRecoveryV2WithRefreshDimension() {
        val policy = RecoveryPolicy.DEFAULT

        assertEquals("sponge-recovery-v2", policy.policyId)
        assertEquals(
            mapOf(
                RecoveryBudgetDimension.REMOTE_ATTEMPT to 4,
                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH to 1,
            ),
            policy.budget.limits,
        )
        assertEquals(RecoveryBackoff(baseMs = 500, capMs = 5_000), policy.backoff)
    }
}
