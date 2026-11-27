package io.github.definitelystable.spongetube

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.definitelystable.spongetube.core.engine.FixtureTransportSession
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeConfig
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeRuntime
import io.github.definitelystable.spongetube.core.engine.PlaybackRequirementSet
import io.github.definitelystable.spongetube.core.engine.ReserveExecutionStop
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1PlaybackPlanFactory
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/**
 * Opt-in DEBUG-ONLY F1 laboratory. This activity is absent from release and
 * benchmark APKs and has a separate launcher, preserving the M0 baseline.
 *
 * A session owns exactly one PlaybackBridgeRuntime and ExtentStore.
 * There is no autoplay, timer-triggered download, queue or separate owner:
 * only a user's "Fill reserve once" invokes the engine's bounded pass.
 *
 * The progress indicator reads CoverageIndex.refresh()+snapshot(), never
 * Media3 bufferedPosition, received byte count or the attempted fetch list.
 * Disabling/backgrounding cancels the caller-owned pass before shutdown.
 */
@OptIn(SpongeBridgeApi::class)
class M4ReserveLabActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var commands: Channel<LabCommand>? = null
    private var activationId = 0L
    private var refreshReturnPhase: LabPhase? = null
    private var labState by mutableStateOf(M4ReserveLabState())

    // Read only on the main thread by the debug lifecycle instrumentation test.
    internal val sessionPhaseForTest: LabPhase get() = labState.phase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            M4ReserveLabScreen(
                state = labState,
                onEnable = ::enableLab,
                onFill = ::fillOnce,
                onRefresh = ::refreshOnce,
                onDisable = ::disableLab,
            )
        }
    }

    private fun enableLab() {
        if (labState.phase != LabPhase.DISABLED) return
        val id = ++activationId
        val channel = Channel<LabCommand>(capacity = 1)
        commands = channel
        labState = M4ReserveLabState(phase = LabPhase.CONNECTING)

        sessionJob = scope.launch {
            var store: ExtentStore? = null
            var bridge: PlaybackBridgeRuntime? = null
            var ownsSession = false
            try {
                // Keep the store lease exclusive across Activity recreation,
                // including the old instance's non-cancellable teardown.
                sessionMutex.lock()
                ownsSession = true
                currentCoroutineContext().ensureActive()
                // SHA-256-verified packaged F1 is a debug-only asset. No
                // provider/user-supplied URL or release build is involved.
                val plan = withContext(Dispatchers.IO) {
                    F1PlaybackPlanFactory(F1FixtureAssets(assets)).plan()
                }
                val openedStore = ExtentStore.open(applicationContext)
                store = openedStore
                val active = PlaybackBridgeRuntime.open(
                    store = openedStore,
                    plan = plan,
                    transport = FixtureTransportSession(ORIGIN_URL),
                    config = PlaybackBridgeConfig(sessionId = SESSION_ID),
                )
                bridge = active
                val requirements = PlaybackRequirementSet(
                    F1FixtureCatalog.ASSET,
                    F1FixtureCatalog.REQUIRED_REPRESENTATIONS,
                )

                suspend fun refreshCoverage() {
                    active.coverageIndex.refresh()
                    val snapshot = active.coverageIndex.snapshot(requirements, PLAYHEAD_US)
                    currentCoroutineContext().ensureActive()
                    if (activationId == id && labState.phase != LabPhase.STOPPING) {
                        labState = labState.copy(durableReserveUs = snapshot.durableReserveUs)
                    }
                }

                refreshCoverage()
                if (activationId == id && labState.phase != LabPhase.STOPPING) {
                    labState = labState.copy(phase = LabPhase.READY)
                }

                for (command in channel) {
                    currentCoroutineContext().ensureActive()
                    if (activationId != id || labState.phase == LabPhase.STOPPING) break
                    when (command) {
                        LabCommand.REFRESH -> {
                            val previous = checkNotNull(refreshReturnPhase)
                            refreshCoverage()
                            refreshReturnPhase = null
                            labState = labState.copy(phase = previous)
                        }
                        LabCommand.FILL -> {
                            labState = labState.copy(
                                phase = LabPhase.FILLING,
                                requestedUnits = null,
                                terminalFetchOutcome = null,
                                error = null,
                            )
                            coroutineScope {
                                // This is UI sampling only: it never admits a
                                // request. Committed bytes become visible
                                // only after authoritative coverage refresh.
                                val sampler = launch {
                                    while (isActive) {
                                        delay(250)
                                        refreshCoverage()
                                    }
                                }
                                try {
                                    val report = active.fillReserveOnce(
                                        requirements = requirements,
                                        playheadUs = PLAYHEAD_US,
                                        targetReserveUs = TARGET_RESERVE_US,
                                        maxFetchUnits = MAX_UNITS,
                                    )
                                    sampler.cancelAndJoin()
                                    refreshCoverage()
                                    check(labState.durableReserveUs == report.finalReserveUs) {
                                        "visible coverage differs from the durable reserve report"
                                    }
                                    labState = labState.copy(
                                        phase = LabPhase.fromStop(report.stop),
                                        requestedUnits = report.requestedFetchKeys.size,
                                        terminalFetchOutcome = report.terminalFetchOutcome,
                                    )
                                } finally {
                                    sampler.cancelAndJoin()
                                }
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (activationId == id && labState.phase != LabPhase.STOPPING) {
                    labState = labState.copy(
                        phase = LabPhase.ERROR,
                        error = error.javaClass.simpleName +
                            (error.message?.let { ": $it" } ?: ""),
                    )
                }
            } finally {
                channel.cancel()
                if (commands === channel) commands = null
                refreshReturnPhase = null
                // NonCancellable retains shutdown ordering after the UI
                // cancels its caller-owned pass. Publish Disabled only after
                // recovery/broker and storage have both closed.
                Log.i("M4ReserveLab", "session cleanup begins phase=${labState.phase}")
                try {
                    var shutdownFailure: Throwable? = null
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            Log.i("M4ReserveLab", "closing recovery and broker")
                            bridge?.shutdown()
                            Log.i("M4ReserveLab", "recovery and broker closed")
                        } catch (error: Throwable) {
                            shutdownFailure = error
                            Log.e("M4ReserveLab", "runtime shutdown failed", error)
                        } finally {
                            try {
                                Log.i("M4ReserveLab", "closing extent store")
                                store?.close()
                                Log.i("M4ReserveLab", "extent store closed")
                            } catch (error: Throwable) {
                                shutdownFailure = shutdownFailure ?: error
                                Log.e("M4ReserveLab", "extent store close failed", error)
                            }
                        }
                    }
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        Log.i("M4ReserveLab", "session cleanup complete phase=${labState.phase}")
                        if (activationId == id && labState.phase == LabPhase.STOPPING) {
                            val error = shutdownFailure
                            labState = if (error == null) {
                                M4ReserveLabState()
                            } else {
                                M4ReserveLabState(
                                    phase = LabPhase.ERROR,
                                    error = "Session shutdown failed: " +
                                        error.javaClass.simpleName,
                                )
                            }
                        }
                    }
                } finally {
                    if (ownsSession) sessionMutex.unlock()
                }
            }
        }
    }

    private fun fillOnce() = submit(LabCommand.FILL)

    private fun refreshOnce() = submit(LabCommand.REFRESH)

    private fun submit(command: LabCommand) {
        val previous = labState.phase
        if (!previous.acceptsCommands) return
        val channel = commands ?: return
        // Main.immediate can resume the receiver inline. Publish busy
        // before sending, and never replace a pending command.
        if (command == LabCommand.REFRESH) refreshReturnPhase = previous
        val busy = if (command == LabCommand.FILL) LabPhase.FILLING else LabPhase.REFRESHING
        labState = labState.copy(phase = busy)
        if (channel.trySend(command).isFailure) {
            refreshReturnPhase = null
            labState = labState.copy(phase = previous)
        }
    }

    private fun disableLab() {
        if (labState.phase == LabPhase.DISABLED ||
            labState.phase == LabPhase.STOPPING
        ) return
        val previousPhase = labState.phase
        Log.i("M4ReserveLab", "disable requested from phase=$previousPhase")
        labState = labState.copy(phase = LabPhase.STOPPING)
        commands?.cancel()
        val job = sessionJob
        if (job != null && !job.isCompleted) {
            // Cancelling the channel discards queued commands and wakes an
            // idle receiver. Cancel active or initializing work directly.
            if (previousPhase == LabPhase.FILLING ||
                previousPhase == LabPhase.REFRESHING ||
                previousPhase == LabPhase.CONNECTING
            ) {
                job.cancel()
            }
        } else {
            commands = null
            labState = M4ReserveLabState()
        }
    }

    override fun onStop() {
        // No hidden reserve job when the lab loses foreground ownership.
        disableLab()
        super.onStop()
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val sessionMutex = Mutex()
        const val ORIGIN_URL = "http://localhost:18080"
        const val SESSION_ID = "m4-a1c-f1-debug"
        const val PLAYHEAD_US = 0L
        const val TARGET_RESERVE_US = 8_000_000L
        const val MAX_UNITS = 4
    }
}

