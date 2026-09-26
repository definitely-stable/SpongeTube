package io.github.definitelystable.spongetube.core.engine.recovery

import io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingRevision
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterKind
import io.github.definitelystable.spongetube.core.engine.delivery.RetryAfterObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * M2-D `failure-decision-events-v2` row shape: the v1 row plus the normalized
 * Retry-After observation, the provider signal, the delivery binding revision,
 * the refresh budget context and the executed provider action. The schema
 * itself is validated later by the Python oracles; this test pins the exact
 * key set and order produced by the recorder.
 */
class RecoveryEvidenceV2Test {
    private val notBeforeUtcEpochMs = 1_790_337_602_000L
    private val wallClockNowUtcEpochMs = 1_790_337_600_000L

    private val context = RecoveryDecisionContext(
        demandPresent = true,
        sessionClosing = false,
        remoteAttemptsRemaining = 3,
        deliveryBindingRefreshesRemaining = 1,
    )

    private fun row(
        sequence: Long,
        chainOrdinal: Int,
        fetchKey: String,
        fetchId: String?,
        attemptCorrelationId: String?,
        observation: FailureObservation,
        classification: FailureClassification,
        decision: RecoveryDecision,
        action: RecoveryActionRecord,
        decisionContext: RecoveryDecisionContext = context,
    ): FailureDecisionEvent = FailureDecisionEvent(
        sequence = sequence,
        elapsedRealtimeNs = sequence * 1_000,
        sessionId = "m2-d-evidence",
        recoveryChainId = RecoveryChainId("recovery-$chainOrdinal"),
        failureId = "recovery-$chainOrdinal:failure-1",
        fetchKey = fetchKey,
        fetchId = fetchId,
        attemptCorrelationId = attemptCorrelationId,
        routeEpoch = null,
        observation = observation,
        classification = classification,
        decision = decision,
        context = decisionContext,
        action = action,
    )

    private fun assertRowShape(
        map: Map<String, Any?>,
        expectedObservationKeys: List<String>,
        expectedActionKeys: List<String>,
    ) {
        assertEquals(
            listOf(
                "sequence",
                "elapsedRealtimeNs",
                "sessionId",
                "recoveryChainId",
                "failureId",
                "fetchKey",
                "fetchId",
                "attemptCorrelationId",
                "routeEpoch",
                "observation",
                "classification",
                "decision",
                "context",
                "action",
            ),
            map.keys.toList(),
        )
        assertEquals(
            expectedObservationKeys,
            (map.getValue("observation") as Map<*, *>).keys.toList(),
        )
        assertEquals(
            listOf(
                "demandPresent",
                "sessionClosing",
                "remoteAttemptsRemaining",
                "deliveryBindingRefreshesRemaining",
            ),
            (map.getValue("context") as Map<*, *>).keys.toList(),
        )
        assertEquals(
            expectedActionKeys,
            (map.getValue("action") as Map<*, *>).keys.toList(),
        )
    }

    private fun assertObservationJson(
        expected: String,
        event: FailureDecisionEvent,
    ) {
        assertEquals(expected, recoveryJson(event.toArtifactMap().getValue("observation")))
    }

    private fun assertActionJson(
        expected: String,
        event: FailureDecisionEvent,
    ) {
        assertEquals(expected, recoveryJson(event.toArtifactMap().getValue("action")))
    }

    private fun Map<*, *>.section(key: String): Map<*, *> =
        checkNotNull(get(key)) { "missing evidence section $key" } as Map<*, *>

