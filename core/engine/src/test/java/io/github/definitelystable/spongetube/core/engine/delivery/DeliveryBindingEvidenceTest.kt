package io.github.definitelystable.spongetube.core.engine.delivery

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

/**
 * `delivery-binding-events-v1` producer shape (DESIGN 2.2): stable root keys,
 * stable row key order, nulls preserved, bounded capacity and privacy.
 */
@Timeout(30)
class DeliveryBindingEvidenceTest {
    @Test
    fun artifactHasStableRootAndRowShape() = runBlocking {
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val clock = AtomicLong()
        val coordinator = DeliveryBindingCoordinator(
            initialMaterial = EvidenceMaterial("material-1"),
            refresher = { _, _ ->
                DeliveryMaterialRefresh.Material(EvidenceMaterial("material-2"))
            },
            evidence = recorder,
            clockNs = { clock.incrementAndGet() },
        )
        try {
            coordinator.recordSelection(caller(), "fetch-key-1:attempt-1", REVISION_1)
            assertEquals(
                DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
                coordinator.refresh(REVISION_1, caller(), DeliveryBindingRefreshAdmission {}),
            )
        } finally {
            coordinator.shutdown()
        }

        val artifact = recorder.artifact()

        assertEquals(
            listOf("schemaVersion", "runId", "sessionId", "clockDomain", "events"),
            artifact.keys.toList(),
        )
        assertEquals(DeliveryBindingEvidenceRecorder.SCHEMA_VERSION, artifact["schemaVersion"])
        assertEquals(1, artifact["schemaVersion"])
        assertEquals(RUN_ID, artifact["runId"])
        assertEquals(SESSION_ID, artifact["sessionId"])
        assertEquals(DeliveryBindingEvidenceRecorder.CLOCK_DOMAIN, artifact["clockDomain"])
        assertEquals("ANDROID_MONOTONIC", artifact["clockDomain"])

        @Suppress("UNCHECKED_CAST")
        val rows = artifact["events"] as List<Map<String, Any?>>
        assertEquals(4, rows.size)
        val rowKeys = listOf(
            "sequence",
            "elapsedRealtimeNs",
            "recoveryChainId",
            "failureId",
            "fetchKey",
            "extentId",
            "kind",
            "previousRevision",
            "currentRevision",
            "attemptCorrelationId",
            "refreshCorrelationId",
            "outcome",
        )
        rows.forEach { row -> assertEquals(rowKeys, row.keys.toList()) }
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it["sequence"] })
        assertEquals(
            listOf(
                "BINDING_SELECTED_FOR_ATTEMPT",
                "REFRESH_REQUESTED",
                "REFRESH_STARTED",
                "REFRESH_SUCCEEDED",
            ),
            rows.map { it["kind"] },
        )

        // Nulls are preserved, not omitted.
        val selected = rows[0]
        assertEquals("recovery-1", selected["recoveryChainId"])
        assertTrue(selected.containsKey("failureId"))
        assertNull(selected["failureId"])
        assertEquals("fetch-key-1", selected["fetchKey"])
        assertEquals("extent-1", selected["extentId"])
        assertNull(selected["previousRevision"])
        assertEquals("binding-1", selected["currentRevision"])
        assertEquals("fetch-key-1:attempt-1", selected["attemptCorrelationId"])
        assertNull(selected["refreshCorrelationId"])
        assertNull(selected["outcome"])

        val succeeded = rows[3]
        assertTrue(succeeded.containsKey("attemptCorrelationId"))
        assertNull(succeeded["attemptCorrelationId"])
        assertEquals("binding-1", succeeded["previousRevision"])
        assertEquals("binding-2", succeeded["currentRevision"])
        assertEquals("refresh-1", succeeded["refreshCorrelationId"])
        assertEquals("SUCCEEDED", succeeded["outcome"])
    }

    @Test
    fun capacityOverflowMakesArtifactUnusable() {
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID, capacity = 2)
        recorder.onDeliveryBindingEvent(event(1))
        recorder.onDeliveryBindingEvent(event(2))
        recorder.onDeliveryBindingEvent(event(3))

        assertEquals(2, recorder.events().size)
        assertThrows<IllegalStateException> { recorder.artifact() }
    }

    @Test
    fun throwingListenerDoesNotBreakTheCoordinator() = runBlocking {
        val listener = DeliveryBindingEvidenceListener {
            throw IllegalStateException("listener failed")
        }
        val clock = AtomicLong()
        val coordinator = DeliveryBindingCoordinator(
            initialMaterial = EvidenceMaterial("material-1"),
            refresher = { _, _ ->
                DeliveryMaterialRefresh.Material(EvidenceMaterial("material-2"))
            },
            evidence = listener,
            clockNs = { clock.incrementAndGet() },
        )
        try {
            coordinator.recordSelection(caller(), "fetch-key-1:attempt-1", REVISION_1)

            val result = coordinator.refresh(
                REVISION_1,
                caller(),
                DeliveryBindingRefreshAdmission {},
            )

            assertEquals(
                DeliveryBindingRefreshResult.Refreshed(REVISION_1, REVISION_2, "refresh-1"),
                result,
            )
            assertEquals(REVISION_2, coordinator.current().revision)
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun artifactContainsNoMaterialOrUrlText() = runBlocking {
        val secret = "https://provider.example/media?token=super-secret"
        val recorder = DeliveryBindingEvidenceRecorder(RUN_ID, SESSION_ID)
        val clock = AtomicLong()
        val coordinator = DeliveryBindingCoordinator(
            initialMaterial = EvidenceMaterial(secret),
            refresher = { _, _ ->
                DeliveryMaterialRefresh.Material(EvidenceMaterial("$secret-refreshed"))
            },
            evidence = recorder,
            clockNs = { clock.incrementAndGet() },
        )
        try {
            coordinator.recordSelection(caller(), "fetch-key-1:attempt-1", REVISION_1)
            coordinator.refresh(REVISION_1, caller(), DeliveryBindingRefreshAdmission {})
        } finally {
            coordinator.shutdown()
        }

        val rendered = recorder.artifact().toString()

        assertFalse(rendered.contains("super-secret"))
        assertFalse(rendered.contains("provider.example"))
        assertFalse(rendered.contains("https://"))
        assertFalse(rendered.contains("token"))
        assertFalse(rendered.contains("EvidenceMaterial"))
    }

    private fun event(sequence: Long): DeliveryBindingEvent = DeliveryBindingEvent(
        sequence = sequence,
        elapsedRealtimeNs = sequence,
        recoveryChainId = "recovery-1",
        failureId = null,
        fetchKey = "fetch-key-1",
        extentId = "extent-1",
        kind = DeliveryBindingEventKind.REFRESH_REQUESTED,
    )

    private fun caller(): DeliveryBindingCaller = DeliveryBindingCaller(
        recoveryChainId = "recovery-1",
        failureId = null,
        fetchKey = "fetch-key-1",
        extentId = "extent-1",
    )

    private companion object {
        const val RUN_ID = "run-1"
        const val SESSION_ID = "session-1"
    }
}

/** Opaque material whose text must never reach the artifact. */
private class EvidenceMaterial(private val text: String) : DeliveryMaterial {
    override fun toString(): String = text
}

private val REVISION_1 = DeliveryBindingRevision("binding-1")
private val REVISION_2 = DeliveryBindingRevision("binding-2")
