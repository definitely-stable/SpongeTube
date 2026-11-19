package io.github.definitelystable.spongetube

import io.github.definitelystable.spongetube.core.engine.ReserveExecutionStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class M4ReserveLabStateTest {
    @Test
    fun unknownCoverageIsNotMisrepresentedAsZero() {
        val initial = M4ReserveLabState()
        assertEquals(LabPhase.DISABLED, initial.phase)
        assertEquals(null, initial.durableReserveUs)
        assertEquals("Durable playable reserve: not measured", initial.reserveLabel)
    }

    @Test
    fun zeroIsAnActualMeasuredReserve() {
        val state = M4ReserveLabState(
            phase = LabPhase.READY,
            durableReserveUs = 0L,
        )
        assertEquals("Durable playable reserve: 0 µs (0.000 s)", state.reserveLabel)
        assertEquals(0.0f, state.progress)
    }

    @Test
    fun preciseDurableCoverageUsesMicrosecondsNotPlayerBufferedBytes() {
        val state = M4ReserveLabState(
            phase = LabPhase.TARGET_REACHED,
            durableReserveUs = 9_941_333L,
            requestedUnits = 4,
        )
        assertEquals(
            "Durable playable reserve: 9941333 µs (9.941 s)",
            state.reserveLabel,
        )
        assertEquals("Target: 8.000 s", state.targetLabel)
        assertEquals(1.0f, state.progress)
    }

    @Test
    fun incompleteRequiredTracksCannotPretendToReachTarget() {
        val state = M4ReserveLabState(
            phase = LabPhase.PLAN_DEPLETED,
            durableReserveUs = 3_000_000L,
        )
        assertEquals(0.375f, state.progress)
        assertTrue(state.reserveLabel.contains("3000000 µs"))
    }

    @Test
    fun stopReasonsNeverMapToGenericSuccess() {
        assertEquals(
            LabPhase.TARGET_REACHED,
            LabPhase.fromStop(ReserveExecutionStop.TARGET_REACHED),
        )
        assertEquals(
            LabPhase.PLAN_DEPLETED,
            LabPhase.fromStop(ReserveExecutionStop.PLAN_DEPLETED),
        )
        assertEquals(
            LabPhase.FETCH_FAILED,
            LabPhase.fromStop(ReserveExecutionStop.FETCH_FAILED),
        )
        assertEquals(
            LabPhase.DEPENDENCY_NOT_READY,
            LabPhase.fromStop(ReserveExecutionStop.DEPENDENCY_NOT_READY),
        )
    }
}