internal enum class LabCommand { FILL, REFRESH }

internal enum class LabPhase(val title: String) {
    DISABLED("Disabled"),
    CONNECTING("Initializing local F1 store"),
    READY("Ready"),
    FILLING("Filling (caller-owned pass)"),
    REFRESHING("Refreshing coverage"),
    TARGET_REACHED("Target reached"),
    PLAN_DEPLETED("Plan depleted"),
    FETCH_FAILED("Fetch failed"),
    DEPENDENCY_NOT_READY("Dependency not ready"),
    ERROR("Error"),
    STOPPING("Stopping"),
    ;

    val acceptsCommands: Boolean
        get() = when (this) {
            READY, TARGET_REACHED, PLAN_DEPLETED, FETCH_FAILED, DEPENDENCY_NOT_READY -> true
            else -> false
        }

    companion object {
        fun fromStop(stop: ReserveExecutionStop): LabPhase = when (stop) {
            ReserveExecutionStop.TARGET_REACHED -> TARGET_REACHED
            ReserveExecutionStop.PLAN_DEPLETED -> PLAN_DEPLETED
            ReserveExecutionStop.FETCH_FAILED -> FETCH_FAILED
            ReserveExecutionStop.DEPENDENCY_NOT_READY -> DEPENDENCY_NOT_READY
        }
    }
}

