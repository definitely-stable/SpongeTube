package io.github.definitelystable.spongetube.playback.bridge

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.definitelystable.spongetube.core.engine.CoverageEvidenceSnapshot
import io.github.definitelystable.spongetube.core.engine.FixtureTransportSession
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeConfig
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeEvent
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeEventKind
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeRuntime
import io.github.definitelystable.spongetube.core.engine.PlaybackPlan
import io.github.definitelystable.spongetube.core.engine.PlaybackRequirementSet
import io.github.definitelystable.spongetube.core.engine.PlaybackTransportGate
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FetchUnit
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal const val AUTHORITY =
    io.github.definitelystable.spongetube.testsupport.playback.f1.F1_PLAYBACK_AUTHORITY
internal const val MANIFEST_KEY =
    io.github.definitelystable.spongetube.testsupport.playback.f1.F1_MANIFEST_KEY

/** One instrumented M1-E case: real ExtentStore, real broker, real ExoPlayer. */
@UnstableApi
@OptIn(SpongeBridgeApi::class)
internal class BridgeScenario(
    private val context: Context,
    private val fixture: F1FixtureAssets,
    val caseId: String,
    seeded: List<F1FetchUnit>,
    plan: PlaybackPlan,
    originBaseUrl: String,
    gate: PlaybackTransportGate? = null,
) {
    val sessionId = "m1-e-$caseId"
    val bridgeEvents = CopyOnWriteArrayList<PlaybackBridgeEvent>()
    val fetchEvents = CopyOnWriteArrayList<Map<String, Any?>>()
    val playerErrors = CopyOnWriteArrayList<String>()
    val seededExtentIds: List<String> = seeded.map(F1FetchUnit::extentId)
    val extra = linkedMapOf<String, Any?>()
    val policy = SpongeLoadErrorHandlingPolicy()
    private val config = PlaybackBridgeConfig(sessionId = sessionId, transportGate = gate)
    private val store: ExtentStore = runBlocking { ExtentStore.open(context) }
    val runtime: PlaybackBridgeRuntime = try {
        runBlocking {
            for (unit in seeded) {
                val bytes = fixture.verifiedBytes(unit.resourcePath)
                store.writeExtent(unit.toExtentSpec()) { write(bytes) }
            }
            PlaybackBridgeRuntime.open(
                store = store,
                plan = plan,
                transport = FixtureTransportSession(originBaseUrl),
                config = config,
                bridgeEventListener = { bridgeEvents += it },
                fetchEventListener = { fetchEvents += it },
            )
        }
    } catch (error: Throwable) {
        store.close()
        throw error
    }

    lateinit var player: ExoPlayer
        private set
    private var playerReleased = false

    fun startPlayer() {
        onMain {
            player = SpongePlayback.createPlayer(context, runtime, AUTHORITY, policy)
            player.addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playerErrors += error.errorCodeName + ": " + error.message
                    }
                },
            )
            player.setMediaItem(SpongePlayback.mediaItem(AUTHORITY, MANIFEST_KEY))
            player.prepare()
            player.play()
        }
    }

    fun seekTo(positionMs: Long) = onMain { player.seekTo(positionMs) }

    fun resume() = onMain { player.play() }

    fun awaitPosition(positionMs: Long, timeoutMs: Long = 90_000) {
        awaitCondition(timeoutMs, "$caseId playing at >= ${positionMs}ms") {
            checkNoPlayerError()
            onMain {
                player.playbackState == Player.STATE_READY &&
                    player.currentPosition >= positionMs
            }
        }
    }

    /** Pause and wait until Media3 stops loading so no fetch is in flight. */
    fun quiesce(stableMs: Long = 1_500, timeoutMs: Long = 180_000) {
        onMain { player.pause() }
        var idleSince = -1L
        awaitCondition(timeoutMs, "$caseId loading quiescence") {
            checkNoPlayerError()
            val loading = onMain { player.isLoading }
            val now = SystemClock.elapsedRealtime()
            if (loading) {
                idleSince = -1L
                false
            } else {
                if (idleSince < 0) {
                    idleSince = now
                }
                now - idleSince >= stableMs
            }
        }
    }

    fun marker(name: String) = runtime.emitHarnessMarker(name)

    /** Measures ExoPlayer.release() on the application thread. */
    fun releasePlayer(): Long {
        val started = SystemClock.elapsedRealtime()
        onMain { player.release() }
        playerReleased = true
        return SystemClock.elapsedRealtime() - started
    }

    fun events(kind: PlaybackBridgeEventKind): List<PlaybackBridgeEvent> =
        bridgeEvents.filter { it.event == kind }

    fun fetchRows(event: String): List<Map<String, Any?>> =
        fetchEvents.filter { it["event"] == event }

    /**
     * Close order (plan F9): player.release -> bridge runtime -> broker ->
     * coverage snapshot -> ExtentStore.close; then export evidence.
     */
    fun finish() {
        if (!playerReleased && this::player.isInitialized) {
            releasePlayer()
        }
        runBlocking { runtime.shutdown() }
        val coverage = runBlocking {
            runtime.coverageIndex.refresh()
            runtime.coverageIndex.snapshot(
                PlaybackRequirementSet(
                    F1FixtureCatalog.ASSET,
                    F1FixtureCatalog.REQUIRED_REPRESENTATIONS,
                ),
                playheadUs = 0,
            )
        }
        store.close()
        writeEvidence(
            CoverageEvidenceSnapshot(
                eventSequence = 0,
                eventElapsedRealtimeNs = 0,
                sessionId = sessionId,
                coverage = coverage,
            ).toArtifactMap(),
        )
    }

    fun abort() {
        runCatching {
            if (!playerReleased && this::player.isInitialized) {
                releasePlayer()
            }
        }
        runCatching { runBlocking { runtime.shutdown() } }
        runCatching { store.close() }
    }

    private fun checkNoPlayerError() {
        check(playerErrors.isEmpty()) { "$caseId player error: $playerErrors" }
    }

    private fun writeEvidence(runtimeCoverage: Map<String, Any?>) {
        val output = PlatformTestStorageRegistry.getInstance()
        val prefix = "m1-e-evidence/$caseId"
        output.openOutputFile("$prefix/bridge-events-v1.jsonl").bufferedWriter().use { writer ->
            bridgeEvents.sortedBy(PlaybackBridgeEvent::eventSequence).forEach { event ->
                writer.write(JSONObject(event.toArtifactMap()).toString())
                writer.newLine()
            }
        }
        output.openOutputFile("$prefix/fetch-events-v3.jsonl").bufferedWriter().use { writer ->
            fetchEvents.sortedBy { (it["eventSequence"] as Number).toLong() }.forEach { row ->
                writer.write(JSONObject(row).toString())
                writer.newLine()
            }
        }
        output.openOutputFile("$prefix/runtime-coverage.json").bufferedWriter().use { writer ->
            writer.write(JSONObject(runtimeCoverage).toString(2))
            writer.newLine()
        }
        val case = JSONObject(
            linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "caseId" to caseId,
                "sessionId" to sessionId,
                "seededExtentIds" to JSONArray(seededExtentIds),
                "bridgeConfig" to JSONObject(config.toArtifactMap()),
                "loadErrorPolicy" to JSONObject(policy.toArtifactMap()),
                "playerErrors" to JSONArray(playerErrors.toList()),
            ) + extra,
        )
        output.openOutputFile("$prefix/case.json").bufferedWriter().use { writer ->
            writer.write(case.toString(2))
            writer.newLine()
        }

        val storeRoot = File(context.filesDir, "sponge")
        storeRoot.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(storeRoot).invariantSeparatorsPath
            output.openOutputFile("$prefix/storage/$relative").use { destination ->
                source.inputStream().use { input -> input.copyTo(destination) }
            }
        }
    }
}

internal fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        result = runCatching(block)
    }
    return checkNotNull(result).getOrThrow()
}

internal fun awaitCondition(
    timeoutMs: Long,
    description: String,
    condition: () -> Boolean,
) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $description" }
        Thread.sleep(20)
    }
}
