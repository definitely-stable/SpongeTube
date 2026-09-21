package io.github.definitelystable.spongetube

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.definitelystable.spongetube.playback.baseline.BaselineCacheState
import io.github.definitelystable.spongetube.playback.baseline.BaselineMode
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlayback
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlaybackIdentity
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlaybackSession
import io.github.definitelystable.spongetube.playback.baseline.BaselinePlaybackSpec
import io.github.definitelystable.spongetube.playback.baseline.BaselineTransport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
class MainActivity : ComponentActivity() {

    private val prepareExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spongetube-m0-baseline-prepare").apply {
            isDaemon = true
        }
    }
    private val requestGeneration = AtomicLong(0)

    private var activeSession: BaselinePlaybackSession? = null
    private var labState by mutableStateOf(BaselineLabState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BaselineLabShell(
                state = labState,
                onLoad = ::loadBaseline,
            )
        }
    }

    private fun loadBaseline(
        selection: BaselineSelection,
        transport: BaselineTransport,
    ) {
        val generation = requestGeneration.incrementAndGet()

        activeSession?.close()
        activeSession = null

        val spec = selection.toSpec(transport)
        labState = BaselineLabState(
            phase = "Preparing ${selection.label}",
            requestedTransport = transport,
        )

        prepareExecutor.execute {
            val preparedResult = runCatching {
                BaselinePlayback.prepare(
                    context = applicationContext,
                    spec = spec,
                )
            }

            runOnUiThread {
                if (generation != requestGeneration.get()) {
                    preparedResult.getOrNull()?.close()
                    return@runOnUiThread
                }

                preparedResult.fold(
                    onSuccess = { prepared ->
                        try {
                            val session = BaselinePlayback.createSession(
                                context = this,
                                prepared = prepared,
                            )
                            activeSession = session
                            attachPlayerListener(session, generation)

                            labState = labState.copy(
                                phase = "Preparing Media3",
                                identity = session.identity,
                                player = session.player,
                                cacheBytes = session.cacheBytesNow(),
                                error = null,
                            )

                            session.player.prepare()
                        } catch (throwable: Throwable) {
                            prepared.close()
                            showFailure("Player creation failed", throwable)
                        }
                    },
                    onFailure = { throwable ->
                        showFailure("Baseline preparation failed", throwable)
                    },
                )
            }
        }
    }

    private fun attachPlayerListener(
        session: BaselinePlaybackSession,
        generation: Long,
    ) {
        session.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (generation != requestGeneration.get()) {
                    return
                }

                labState = labState.copy(
                    phase = playbackStateName(playbackState),
                    cacheBytes = session.cacheBytesNow(),
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != requestGeneration.get()) {
                    return
                }

                labState = labState.copy(
                    isPlaying = isPlaying,
                    cacheBytes = session.cacheBytesNow(),
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (generation != requestGeneration.get()) {
                    return
                }

                labState = labState.copy(
                    phase = "Playback error",
                    error = "${error.errorCodeName}: ${error.message ?: "no message"}",
                    cacheBytes = session.cacheBytesNow(),
                )
            }
        })
    }

    private fun showFailure(prefix: String, throwable: Throwable) {
        labState = labState.copy(
            phase = prefix,
            player = null,
            error = throwable.message ?: throwable::class.java.simpleName,
            cacheBytes = BaselinePlayback.standardCacheBytes(),
        )
    }

    override fun onDestroy() {
        requestGeneration.incrementAndGet()
        activeSession?.close()
        activeSession = null
        prepareExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val F1_URI = "http://localhost:18080/fixtures/F1/manifest.mpd"

        fun playbackStateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "Idle"
            Player.STATE_BUFFERING -> "Buffering"
            Player.STATE_READY -> "Ready"
            Player.STATE_ENDED -> "Ended"
            else -> "Unknown($state)"
        }
    }

    private fun BaselineSelection.toSpec(
        transport: BaselineTransport,
    ): BaselinePlaybackSpec {
        return when (this) {
            BaselineSelection.DIRECT -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.DIRECT,
                cacheState = BaselineCacheState.NONE,
                transport = transport,
            )

            BaselineSelection.CACHE_COLD -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.STANDARD_CACHE,
                cacheState = BaselineCacheState.COLD,
                transport = transport,
            )

            BaselineSelection.CACHE_WARM -> BaselinePlaybackSpec(
                mediaUri = F1_URI,
                mode = BaselineMode.STANDARD_CACHE,
                cacheState = BaselineCacheState.WARM,
                transport = transport,
            )
        }
    }
}

private enum class BaselineSelection(val label: String) {
    DIRECT("DIRECT"),
    CACHE_COLD("CACHE COLD"),
    CACHE_WARM("CACHE WARM"),
}

@UnstableApi
private data class BaselineLabState(
    val phase: String = "Idle · load F1 to begin",
    val requestedTransport: BaselineTransport = BaselineTransport.RECOMMENDED_PLATFORM,
    val identity: BaselinePlaybackIdentity? = null,
    val player: Player? = null,
    val isPlaying: Boolean = false,
    val cacheBytes: Long = 0,
    val error: String? = null,
)

@UnstableApi
@Composable
private fun BaselineLabShell(
    state: BaselineLabState,
    onLoad: (BaselineSelection, BaselineTransport) -> Unit,
) {
    var selection by remember { mutableStateOf(BaselineSelection.DIRECT) }
    var transport by remember {
        mutableStateOf(BaselineTransport.RECOMMENDED_PLATFORM)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "SpongeTube · M0-C",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Media3 reference baselines against canonical F1",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = "Playback path",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BaselineSelection.entries.forEach { candidate ->
                        FilledTonalButton(
                            onClick = { selection = candidate },
                            enabled = selection != candidate,
                        ) {
                            Text(candidate.label)
                        }
                    }
                }

                Text(
                    text = "Transport",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            transport = BaselineTransport.RECOMMENDED_PLATFORM
                        },
                        enabled = transport != BaselineTransport.RECOMMENDED_PLATFORM,
                    ) {
                        Text("RECOMMENDED")
                    }
                    FilledTonalButton(
                        onClick = {
                            transport = BaselineTransport.DEFAULT_HTTP
                        },
                        enabled = transport != BaselineTransport.DEFAULT_HTTP,
                    ) {
                        Text("DEFAULT HTTP")
                    }
                }

                Button(
                    onClick = { onLoad(selection, transport) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load canonical F1")
                }

                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = true
                        }
                    },
                    update = { view ->
                        view.player = state.player
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )

                Text(
                    text = "State: ${state.phase}" +
                        if (state.isPlaying) " · playing" else "",
                    style = MaterialTheme.typography.bodyLarge,
                )

                val identity = state.identity
                if (identity != null) {
                    Text(
                        text = "Mode: ${identity.mode} / ${identity.cacheState}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Transport: ${identity.requestedTransport} → " +
                            identity.effectiveTransport,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "Requested transport: ${state.requestedTransport}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = "Standard cache: ${state.cacheBytes} bytes",
                    style = MaterialTheme.typography.bodyMedium,
                )

                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = "PlayerView provides play/pause/resume and seek controls. " +
                        "Media Lab must be reachable through adb reverse at localhost:18080.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