    @Test
    fun rateLimitWaitProviderRowSerializesWithTheExactV2KeySet() {
        val event = row(
            sequence = 1,
            chainOrdinal = 1,
            fetchKey = "fixture:M2D/video/evidence",
            fetchId = "fetch-1",
            attemptCorrelationId = "fetch-1:attempt-1",
            observation = FailureObservation.HttpResponse(
                statusCode = 429,
                retryAfter = RetryAfterObservation(
                    rawKind = RetryAfterKind.HTTP_DATE,
                    notBeforeUtcEpochMs = notBeforeUtcEpochMs,
                ),
                deliveryBindingRevision = DeliveryBindingRevision("binding-1"),
            ),
            classification = FailureClassification.PROVIDER_RATE_LIMITED,
            decision = RecoveryDecision(
                RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
                RecoveryDecisionReason.PROVIDER_THROTTLED,
            ),
            action = RecoveryActionRecord(
                kind = RecoveryActionKind.WAIT_PROVIDER,
                providerWait = ProviderWaitRecord(
                    rawKind = RetryAfterKind.HTTP_DATE,
                    waitMs = 2_000,
                    notBeforeUtcEpochMs = notBeforeUtcEpochMs,
                    wallClockNowUtcEpochMs = wallClockNowUtcEpochMs,
                ),
            ),
        )
        val map = event.toArtifactMap()

        assertRowShape(
            map,
            expectedObservationKeys = listOf(
                "plane",
                "type",
                "kind",
                "httpStatus",
                "retryAfter",
                "providerSignal",
                "deliveryBindingRevision",
            ),
            expectedActionKeys = listOf(
                "kind",
                "delayMs",
                "retryOrdinal",
                "reconciliation",
                "exhaustedDimension",
                "providerWait",
                "deliveryBinding",
            ),
        )
        assertEquals("PROVIDER_RATE_LIMITED", map["classification"])
        assertEquals("WAIT_UNTIL_PROVIDER", (map.getValue("decision") as Map<*, *>)["kind"])
        assertEquals("PROVIDER_THROTTLED", (map.getValue("decision") as Map<*, *>)["reason"])
        assertEquals("WAIT_PROVIDER", (map.getValue("action") as Map<*, *>)["kind"])
        assertEquals(
            listOf("rawKind", "delaySeconds", "notBeforeUtcEpochMs", "clockDomain"),
            map.section("observation").section("retryAfter").keys.toList(),
        )
        assertEquals(
            listOf(
                "rawKind",
                "waitMs",
                "notBeforeUtcEpochMs",
                "wallClockNowUtcEpochMs",
                "wallClockDomain",
            ),
            map.section("action").section("providerWait").keys.toList(),
        )
        assertObservationJson(
            """{"plane":"PROVIDER", "type":"HTTP_RESPONSE", "kind":"HTTP_STATUS", "httpStatus":429, "retryAfter":{"rawKind":"HTTP_DATE", "delaySeconds":null, "notBeforeUtcEpochMs":1790337602000, "clockDomain":"PROVIDER_WALL_CLOCK"}, "providerSignal":"NONE", "deliveryBindingRevision":"binding-1"}""",
            event,
        )
        assertActionJson(
            """{"kind":"WAIT_PROVIDER", "delayMs":null, "retryOrdinal":null, "reconciliation":null, "exhaustedDimension":null, "providerWait":{"rawKind":"HTTP_DATE", "waitMs":2000, "notBeforeUtcEpochMs":1790337602000, "wallClockNowUtcEpochMs":1790337600000, "wallClockDomain":"PROVIDER_WALL_CLOCK"}, "deliveryBinding":null}""",
            event,
        )
    }

    @Test
    fun deliveryBindingRefreshRowSerializesWithTheExactV2KeySet() {
        val event = row(
            sequence = 2,
            chainOrdinal = 2,
            fetchKey = "fixture:M2D/video/refresh",
            fetchId = "fetch-2",
            attemptCorrelationId = "fetch-2:attempt-1",
            observation = FailureObservation.HttpResponse(
                statusCode = 403,
                providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
                deliveryBindingRevision = DeliveryBindingRevision("binding-1"),
            ),
            classification = FailureClassification.DELIVERY_BINDING_STALE,
            decision = RecoveryDecision(
                RecoveryDecisionKind.REFRESH_DELIVERY_BINDING,
                RecoveryDecisionReason.STALE_BINDING_SIGNAL,
            ),
            action = RecoveryActionRecord(
                kind = RecoveryActionKind.REFRESH_DELIVERY_BINDING,
                deliveryBinding = DeliveryBindingActionRecord(
                    expectedRevision = DeliveryBindingRevision("binding-1"),
                    result = DeliveryBindingActionResult.REFRESHED,
                    currentRevision = DeliveryBindingRevision("binding-2"),
                    refreshCorrelationId = "refresh-1",
                    charged = true,
                ),
            ),
        )
        val map = event.toArtifactMap()

        assertRowShape(
            map,
            expectedObservationKeys = listOf(
                "plane",
                "type",
                "kind",
                "httpStatus",
                "retryAfter",
                "providerSignal",
                "deliveryBindingRevision",
            ),
            expectedActionKeys = listOf(
                "kind",
                "delayMs",
                "retryOrdinal",
                "reconciliation",
                "exhaustedDimension",
                "providerWait",
                "deliveryBinding",
            ),
        )
        assertEquals("DELIVERY_BINDING_STALE", map["classification"])
        assertEquals(
            "REFRESH_DELIVERY_BINDING",
            (map.getValue("decision") as Map<*, *>)["kind"],
        )
        assertEquals(
            "STALE_BINDING_SIGNAL",
            (map.getValue("decision") as Map<*, *>)["reason"],
        )
        assertEquals(
            "REFRESH_DELIVERY_BINDING",
            (map.getValue("action") as Map<*, *>)["kind"],
        )
        assertEquals(
            listOf(
                "expectedRevision",
                "result",
                "currentRevision",
                "refreshCorrelationId",
                "charged",
            ),
            map.section("action").section("deliveryBinding").keys.toList(),
        )
        assertObservationJson(
            """{"plane":"PROVIDER", "type":"HTTP_RESPONSE", "kind":"HTTP_STATUS", "httpStatus":403, "retryAfter":{"rawKind":"ABSENT", "delaySeconds":null, "notBeforeUtcEpochMs":null, "clockDomain":null}, "providerSignal":"BINDING_STALE_CONFIRMED", "deliveryBindingRevision":"binding-1"}""",
            event,
        )
        assertActionJson(
            """{"kind":"REFRESH_DELIVERY_BINDING", "delayMs":null, "retryOrdinal":null, "reconciliation":null, "exhaustedDimension":null, "providerWait":null, "deliveryBinding":{"expectedRevision":"binding-1", "result":"REFRESHED", "currentRevision":"binding-2", "refreshCorrelationId":"refresh-1", "charged":true}}""",
            event,
        )
    }

