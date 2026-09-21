package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GlobalBandwidthGovernorTest {

    @Test
    void sharesOneAbsoluteDeadlineBudgetAcrossReservations() throws Exception {
        FakeTime time = new FakeTime();
        SessionCalibration calibration = new SessionCalibration();

        // 8 KiB per second.
        GlobalBandwidthGovernor governor =
                new GlobalBandwidthGovernor(65_536L, time, time, calibration);

        governor.reserve(8192);
        assertEquals(0L, time.nowNanos());

        governor.reserve(8192);
        assertEquals(1_000_000_000L, time.nowNanos());

        governor.reserve(8192);
        assertEquals(2_000_000_000L, time.nowNanos());
    }

    @Test
    void unlimitedModeDoesNotAdvanceTime() throws Exception {
        FakeTime time = new FakeTime();
        GlobalBandwidthGovernor governor =
                new GlobalBandwidthGovernor(null, time, time, new SessionCalibration());

        governor.reserve(8192);
        governor.reserve(8192);

        assertEquals(0L, time.nowNanos());
    }

    @Test
    void slotDurationRoundsUpInsteadOfRunningFast() {
        assertEquals(1_000_000_000L, GlobalBandwidthGovernor.slotDurationNs(1, 8));
        assertEquals(500_000_000L, GlobalBandwidthGovernor.slotDurationNs(1, 16));
    }
}
