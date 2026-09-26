package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecoveryPolicyTest {
    private val policy = RecoveryPolicy.DEFAULT
    private val demanded = RecoveryDecisionContext(
        demandPresent = true,
        sessionClosing = false,
        remoteAttemptsRemaining = 3,
        deliveryBindingRefreshesRemaining = 1,
    )
    private val delaySeconds = RetryAfterObservation(
        rawKind = RetryAfterKind.DELAY_SECONDS,
        delaySeconds = 2,
    )
    private val httpDate = RetryAfterObservation(
        rawKind = RetryAfterKind.HTTP_DATE,
        notBeforeUtcEpochMs = 1_790_337_602_000,
    )

    private fun decide(
        observation: FailureObservation,
        context: RecoveryDecisionContext = demanded,
    ): RecoveryDecision =
        policy.decide(FailureClassifier.classify(observation), observation, context)

    private fun assertDecision(
        observation: FailureObservation,
        expectedKind: RecoveryDecisionKind,
        expectedReason: RecoveryDecisionReason,
        context: RecoveryDecisionContext = demanded,
    ) {
        val decision = decide(observation, context)
        assertEquals(expectedKind, decision.kind, observation.toString())
        assertEquals(expectedReason, decision.reason, observation.toString())
    }

    // C-18 / C-19
    @Test
    fun backoffWindowGrowsExponentiallyAndRespectsTheCap() {
        val windows = (1..7).map(policy.backoff::windowMs)

        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L), windows)
        assertEquals(5_000L, policy.backoff.windowMs(Int.MAX_VALUE))
        assertThrows<IllegalArgumentException> { policy.backoff.windowMs(0) }
    }

    // C-17
    @Test
    fun fullJitterDelayStaysInsideTheWindow() {
        val seeded = Random(20260925)
        val jitter = RecoveryJitterSource { window -> seeded.nextLong(window + 1) }

        repeat(1_000) { index ->
            val ordinal = index % 6 + 1
            val delay = policy.backoff.delayMs(ordinal, jitter)
            assertTrue(delay in 0..policy.backoff.windowMs(ordinal), "$ordinal -> $delay")
        }
        assertEquals(0L, policy.backoff.delayMs(1) { 0L })
        assertEquals(500L, policy.backoff.delayMs(1) { it })
    }

    @Test
    fun jitterOutsideTheWindowFailsClosed() {
        assertThrows<IllegalStateException> { policy.backoff.delayMs(1) { it + 1 } }
        assertThrows<IllegalStateException> { policy.backoff.delayMs(1) { -1 } }
    }

    @Test
    fun transientFailuresRetryOnlyWhileDemanded() {
        val timeout = FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT)

        assertEquals(RecoveryDecisionKind.RETRY_AFTER_BACKOFF, decide(timeout).kind)
        assertEquals(
            RecoveryDecisionKind.RETRY_AFTER_BACKOFF,
            decide(FailureObservation.HttpResponse(503)).kind,
        )
        assertEquals(
            RecoveryDecisionKind.COMPLETE_NO_DEMAND,
            decide(timeout, demanded.copy(demandPresent = false)).kind,
        )
    }

    @Test
    fun nonRetryableClassificationsNeverRetry() {
        listOf(
            FailureObservation.HttpResponse(403),
            FailureObservation.RangeProtocolFailure(RangeProtocolKind.FULL_BODY_FOR_RANGE_REQUEST),
            FailureObservation.ContentIntegrityFailure,
            FailureObservation.StorageFailure(StorageFailureKind.NO_SPACE),
            FailureObservation.StorageFailure(StorageFailureKind.IO),
            FailureObservation.TransportIo(TransportIoKind.TARGET_UNRESOLVED),
            FailureObservation.InternalFailure,
            FailureObservation.HttpResponse(302),
        ).forEach { observation ->
            assertEquals(RecoveryDecisionKind.FAIL_TERMINAL, decide(observation).kind, "$observation")
        }
    }

    @Test
    fun storageObservationsNeverTriggerProviderActions() {
        StorageFailureKind.entries.forEach { kind ->
            val decision = decide(FailureObservation.StorageFailure(kind))
            assertTrue(
                decision.kind !in setOf(
                    RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
                    RecoveryDecisionKind.RERESOLVE_PROVIDER,
                    RecoveryDecisionKind.RETRY_AFTER_BACKOFF,
                ),
                "$kind -> $decision",
            )
        }
    }

    @Test
    fun providerSignalsDecideProviderActions() {
        assertEquals(
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            decide(FailureObservation.HttpResponse(429, retryAfter = delaySeconds)).kind,
        )
        assertEquals(
            RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
            decide(FailureObservation.DeliveryDescriptorStale).kind,
        )
    }

    @Test
    fun cancellationEndsForNoDemandOrContinuesForLateDemand() {
        val cancelled = FailureObservation.Cancellation(CancellationKind.NO_CONSUMERS)

        assertEquals(RecoveryDecisionKind.CONTINUE_FOR_DEMAND, decide(cancelled).kind)
        assertEquals(
            RecoveryDecisionKind.COMPLETE_NO_DEMAND,
            decide(cancelled, demanded.copy(demandPresent = false)).kind,
        )
        assertEquals(
            RecoveryDecisionKind.COMPLETE_SESSION,
            decide(FailureObservation.Cancellation(CancellationKind.SESSION_SHUTDOWN)).kind,
        )
    }

    @Test
    fun sessionShutdownTakesPrecedenceOverEveryClassification() {
        val closing = demanded.copy(sessionClosing = true)

        listOf(
            FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT),
            FailureObservation.HttpResponse(429),
            FailureObservation.InternalFailure,
        ).forEach { observation ->
            assertEquals(RecoveryDecisionKind.COMPLETE_SESSION, decide(observation, closing).kind)
        }
    }

    // M2-D precedence: session closing wins over both provider actions.
    @Test
    fun sessionClosingWinsOverProviderActions() {
        val closing = demanded.copy(sessionClosing = true)

        assertDecision(
            FailureObservation.HttpResponse(429, retryAfter = delaySeconds),
            RecoveryDecisionKind.COMPLETE_SESSION,
            RecoveryDecisionReason.SESSION_SHUTDOWN,
            closing,
        )
        assertDecision(
            FailureObservation.DeliveryDescriptorStale,
            RecoveryDecisionKind.COMPLETE_SESSION,
            RecoveryDecisionReason.SESSION_SHUTDOWN,
            closing,
        )
        assertDecision(
            FailureObservation.Cancellation(CancellationKind.SESSION_SHUTDOWN),
            RecoveryDecisionKind.COMPLETE_SESSION,
            RecoveryDecisionReason.SESSION_SHUTDOWN,
            demanded.copy(deliveryBindingRefreshesRemaining = 0),
        )
    }

    // M2-D precedence: released demand completes before any provider action.
    @Test
    fun rateLimitWithoutDemandDoesNotWait() {
        assertDecision(
            FailureObservation.HttpResponse(429, retryAfter = delaySeconds),
            RecoveryDecisionKind.COMPLETE_NO_DEMAND,
            RecoveryDecisionReason.DEMAND_RELEASED,
            demanded.copy(demandPresent = false),
        )
    }

    @Test
    fun staleBindingWithNoDemandDoesNotRefresh() {
        val released = demanded.copy(demandPresent = false)

        assertDecision(
            FailureObservation.DeliveryDescriptorStale,
            RecoveryDecisionKind.COMPLETE_NO_DEMAND,
            RecoveryDecisionReason.DEMAND_RELEASED,
            released,
        )
        assertDecision(
            FailureObservation.HttpResponse(
                403,
                providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
            ),
            RecoveryDecisionKind.COMPLETE_NO_DEMAND,
            RecoveryDecisionReason.DEMAND_RELEASED,
            released,
        )
    }

    // M2-D precedence: REMOTE_ATTEMPT exhaustion beats Retry-After validity.
    @Test
    fun rateLimitWithNoRemoteAttemptsDecidesWaitButNoAttemptRemains() {
        val exhausted = demanded.copy(remoteAttemptsRemaining = 0)

        assertDecision(
            FailureObservation.HttpResponse(429),
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            RecoveryDecisionReason.PROVIDER_THROTTLED,
            exhausted,
        )
        assertDecision(
            FailureObservation.HttpResponse(429, retryAfter = delaySeconds),
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            RecoveryDecisionReason.PROVIDER_THROTTLED,
            exhausted,
        )
    }

    @Test
    fun rateLimitWithoutRetryAfterFailsClosed() {
        assertDecision(
            FailureObservation.HttpResponse(429),
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryDecisionReason.RETRY_AFTER_ABSENT,
        )
    }

    @Test
    fun rateLimitWithMalformedRetryAfterFailsClosed() {
        assertDecision(
            FailureObservation.HttpResponse(
                429,
                retryAfter = RetryAfterObservation.MALFORMED,
            ),
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryDecisionReason.RETRY_AFTER_MALFORMED,
        )
    }

    @Test
    fun rateLimitWithValidRetryAfterWaits() {
        assertDecision(
            FailureObservation.HttpResponse(429, retryAfter = delaySeconds),
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            RecoveryDecisionReason.PROVIDER_THROTTLED,
        )
        assertDecision(
            FailureObservation.HttpResponse(429, retryAfter = httpDate),
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            RecoveryDecisionReason.PROVIDER_THROTTLED,
        )
    }

    @Test
    fun staleBindingDecidesRefresh() {
        assertDecision(
            FailureObservation.DeliveryDescriptorStale,
            RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
            RecoveryDecisionReason.STALE_BINDING_SIGNAL,
        )
        assertDecision(
            FailureObservation.HttpResponse(
                403,
                providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
            ),
            RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
            RecoveryDecisionReason.STALE_BINDING_SIGNAL,
        )
    }

    // F-14: a bare 403 never becomes a stale-binding refresh.
    @Test
    fun bare403NeverRefreshes() {
        assertDecision(
            FailureObservation.HttpResponse(403),
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryDecisionReason.NON_RETRYABLE_CLASSIFICATION,
        )
        assertTrue(decide(FailureObservation.HttpResponse(403)).kind !=
            RecoveryDecisionKind.REFRESH_DELIVERY_BINDING)
    }

    // A Retry-After never moves a non-429 classification to a provider wait.
    @Test
    fun retryAfterNeverAffectsNonRateLimitClassifications() {
        assertDecision(
            FailureObservation.HttpResponse(503, retryAfter = delaySeconds),
            RecoveryDecisionKind.RETRY_AFTER_BACKOFF,
            RecoveryDecisionReason.PROVIDER_TRANSIENT_STATUS,
        )
        assertDecision(
            FailureObservation.HttpResponse(503, retryAfter = RetryAfterObservation.MALFORMED),
            RecoveryDecisionKind.RETRY_AFTER_BACKOFF,
            RecoveryDecisionReason.PROVIDER_TRANSIENT_STATUS,
        )
        assertDecision(
            FailureObservation.HttpResponse(403, retryAfter = delaySeconds),
            RecoveryDecisionKind.FAIL_TERMINAL,
            RecoveryDecisionReason.NON_RETRYABLE_CLASSIFICATION,
        )
    }

    @Test
    fun defaultPolicyIsV2WithRefreshDimension() {
        val default = RecoveryPolicy.DEFAULT

        assertEquals("sponge-recovery-v2", RecoveryPolicy.DEFAULT_POLICY_ID)
        assertEquals("sponge-recovery-v2", default.policyId)
        assertEquals(4, RecoveryPolicy.DEFAULT_REMOTE_ATTEMPT_LIMIT)
        assertEquals(1, RecoveryPolicy.DEFAULT_DELIVERY_BINDING_REFRESH_LIMIT)
        assertEquals(
            mapOf(
                RecoveryBudgetDimension.REMOTE_ATTEMPT to 4,
                RecoveryBudgetDimension.DELIVERY_BINDING_REFRESH to 1,
            ),
            default.budget.limits,
        )
        assertEquals(RecoveryBackoff(baseMs = 500, capMs = 5_000), default.backoff)
        assertThrows<IllegalArgumentException> {
            RecoveryDecisionContext(
                demandPresent = true,
                sessionClosing = false,
                remoteAttemptsRemaining = 3,
                deliveryBindingRefreshesRemaining = -1,
            )
        }
    }

    @Test
    fun routeContextIsNotAClassificationInput() {
        // A transport observation during a route change stays transport:
        // the classifier has no route input at all.
        val reset = FailureObservation.TransportIo(TransportIoKind.CONNECTION_RESET)
        assertEquals(FailureClassification.TRANSIENT_TRANSPORT, FailureClassifier.classify(reset))
        assertEquals(ObservationPlane.TRANSPORT, reset.plane)
    }
}
