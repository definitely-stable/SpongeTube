package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.FetchOutcomeKind
import io.github.definitelystable.spongetube.core.engine.legacyOutcomeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** M2-C frozen classification anchors (C-11..C-14 and item 23). */
class FailureClassifierTest {
    private fun classify(observation: FailureObservation) =
        FailureClassifier.classify(observation)

    // C-13
    @Test
    fun socketLevelTransportObservationsAreTransient() {
        listOf(
            TransportIoKind.CONNECT_TIMEOUT,
            TransportIoKind.READ_TIMEOUT,
            TransportIoKind.CONNECTION_RESET,
            TransportIoKind.PREMATURE_EOF,
            TransportIoKind.IO,
        ).forEach { kind ->
            assertEquals(
                FailureClassification.TRANSIENT_TRANSPORT,
                classify(FailureObservation.TransportIo(kind)),
                kind.name,
            )
        }
        assertEquals(
            FailureClassification.TERMINAL_TRANSPORT,
            classify(FailureObservation.TransportIo(TransportIoKind.TARGET_UNRESOLVED)),
        )
    }

    // C-11
    @Test
    fun http429IsRateLimitingAndNeverTransport() {
        val observation = FailureObservation.HttpResponse(429)

        assertEquals(FailureClassification.PROVIDER_RATE_LIMITED, classify(observation))
        assertNotEquals(FailureClassification.TRANSIENT_TRANSPORT, classify(observation))
        assertEquals(ObservationPlane.PROVIDER, observation.plane)
    }

    // C-12
    @Test
    fun bare403IsProviderRejectionNeverAStaleBinding() {
        val observation = FailureObservation.HttpResponse(403)

        assertEquals(FailureClassification.PROVIDER_REJECTED, classify(observation))
        assertNotEquals(FailureClassification.DELIVERY_BINDING_STALE, classify(observation))
    }

    @Test
    fun everyReceivedHttpStatusIsAProviderPlaneObservation() {
        (100..599).forEach { status ->
            val observation = FailureObservation.HttpResponse(status)
            assertEquals(ObservationPlane.PROVIDER, observation.plane)
            val classification = classify(observation)
            assertNotEquals(FailureClassification.TRANSIENT_TRANSPORT, classification, "$status")
            assertNotEquals(FailureClassification.TERMINAL_TRANSPORT, classification, "$status")
            assertNotEquals(FailureClassification.STORAGE_FAILURE, classification, "$status")
        }
        assertEquals(
            FailureClassification.PROVIDER_TRANSIENT_RESPONSE,
            classify(FailureObservation.HttpResponse(503)),
        )
        assertEquals(
            FailureClassification.PROVIDER_TRANSIENT_RESPONSE,
            classify(FailureObservation.HttpResponse(408)),
        )
        assertEquals(
            FailureClassification.PROVIDER_REJECTED,
            classify(FailureObservation.HttpResponse(404)),
        )
    }

    // C-14
    @Test
    fun unrecognizedStatusClassifiesUnknownAndFailsClosed() {
        listOf(101, 204, 302, 304).forEach { status ->
            val classification = classify(FailureObservation.HttpResponse(status))
            assertEquals(FailureClassification.UNKNOWN, classification, "$status")
            val decision = RecoveryPolicy.DEFAULT.decide(
                classification,
                FailureObservation.HttpResponse(status),
                RecoveryDecisionContext(true, false, 3),
            )
            assertEquals(RecoveryDecisionKind.FAIL_TERMINAL, decision.kind)
            assertEquals(RecoveryDecisionReason.UNKNOWN_FAILS_CLOSED, decision.reason)
        }
    }

    @Test
    fun responseContractStorageAndLocalObservationsKeepTheirOwnMeaning() {
        RangeProtocolKind.entries.forEach { kind ->
            assertEquals(
                FailureClassification.RANGE_REJECTED,
                classify(FailureObservation.RangeProtocolFailure(kind)),
            )
        }
        assertEquals(
            FailureClassification.CONTENT_INTEGRITY,
            classify(FailureObservation.ContentIntegrityFailure),
        )
        assertEquals(
            FailureClassification.STORAGE_FAILURE,
            classify(FailureObservation.StorageFailure(StorageFailureKind.NO_SPACE)),
        )
        assertEquals(
            FailureClassification.STORAGE_FAILURE,
            classify(FailureObservation.StorageFailure(StorageFailureKind.IO)),
        )
        assertEquals(
            FailureClassification.PUBLICATION_CONFLICT,
            classify(FailureObservation.StorageFailure(StorageFailureKind.CONFLICT)),
        )
        assertEquals(
            FailureClassification.DELIVERY_BINDING_STALE,
            classify(FailureObservation.DeliveryDescriptorStale),
        )
        assertEquals(
            FailureClassification.CANCELLED,
            classify(FailureObservation.Cancellation(CancellationKind.NO_CONSUMERS)),
        )
        assertEquals(FailureClassification.INTERNAL, classify(FailureObservation.InternalFailure))
    }

    @Test
    fun legacyProjectionPreservesTheM1OutcomeVocabulary() {
        assertEquals(
            FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE,
            FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT).legacyOutcomeKind(),
        )
        assertEquals(
            FetchOutcomeKind.RETRYABLE_TRANSPORT_FAILURE,
            FailureObservation.HttpResponse(429).legacyOutcomeKind(),
        )
        assertEquals(
            FetchOutcomeKind.TERMINAL_TRANSPORT_FAILURE,
            FailureObservation.HttpResponse(403).legacyOutcomeKind(),
        )
        // The classifier never uses the legacy value: 429 stays rate limiting.
        assertEquals(
            FailureClassification.PROVIDER_RATE_LIMITED,
            classify(FailureObservation.HttpResponse(429)),
        )
    }

    @Test
    fun observationEvidenceCarriesTypedFieldsOnly() {
        val artifact = FailureObservation.HttpResponse(429).toArtifactMap()

        assertEquals(
            mapOf(
                "plane" to "PROVIDER",
                "type" to "HTTP_RESPONSE",
                "kind" to "HTTP_STATUS",
                "httpStatus" to 429,
            ),
            artifact,
        )
    }
}
