package io.github.definitelystable.spongetube.benchmark

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class M1N4RecoveryTest {

    @Test
    fun n4rShortStaysInsideDurableReserve() {
        val env = environment("m1-f-short", "N4R-SHORT")
        env.gate.open("short-reset")
        env.gate.close("short-close")
        val started = env.harness.startN4R(
            env.sessionId,
            env.scenario,
            env.originBaseUrl,
        )
        val reserveUs = started.getLong(
            M1RecoveryHarnessClient.KEY_INITIAL_RESERVE_US,
        )
        assertTrue("S30 durable reserve must be positive", reserveUs > 0)

        awaitPlayingAndBlocked(env)
        Thread.sleep(SHORT_HOLD_MS)
        env.gate.open("short-open")

        val afterOpen = awaitStatus(
            env,
            RECOVERY_TIMEOUT_MS,
            "SHORT playback progress after restore",
        ) { status ->
            !status.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE) &&
                status.getLong(M1RecoveryHarnessClient.KEY_POSITION_US) >=
                SHORT_MIN_PROGRESS_US
        }
        assertFalse(
            "SHORT must not require a reserve-exhaustion stall",
            afterOpen.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE),
        )
        assertNoPlayerErrors(afterOpen)

        env.harness.finishN4R(env.sessionId)
        env.harness.exportEvidence(env.sessionId)
    }

    @Test
    fun n4rExhaustStallsOnlyAfterLocalHorizonIsConsumed() {
        val env = environment("m1-f-exhaust", "N4R-EXHAUST")
        env.gate.open("exhaust-reset")
        env.gate.close("exhaust-close")
        env.harness.startN4R(
            env.sessionId,
            env.scenario,
            env.originBaseUrl,
        )
        awaitPlayingAndBlocked(env)

        val stalled = awaitStatus(
            env,
            EXHAUST_TIMEOUT_MS,
            "EXHAUST player stall",
        ) {
            it.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE)
        }
        val sequence = stalled.getLong(
            M1RecoveryHarnessClient.KEY_LAST_STALL_SEQUENCE,
        )
        assertTrue("stall sequence was not recorded", sequence >= 0)

        Thread.sleep(EXHAUST_SETTLE_MS)
        env.harness.finishN4R(
            env.sessionId,
            observedStallSequence = sequence,
        )
        env.harness.exportEvidence(env.sessionId)
        env.gate.open("exhaust-cleanup")
    }

    @Test
    fun n4rRestoreResumesSamePlayerAfterObservedStall() {
        val env = environment("m1-f-restore", "N4R-RESTORE")
        env.gate.open("restore-reset")
        env.gate.close("restore-close")
        env.harness.startN4R(
            env.sessionId,
            env.scenario,
            env.originBaseUrl,
        )
        awaitPlayingAndBlocked(env)

        val stalled = awaitStatus(
            env,
            EXHAUST_TIMEOUT_MS,
            "RESTORE player stall",
        ) {
            it.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE)
        }
        val stallSequence = stalled.getLong(
            M1RecoveryHarnessClient.KEY_LAST_STALL_SEQUENCE,
        )
        val stalledPosition = stalled.getLong(
            M1RecoveryHarnessClient.KEY_POSITION_US,
        )
        assertTrue("stall sequence was not recorded", stallSequence >= 0)

        val restoreCommand = "restore-open"
        env.gate.open(restoreCommand)

        val recovered = awaitStatus(
            env,
            RECOVERY_TIMEOUT_MS,
            "same-player playback recovery",
        ) { status ->
            !status.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE) &&
                status.getLong(M1RecoveryHarnessClient.KEY_POSITION_US) >
                stalledPosition + RESTORE_ADVANCE_US
        }
        assertNoPlayerErrors(recovered)

        env.harness.finishN4R(
            env.sessionId,
            observedStallSequence = stallSequence,
            restoreCommandId = restoreCommand,
        )
        env.harness.exportEvidence(env.sessionId)
    }

    @Test
    fun n4rFlapKeepsRecoveryBoundedAcrossThreeGenerations() {
        val env = environment("m1-f-flap", "N4R-FLAP")
        env.gate.open("flap-reset")
        env.gate.close("flap-1-close")
        env.harness.startN4R(
            env.sessionId,
            env.scenario,
            env.originBaseUrl,
        )
        awaitPlayingAndBlocked(env)

        for (generation in 1..3) {
            if (generation > 1) {
                env.gate.close("flap-$generation-close")
                awaitStatus(
                    env,
                    INITIAL_TIMEOUT_MS,
                    "FLAP generation $generation blocked request",
                ) {
                    env.gate.state().activeBlockedRequests > 0
                }
            }

            Thread.sleep(FLAP_HOLD_MS)
            env.gate.open("flap-$generation-open")
            awaitStatus(
                env,
                RECOVERY_TIMEOUT_MS,
                "FLAP generation $generation release",
            ) {
                env.gate.state().activeBlockedRequests == 0
            }
        }

        val recovered = awaitStatus(
            env,
            RECOVERY_TIMEOUT_MS,
            "FLAP final same-player progress",
        ) { status ->
            !status.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE) &&
                status.getLong(M1RecoveryHarnessClient.KEY_POSITION_US) >=
                FLAP_MIN_PROGRESS_US
        }
        assertNoPlayerErrors(recovered)
        env.harness.finishN4R(env.sessionId)
        env.harness.exportEvidence(env.sessionId)
    }

    private fun environment(
        sessionId: String,
        scenario: String,
    ): Environment {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString(ARG_ORIGIN_BASE_URL)?.trimEnd('/')
        val control = args.getString(ARG_CONTROL_BASE_URL)?.trimEnd('/')
        assumeTrue(
            "M1-F N4R requires $ARG_ORIGIN_BASE_URL",
            !origin.isNullOrBlank(),
        )
        assumeTrue(
            "M1-F N4R requires $ARG_CONTROL_BASE_URL",
            !control.isNullOrBlank(),
        )
        return Environment(
            sessionId = sessionId,
            scenario = scenario,
            originBaseUrl = checkNotNull(origin),
            harness = M1RecoveryHarnessClient(instrumentation.context),
            gate = M1MediaLabGateClient(checkNotNull(control)),
        )
    }

    private fun awaitPlayingAndBlocked(env: Environment) {
        awaitStatus(
            env,
            INITIAL_TIMEOUT_MS,
            "${env.scenario} initial local playback and blocked origin",
        ) { status ->
            status.getBoolean(M1RecoveryHarnessClient.KEY_EVER_PLAYED) &&
                env.gate.state().activeBlockedRequests > 0
        }
    }

    private fun awaitStatus(
        env: Environment,
        timeoutMs: Long,
        description: String,
        predicate: (Bundle) -> Boolean,
    ): Bundle {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var last = Bundle()
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            last = env.harness.status(env.sessionId)
            if (predicate(last)) {
                return last
            }
            Thread.sleep(POLL_MS)
        }
        error(
            "timed out waiting for $description; " +
                "lastPositionUs=" +
                last.getLong(M1RecoveryHarnessClient.KEY_POSITION_US) +
                " stall=" +
                last.getBoolean(M1RecoveryHarnessClient.KEY_STALL_ACTIVE),
        )
    }

    private fun assertNoPlayerErrors(status: Bundle) {
        val errors = status.getStringArrayList(
            M1RecoveryHarnessClient.KEY_PLAYER_ERRORS,
        ).orEmpty()
        check(errors.isEmpty()) { "player errors: $errors" }
    }

    private data class Environment(
        val sessionId: String,
        val scenario: String,
        val originBaseUrl: String,
        val harness: M1RecoveryHarnessClient,
        val gate: M1MediaLabGateClient,
    )

    private companion object {
        const val ARG_ORIGIN_BASE_URL =
            "spongetube.m1f.originBaseUrl"
        const val ARG_CONTROL_BASE_URL =
            "spongetube.m1f.controlBaseUrl"
        const val POLL_MS = 100L
        const val INITIAL_TIMEOUT_MS = 60_000L
        const val EXHAUST_TIMEOUT_MS = 60_000L
        const val RECOVERY_TIMEOUT_MS = 60_000L
        const val SHORT_HOLD_MS = 1_000L
        const val EXHAUST_SETTLE_MS = 2_000L
        const val FLAP_HOLD_MS = 500L
        const val SHORT_MIN_PROGRESS_US = 2_000_000L
        const val FLAP_MIN_PROGRESS_US = 2_000_000L
        const val RESTORE_ADVANCE_US = 1_000_000L
    }
}