    @Test
    fun transportIoRowSerializesWithTheExactV2KeySet() {
        val event = row(
            sequence = 3,
            chainOrdinal = 3,
            fetchKey = "fixture:M2D/video/transport",
            fetchId = null,
            attemptCorrelationId = null,
            observation = FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT),
            classification = FailureClassification.TRANSIENT_TRANSPORT,
            decision = RecoveryDecision(
                RecoveryDecisionKind.RETRY_AFTER_BACKOFF,
                RecoveryDecisionReason.TRANSIENT_FAILURE,
            ),
            action = RecoveryActionRecord(
                kind = RecoveryActionKind.SCHEDULE_BACKOFF,
                delayMs = 100,
                retryOrdinal = 1,
            ),
        )
        val map = event.toArtifactMap()

        assertRowShape(
            map,
            expectedObservationKeys = listOf(
                "plane",
                "type",
                "kind",
                "httpStatus",
                "retryAfter",
                "providerSignal",
                "deliveryBindingRevision",
            ),
            expectedActionKeys = listOf(
                "kind",
                "delayMs",
                "retryOrdinal",
                "reconciliation",
                "exhaustedDimension",
                "providerWait",
                "deliveryBinding",
            ),
        )
        val observation = map.getValue("observation") as Map<*, *>
        assertNull(observation["retryAfter"])
        assertNull(observation["providerSignal"])
        assertNull(observation["deliveryBindingRevision"])
        assertObservationJson(
            """{"plane":"TRANSPORT", "type":"TRANSPORT_IO", "kind":"READ_TIMEOUT", "httpStatus":null, "retryAfter":null, "providerSignal":null, "deliveryBindingRevision":null}""",
            event,
        )
        assertActionJson(
            """{"kind":"SCHEDULE_BACKOFF", "delayMs":100, "retryOrdinal":1, "reconciliation":null, "exhaustedDimension":null, "providerWait":null, "deliveryBinding":null}""",
            event,
        )
    }

    @Test
    fun delaySecondsProviderWaitHasNoProviderWallClockDomain() {
        val event = row(
            sequence = 4,
            chainOrdinal = 4,
            fetchKey = "fixture:M2D/video/delay",
            fetchId = "fetch-4",
            attemptCorrelationId = "fetch-4:attempt-1",
            observation = FailureObservation.HttpResponse(
                statusCode = 429,
                retryAfter = RetryAfterObservation(
                    rawKind = RetryAfterKind.DELAY_SECONDS,
                    delaySeconds = 2,
                ),
            ),
            classification = FailureClassification.PROVIDER_RATE_LIMITED,
            decision = RecoveryDecision(
                RecoveryDecisionKind.WAIT_UNTIL_PROVIDER,
                RecoveryDecisionReason.PROVIDER_THROTTLED,
            ),
            action = RecoveryActionRecord(
                kind = RecoveryActionKind.WAIT_PROVIDER,
                providerWait = ProviderWaitRecord(
                    rawKind = RetryAfterKind.DELAY_SECONDS,
                    waitMs = 2_000,
                    notBeforeUtcEpochMs = null,
                    wallClockNowUtcEpochMs = null,
                ),
            ),
        )
        val providerWait = event.toArtifactMap()
            .section("action")
            .section("providerWait")

        assertEquals("DELAY_SECONDS", providerWait["rawKind"])
        assertEquals(2_000L, providerWait["waitMs"])
        assertNull(providerWait["notBeforeUtcEpochMs"])
        assertNull(providerWait["wallClockNowUtcEpochMs"])
        assertNull(providerWait["wallClockDomain"])
    }

    @Test
    fun exhaustedBudgetRowCarriesItsSpentDimension() {
        val event = row(
            sequence = 5,
            chainOrdinal = 5,
            fetchKey = "fixture:M2D/video/exhausted",
            fetchId = "fetch-5",
            attemptCorrelationId = "fetch-5:attempt-1",
            observation = FailureObservation.TransportIo(TransportIoKind.READ_TIMEOUT),
            classification = FailureClassification.TRANSIENT_TRANSPORT,
            decision = RecoveryDecision(
                RecoveryDecisionKind.RETRY_AFTER_BACKOFF,
                RecoveryDecisionReason.TRANSIENT_FAILURE,
            ),
            action = RecoveryActionRecord(
                kind = RecoveryActionKind.TERMINATE_BUDGET_EXHAUSTED,
                exhaustedDimension = RecoveryBudgetDimension.REMOTE_ATTEMPT,
            ),
            decisionContext = context.copy(remoteAttemptsRemaining = 0),
        )
        val action = event.toArtifactMap().getValue("action") as Map<*, *>

        assertEquals("TERMINATE_BUDGET_EXHAUSTED", action["kind"])
        assertEquals("REMOTE_ATTEMPT", action["exhaustedDimension"])
    }

    @Test
    fun recorderWritesV2FailureArtifactAndV1BudgetArtifact() {
        val recorder = RecoveryEvidenceRecorder("m2-d-run", "m2-d-evidence")
        val failure = recorder.failureArtifact("sponge-recovery-test-v2")
        val budget = recorder.budgetArtifact("sponge-recovery-test-v2")

        assertEquals(RecoveryEvidenceRecorder.FAILURE_SCHEMA_VERSION, failure["schemaVersion"])
        assertEquals(RecoveryEvidenceRecorder.BUDGET_SCHEMA_VERSION, budget["schemaVersion"])
        assertEquals(2, failure["schemaVersion"])
        assertEquals(1, budget["schemaVersion"])
        assertEquals(
            listOf("schemaVersion", "runId", "sessionId", "policyId", "clockDomain", "failures"),
            failure.keys.toList(),
        )
        assertEquals(
            listOf("schemaVersion", "runId", "sessionId", "policyId", "clockDomain", "events"),
            budget.keys.toList(),
        )
    }

    @Test
    fun recorderFailureRowsUseTheV2RowProjection() {
        val recorder = RecoveryEvidenceRecorder("m2-d-run", "m2-d-evidence")
        val event = row(
            sequence = 6,
            chainOrdinal = 6,
            fetchKey = "fixture:M2D/video/recorder",
            fetchId = "fetch-6",
            attemptCorrelationId = "fetch-6:attempt-1",
            observation = FailureObservation.HttpResponse(429),
            classification = FailureClassification.PROVIDER_RATE_LIMITED,
            decision = RecoveryDecision(
                RecoveryDecisionKind.FAIL_TERMINAL,
                RecoveryDecisionReason.RETRY_AFTER_ABSENT,
            ),
            action = RecoveryActionRecord(RecoveryActionKind.TERMINATE_FAILURE),
        )
        recorder.onFailureDecision(event)

        val artifact = recorder.failureArtifact("sponge-recovery-test-v2")
        val rows = artifact.getValue("failures") as List<*>

        assertEquals(1, rows.size)
        assertEquals(
            recoveryJson(event.toArtifactMap()),
            recoveryJson(rows.single()),
        )
        assertEquals(
            recoveryJson(event.observation.toArtifactMapV2()),
            recoveryJson((rows.single() as Map<*, *>).section("observation")),
        )
    }

    @Test
    fun v1ObservationProjectionStaysExactlyFourKeys() {
        val observation = FailureObservation.HttpResponse(
            statusCode = 429,
            retryAfter = RetryAfterObservation(
                rawKind = RetryAfterKind.DELAY_SECONDS,
                delaySeconds = 2,
            ),
            providerSignal = ProviderSignal.BINDING_STALE_CONFIRMED,
            deliveryBindingRevision = DeliveryBindingRevision("binding-1"),
        )

        val artifact = observation.toArtifactMap()

        assertEquals(
            listOf("plane", "type", "kind", "httpStatus"),
            artifact.keys.toList(),
        )
        assertEquals(
            mapOf(
                "plane" to "PROVIDER",
                "type" to "HTTP_RESPONSE",
                "kind" to "HTTP_STATUS",
                "httpStatus" to 429,
            ),
            artifact,
        )
        assertEquals(
            """{"plane":"PROVIDER", "type":"HTTP_RESPONSE", "kind":"HTTP_STATUS", "httpStatus":429}""",
            recoveryJson(artifact),
        )
    }
}