internal data class M4ReserveLabState(
    val phase: LabPhase = LabPhase.DISABLED,
    /** Null is not measured; zero is a measured, empty playable intersection. */
    val durableReserveUs: Long? = null,
    val targetReserveUs: Long = 8_000_000L,
    val requestedUnits: Int? = null,
    val terminalFetchOutcome: String? = null,
    val error: String? = null,
) {
    val progress: Float
        get() = ((durableReserveUs ?: 0L).toDouble() /
            targetReserveUs.toDouble()).coerceIn(0.0, 1.0).toFloat()

    val reserveLabel: String
        get() = durableReserveUs?.let {
            "Durable playable reserve: $it µs (${seconds(it)} s)"
        } ?: "Durable playable reserve: not measured"

    val targetLabel: String
        get() = "Target: ${seconds(targetReserveUs)} s"

    companion object {
        fun seconds(micros: Long): String = String.format(
            Locale.US, "%.3f", micros / 1_000_000.0,
        )
    }
}

@Composable
private fun M4ReserveLabScreen(
    state: M4ReserveLabState,
    onEnable: () -> Unit,
    onFill: () -> Unit,
    onRefresh: () -> Unit,
    onDisable: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "SpongeTube · M4 Reserve Lab",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Experimental / debug-only · canonical F1",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "No downloads start until you select Fill reserve once. " +
                        "M0 playback is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (state.phase != LabPhase.DISABLED &&
                    state.phase != LabPhase.STOPPING
                ) {
                    // Always above the scroll-dependent fields so the
                    // foreground owner can reliably stop network work.
                    Button(
                        onClick = onDisable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Disable lab")
                    }
                }

                Text("Status: ${state.phase.title}")
                Text(
                    state.reserveLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.durableReserveUs != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(state.targetLabel)
                Text("Playhead: 0 s · required video + audio")
                Text(
                    "Source: durable CoverageIndex intersection " +
                        "(not Media3 buffer or downloaded bytes).",
                    style = MaterialTheme.typography.bodySmall,
                )

                when (state.phase) {
                    LabPhase.DISABLED ->
                        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable experimental F1 reserve lab")
                        }
                    LabPhase.CONNECTING, LabPhase.STOPPING ->
                        Text("Local session lifecycle in progress")
                    else -> {
                        FilledTonalButton(
                            onClick = onFill,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.phase.acceptsCommands,
                        ) {
                            Text("Fill reserve once")
                        }
                        FilledTonalButton(
                            onClick = onRefresh,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.phase.acceptsCommands,
                        ) {
                            Text("Refresh coverage")
                        }
                    }
                }

                state.requestedUnits?.let {
                    Text("Fetch keys admitted in last pass: $it")
                }
                state.terminalFetchOutcome?.let {
                    Text("Fetch outcome: $it")
                }
                state.error?.let {
                    Text("Error: $it", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Media Lab N0: adb reverse tcp:18080 tcp:18081. " +
                        "This lab does not demonstrate YouTube delivery or real VPN transitions.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
