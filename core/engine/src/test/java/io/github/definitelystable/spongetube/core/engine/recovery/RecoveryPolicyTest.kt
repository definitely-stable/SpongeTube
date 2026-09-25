package io.github.definitelystable.spongetube.core.engine.recovery

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
    )

    private fun decide(
        observation: FailureObservation,
        context: RecoveryDecisionContext = demanded,
    ): RecoveryDecision =
        policy.decide(FailureClassifier.classify(observation), observation, context)

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
    fun providerSignalsDecideProviderActionsThatM2DWillImplement() {
        assertEquals(
            RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
            decide(FailureObservation.HttpResponse(429)).kind,
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

    @Test
    fun routeContextIsNotAClassificationInput() {
        // A transport observation during a route change stays transport:
        // the classifier has no route input at all.
        val reset = FailureObservation.TransportIo(TransportIoKind.CONNECTION_RESET)
        assertEquals(FailureClassification.TRANSIENT_TRANSPORT, FailureClassifier.classify(reset))
        assertEquals(ObservationPlane.TRANSPORT, reset.plane)
    }
}
