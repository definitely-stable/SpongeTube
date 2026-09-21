package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionCalibrationTest {

    @Test
    void reportsConfiguredVersusObservedValues() {
        SessionCalibration calibration = new SessionCalibration();
        ResolvedScenario scenario = ResolvedScenario.testScenario(
                "calibration",
                MediaLabProfile.N1,
                120,
                8_000L,
                1000,
                null,
                null);

        calibration.observeMediaWrite(0L, 1000);
        calibration.observeMediaWrite(1_000_000_000L, 1000);
        calibration.observeFirstBodyDelay(121);
        calibration.observeFirstBodyDelay(119);
        calibration.observeSchedulerSlip(2_000_000L);
        calibration.observeSchedulerSlip(500_000L);

        String json = calibration.toJson(scenario);

        assertTrue(json.contains("\"observedRateBps\":8000"));
        assertTrue(json.contains("\"firstBodyDelayErrorMs\":0"));
        assertTrue(json.contains("\"firstBodyDelaySamplesMs\":[121,119]"));
        assertTrue(json.contains("\"maxSchedulerSlipMs\":2"));
        assertTrue(json.contains("\"schedulerSlipSamplesMs\":[2,1]"));
    }
}
