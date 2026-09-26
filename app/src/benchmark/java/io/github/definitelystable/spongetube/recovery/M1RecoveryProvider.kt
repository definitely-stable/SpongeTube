package io.github.definitelystable.spongetube.recovery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.definitelystable.spongetube.core.engine.CoverageEvidenceSnapshot
import io.github.definitelystable.spongetube.core.engine.CoverageIndex
import io.github.definitelystable.spongetube.core.engine.FixtureTransportSession
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeConfig
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeRuntime
import io.github.definitelystable.spongetube.core.engine.PlaybackRequirementSet
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import io.github.definitelystable.spongetube.core.storage.ExtentLifecycleEvent
import io.github.definitelystable.spongetube.core.storage.ExtentLifecycleListener
import io.github.definitelystable.spongetube.core.storage.ExtentLifecycleState
import io.github.definitelystable.spongetube.core.storage.ExtentStore
import io.github.definitelystable.spongetube.playback.bridge.SpongeLoadErrorHandlingPolicy
import io.github.definitelystable.spongetube.playback.bridge.SpongePlayback
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FetchUnit
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1_MANIFEST_KEY
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1_PLAYBACK_AUTHORITY
import io.github.definitelystable.spongetube.testsupport.playback.f1.F1PlaybackPlanFactory
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Benchmark-only M1-F control/evidence surface.
 *
 * This provider is compiled only into the benchmark target APK. It is guarded
 * by a signature permission and never ships in debug/release product builds.
 */
@OptIn(SpongeBridgeApi::class)
class M1RecoveryProvider : ContentProvider() {

    private var processDeathStore: ExtentStore? = null
    private var activeSession: N4RSession? = null

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val context = requireNotNull(context)
        return when (method) {
            METHOD_PREPARE_PROCESS_DEATH ->
                prepareProcessDeath(context.filesDir, requireSession(arg))
            METHOD_RECOVER_PROCESS_DEATH ->
                recoverProcessDeath(
                    context.filesDir,
                    requireSession(arg),
                    checkNotNull(extras).getInt(KEY_PID_BEFORE),
                    extras.getInt(KEY_PID_AFTER),
                )
            METHOD_START_N4R ->
                startN4R(context.filesDir, requireSession(arg), checkNotNull(extras))
            METHOD_STATUS ->
                checkNotNull(activeSession) { "no active N4R session" }.status()
            METHOD_FINISH_N4R ->
                finishN4R(requireSession(arg), checkNotNull(extras))
            METHOD_LIST_ARTIFACTS ->
                listArtifacts(context.filesDir, requireSession(arg))
            else -> super.call(method, arg, extras) ?: Bundle.EMPTY
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("M1 recovery evidence is read-only")
        }
        val appContext = context
            ?: throw FileNotFoundException("provider context unavailable")
        val segments = uri.pathSegments
        if (segments.size < 2) {
            throw FileNotFoundException("expected /<session>/<artifact>")
        }
        val sessionId = segments.first()
        if (!SESSION_ID.matches(sessionId)) {
            throw FileNotFoundException("invalid session id")
        }
        val sessionRoot = sessionDir(appContext.filesDir, sessionId).canonicalFile
        if (!File(sessionRoot, COMPLETE_MARKER).isFile) {
            throw FileNotFoundException("recovery evidence is not complete")
        }
        val relative = segments.drop(1).joinToString("/")
        if (relative.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw FileNotFoundException("invalid artifact path")
        }
        val target = File(sessionRoot, relative).canonicalFile
        val prefix = sessionRoot.path + File.separator
        if (!target.path.startsWith(prefix) || !target.isFile) {
            throw FileNotFoundException("artifact not found")
        }
        return ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    private fun prepareProcessDeath(
        filesDir: File,
        sessionId: String,
    ): Bundle {
        check(processDeathStore == null) { "process-death store already active" }
        check(activeSession == null) { "N4R session already active" }
        val session = resetSession(filesDir, sessionId)
        File(filesDir, "sponge").deleteRecursively()

        val fixture = F1FixtureAssets(requireNotNull(context).assets)
        val plans = F1PlaybackPlanFactory(fixture)
        val seed = plans.seed(PROCESS_DEATH_SEED_US)
        val store = runBlocking { ExtentStore.open(requireNotNull(context)) }
        processDeathStore = store
        runBlocking {
            seed.forEach { unit ->
                store.writeExtent(unit.toExtentSpec()) {
                    write(fixture.verifiedBytes(unit.resourcePath))
                }
            }
        }

        val coverage = runBlocking {
            val index = CoverageIndex(store)
            index.refresh()
            index.snapshot(REQUIREMENTS, 0)
        }
        writeJson(
            File(session, COVERAGE_BEFORE),
            coverageArtifact(sessionId, coverage, 0),
        )
        writeJson(
            File(session, PROCESS_STATE),
            linkedMapOf(
                "schemaVersion" to 1,
                "sessionId" to sessionId,
                "pidPrepared" to Process.myPid(),
                "seedTargetUs" to PROCESS_DEATH_SEED_US,
                "prePublishedFetchKeys" to JSONArray(
                    seed.map(F1FetchUnit::fetchKeyValue),
                ),
            ),
        )
        return Bundle().apply {
            putInt(KEY_TARGET_PID, Process.myPid())
            putLong(KEY_INITIAL_RESERVE_US, coverage.durableReserveUs)
        }
    }

    private fun recoverProcessDeath(
        filesDir: File,
        sessionId: String,
        pidBefore: Int,
        pidAfter: Int,
    ): Bundle {
        check(processDeathStore == null) {
            "recover must execute in a fresh target process"
        }
        check(pidBefore != pidAfter) {
            "process-death recovery requires a changed PID"
        }
        check(pidAfter == Process.myPid()) {
            "controller PID-after does not match recovery process"
        }
        val session = sessionDir(filesDir, sessionId)
        val state = JSONObject(File(session, PROCESS_STATE).readText())
        val store = runBlocking { ExtentStore.open(requireNotNull(context)) }
        val report = store.initialRecoveryReport
        val coverage = runBlocking {
            val index = CoverageIndex(store)
            index.refresh()
            index.snapshot(REQUIREMENTS, 0)
        }

        writeJson(
            File(session, COVERAGE_AFTER),
            coverageArtifact(sessionId, coverage, 0),
        )
        writeJson(
            File(session, RECOVERY_REPORT),
            linkedMapOf(
                "schemaVersion" to 1,
                "deletedPartFiles" to report.deletedPartFiles,
                "deletedOrphanFiles" to report.deletedOrphanFiles,
                "deletedQuarantinedFiles" to report.deletedQuarantinedFiles,
                "quarantinedExtents" to report.quarantinedExtents,
                "verifiedPublishedExtents" to report.verifiedPublishedExtents,
            ),
        )

        store.close()
        copyStorageRoot(filesDir, session)

        val prePublished = state.getJSONArray("prePublishedFetchKeys")
        writeJson(
            File(session, CASE_JSON),
            linkedMapOf(
                "schemaVersion" to 1,
                "runId" to sessionId,
                "sessionId" to sessionId,
                "scenarioId" to "PROCESS_DEATH",
                "maxAttemptsPerOwner" to MAX_ATTEMPTS_PER_OWNER,
                "media3MaxRetries" to MEDIA3_MAX_RETRIES,
                "recoveryPolicyId" to RECOVERY_POLICY_ID,
                "recoveryRemoteAttemptLimit" to RECOVERY_REMOTE_ATTEMPT_LIMIT,
                "prePublishedFetchKeys" to prePublished,
                "pidBefore" to pidBefore,
                "pidAfter" to pidAfter,
                "playheadUsAfter" to 0,
            ),
        )
        File(session, COMPLETE_MARKER).writeText("complete\n")
        return Bundle().apply {
            putInt(KEY_TARGET_PID, Process.myPid())
            putLong(KEY_FINAL_PLAYHEAD_US, 0)
        }
    }

    @UnstableApi
    private fun startN4R(
        filesDir: File,
        sessionId: String,
        extras: Bundle,
    ): Bundle {
        check(processDeathStore == null) { "process-death store is active" }
        check(activeSession == null) { "N4R session already active" }
        val scenario = checkNotNull(extras.getString(KEY_SCENARIO))
        require(scenario in N4R_SCENARIOS) { "unsupported N4R scenario: $scenario" }
        val originBaseUrl = checkNotNull(extras.getString(KEY_ORIGIN_BASE_URL))
        val sessionDir = resetSession(filesDir, sessionId)
        File(filesDir, "sponge").deleteRecursively()

        val seedUs = if (scenario == "N4R-SHORT") {
            SHORT_SEED_US
        } else {
            EXHAUST_SEED_US
        }
        val session = N4RSession(
            sessionId = sessionId,
            scenarioId = scenario,
            sessionDir = sessionDir,
            filesDir = filesDir,
            originBaseUrl = originBaseUrl,
            seedTargetUs = seedUs,
        )
        activeSession = session
        session.start()
        return session.status()
    }

    private fun finishN4R(
        sessionId: String,
        extras: Bundle,
    ): Bundle {
        val session = checkNotNull(activeSession) { "no active N4R session" }
        check(session.sessionId == sessionId)
        return try {
            session.finish(
                observedStallEventSequence =
                    extras.getLong(KEY_OBSERVED_STALL_SEQUENCE, -1L)
                        .takeIf { it >= 0 },
                restoreCommandId = extras.getString(KEY_RESTORE_COMMAND_ID),
            )
        } finally {
            activeSession = null
        }
    }

    private fun listArtifacts(
        filesDir: File,
        sessionId: String,
    ): Bundle {
        val root = sessionDir(filesDir, sessionId)
        check(File(root, COMPLETE_MARKER).isFile) {
            "recovery evidence is not complete"
        }
        val artifacts = root.walkTopDown()
            .filter(File::isFile)
            .filterNot { it.name == COMPLETE_MARKER }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toCollection(ArrayList())
        return Bundle().apply {
            putStringArrayList(KEY_ARTIFACTS, artifacts)
        }
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    @UnstableApi
    @OptIn(SpongeBridgeApi::class)
    private inner class N4RSession(
        val sessionId: String,
        private val scenarioId: String,
        private val sessionDir: File,
        private val filesDir: File,
        private val originBaseUrl: String,
        private val seedTargetUs: Long,
    ) {
        private val fixture = F1FixtureAssets(requireNotNull(context).assets)
        private val plans = F1PlaybackPlanFactory(fixture)
        private val bridgeEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        private val fetchEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        private val extentEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        private val timeline = CopyOnWriteArrayList<Map<String, Any?>>()
        private val errors = CopyOnWriteArrayList<String>()
        private val eventCounter = AtomicLong()
        private val extentEventCounter = AtomicLong()
        private val playerInstanceId = "$sessionId-player-1"
        private val policy = SpongeLoadErrorHandlingPolicy()
        private val unitByExtentId =
            plans.catalog.units.associateBy(F1FetchUnit::extentId)
        private val store: ExtentStore = runBlocking {
            ExtentStore.open(
                requireNotNull(context),
                lifecycleListener = ExtentLifecycleListener(::recordExtentEvent),
            )
        }
        private lateinit var runtime: PlaybackBridgeRuntime
        private lateinit var player: ExoPlayer
        private var hasPlayed = false
        private var readyRecorded = false
        private var inStall = false
        private var lastProgressUs = -1L
        private var lastStallSequence: Long? = null
        private var initialReserveUs = 0L
        private lateinit var seed: List<F1FetchUnit>

        fun start() {
            seed = plans.seed(seedTargetUs)
            runBlocking {
                seed.forEach { unit ->
                    store.writeExtent(unit.toExtentSpec()) {
                        write(fixture.verifiedBytes(unit.resourcePath))
                    }
                }
            }
            runtime = runBlocking {
                PlaybackBridgeRuntime.open(
                    store = store,
                    plan = plans.plan(),
                    transport = FixtureTransportSession(originBaseUrl),
                    config = PlaybackBridgeConfig(sessionId = sessionId),
                    bridgeEventListener = {
                        bridgeEvents += it.toArtifactMap()
                    },
                    fetchEventListener = {
                        fetchEvents += LinkedHashMap(it)
                    },
                )
            }
            val before = runtime.coverageIndex.snapshot(REQUIREMENTS, 0)
            initialReserveUs = before.durableReserveUs
            writeJson(
                File(sessionDir, COVERAGE_BEFORE),
                coverageArtifact(sessionId, before, 0),
            )
            record("SCENARIO_STARTED", null)

            onMain {
                player = SpongePlayback.createPlayer(
                    requireNotNull(context),
                    runtime,
                    F1_PLAYBACK_AUTHORITY,
                    policy,
                )
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            samplePlayer()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            samplePlayer()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            errors += error.errorCodeName + ": " + error.message
                            samplePlayer()
                        }
                    },
                )
                player.setMediaItem(
                    SpongePlayback.mediaItem(
                        F1_PLAYBACK_AUTHORITY,
                        F1_MANIFEST_KEY,
                    ),
                )
                record("RESERVE_SNAPSHOT", player)
                player.prepare()
                player.play()
                samplePlayer()
            }
        }

        fun status(): Bundle {
            if (this::player.isInitialized) {
                onMain { samplePlayer() }
            }
            return Bundle().apply {
                putBoolean(KEY_EVER_PLAYED, hasPlayed)
                putBoolean(KEY_STALL_ACTIVE, inStall)
                putLong(
                    KEY_LAST_STALL_SEQUENCE,
                    lastStallSequence ?: -1L,
                )
                putLong(
                    KEY_POSITION_US,
                    if (this@N4RSession::player.isInitialized) {
                        onMain { player.currentPosition * 1_000L }
                    } else {
                        0L
                    },
                )
                putLong(KEY_INITIAL_RESERVE_US, initialReserveUs)
                putStringArrayList(KEY_PLAYER_ERRORS, ArrayList(errors))
            }
        }

        fun finish(
            observedStallEventSequence: Long?,
            restoreCommandId: String?,
        ): Bundle {
            val finalPlayheadUs = onMain {
                samplePlayer()
                player.currentPosition.coerceAtLeast(0) * 1_000L
            }
            onMain {
                player.release()
            }
            runBlocking {
                runtime.shutdown()
                runtime.coverageIndex.refresh()
            }
            val after = runtime.coverageIndex.snapshot(
                REQUIREMENTS,
                finalPlayheadUs,
            )
            writeJson(
                File(sessionDir, COVERAGE_AFTER),
                coverageArtifact(
                    sessionId,
                    after,
                    finalPlayheadUs,
                ),
            )
            record("SCENARIO_FINISHED", null)

            store.close()
            writeJsonl(
                File(sessionDir, TIMELINE_JSONL),
                orderedByEventSequence(timeline),
            )
            writeJsonl(
                File(sessionDir, FETCH_JSONL),
                orderedByEventSequence(fetchEvents),
            )
            writeJsonl(
                File(sessionDir, EXTENT_JSONL),
                orderedByEventSequence(extentEvents),
            )
            writeJsonl(
                File(sessionDir, BRIDGE_JSONL),
                orderedByEventSequence(bridgeEvents),
            )
            writeJson(
                File(sessionDir, CASE_JSON),
                linkedMapOf(
                    "schemaVersion" to 1,
                    "runId" to sessionId,
                    "sessionId" to sessionId,
                    "scenarioId" to scenarioId,
                    "maxAttemptsPerOwner" to MAX_ATTEMPTS_PER_OWNER,
                    "media3MaxRetries" to MEDIA3_MAX_RETRIES,
                    "recoveryPolicyId" to RECOVERY_POLICY_ID,
                    "recoveryRemoteAttemptLimit" to RECOVERY_REMOTE_ATTEMPT_LIMIT,
                    "initialDurableReserveUs" to initialReserveUs,
                    "prePublishedFetchKeys" to JSONArray(
                        seed.map(F1FetchUnit::fetchKeyValue),
                    ),
                    "observedStallEventSequence" to
                        observedStallEventSequence,
                    "restoreCommandId" to restoreCommandId,
                    "playheadUsAfter" to finalPlayheadUs,
                    "playerErrors" to JSONArray(errors),
                ),
            )
            copyStorageRoot(filesDir, sessionDir)
            File(sessionDir, COMPLETE_MARKER).writeText("complete\n")
            return Bundle().apply {
                putLong(KEY_FINAL_PLAYHEAD_US, finalPlayheadUs)
                putLong(
                    KEY_LAST_STALL_SEQUENCE,
                    lastStallSequence ?: -1L,
                )
            }
        }

        private fun orderedByEventSequence(
            rows: List<Map<String, Any?>>,
        ): List<Map<String, Any?>> =
            rows.sortedBy { row ->
                (row["eventSequence"] as Number).toLong()
            }

        private fun recordExtentEvent(event: ExtentLifecycleEvent) {
            val unit = unitByExtentId[event.extentId.value]
                ?: error("unknown F1 extent lifecycle identity: ${event.extentId}")
            val spec = unit.toExtentSpec()
            extentEvents += linkedMapOf(
                "schemaVersion" to 1,
                "eventSequence" to extentEventCounter.getAndIncrement(),
                "eventElapsedRealtimeNs" to
                    SystemClock.elapsedRealtimeNanos(),
                "sessionId" to sessionId,
                "extentId" to event.extentId.value,
                "state" to event.state.name,
                "integrityState" to
                    if (
                        event.state == ExtentLifecycleState.VERIFIED ||
                        event.state == ExtentLifecycleState.DURABLE ||
                        event.state == ExtentLifecycleState.PUBLISHED
                    ) {
                        "VALID"
                    } else {
                        "UNKNOWN"
                    },
                "trackId" to spec.trackId,
                "representationId" to spec.representationId,
                "mediaStartUs" to spec.mediaStartUs,
                "mediaEndUs" to spec.mediaEndUs,
                "dependencyExtentIds" to JSONArray(
                    spec.dependencyExtentIds.map { it.value },
                ),
                "byteStart" to spec.byteStart,
                "byteEndExclusive" to spec.byteEndExclusive,
                "expectedLength" to spec.expectedLength,
                "actualLength" to event.actualLength,
                "sha256" to event.sha256?.hex,
            )
        }

        private fun samplePlayer() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!this::player.isInitialized) {
                return
            }
            val state = player.playbackState
            val isPlaying = player.isPlaying

            if (state == Player.STATE_READY && !readyRecorded) {
                readyRecorded = true
                record("PLAYER_READY", player)
            }
            if (isPlaying && !hasPlayed) {
                hasPlayed = true
                record("PLAYER_PLAYING", player)
            }
            if (
                hasPlayed &&
                player.playWhenReady &&
                state == Player.STATE_BUFFERING &&
                !inStall
            ) {
                inStall = true
                lastStallSequence = record("PLAYER_STALL_STARTED", player)
            } else if (inStall && state == Player.STATE_READY) {
                inStall = false
                record("PLAYER_STALL_ENDED", player)
            }

            val positionUs = player.currentPosition.coerceAtLeast(0) * 1_000L
            if (
                isPlaying &&
                (
                    lastProgressUs < 0 ||
                        positionUs - lastProgressUs >= PROGRESS_SAMPLE_US
                    )
            ) {
                lastProgressUs = positionUs
                record("PLAYBACK_PROGRESS", player)
            }
        }

        private fun record(
            event: String,
            player: ExoPlayer?,
        ): Long {
            val sequence = eventCounter.getAndIncrement()
            val positionUs = player?.currentPosition
                ?.coerceAtLeast(0)
                ?.times(1_000L)
            val bufferedAheadUs = player?.let {
                ((it.bufferedPosition - it.currentPosition)
                    .coerceAtLeast(0L)) * 1_000L
            }
            val reserveUs = positionUs?.let {
                runtime.coverageIndex.snapshot(REQUIREMENTS, it)
                    .durableReserveUs
            }
            timeline += linkedMapOf(
                "schemaVersion" to 1,
                "eventSequence" to sequence,
                "eventElapsedRealtimeNs" to
                    SystemClock.elapsedRealtimeNanos(),
                "sessionId" to sessionId,
                "playerInstanceId" to
                    if (player == null) null else playerInstanceId,
                "event" to event,
                "playerPositionUs" to positionUs,
                "playerBufferedAheadUs" to bufferedAheadUs,
                "durableReserveUs" to reserveUs,
                "playbackState" to player?.playbackState,
                "playWhenReady" to player?.playWhenReady,
                "isPlaying" to player?.isPlaying,
            )
            return sequence
        }
    }

    private fun resetSession(
        filesDir: File,
        sessionId: String,
    ): File {
        val root = sessionDir(filesDir, sessionId)
        root.deleteRecursively()
        check(root.mkdirs()) { "failed to create M1 recovery session directory" }
        return root
    }

    /**
     * Export only authority inputs consumed by the independent M1 oracle.
     *
     * The live store also contains coordination/SQLite runtime files such as
     * .store.lock and a TRUNCATE journal. They are deliberately excluded:
     * they are not durable extent authority, may validly be zero-length, and
     * must not become part of the canonical evidence contract.
     */
    private fun copyStorageRoot(
        filesDir: File,
        sessionDir: File,
    ) {
        val source = File(filesDir, "sponge")
        val target = File(sessionDir, "storage")
        target.deleteRecursively()
        check(target.mkdirs()) {
            "failed to create canonical storage snapshot root"
        }

        val database = File(source, "metadata/extents.db")
        check(database.isFile) {
            "canonical storage snapshot is missing metadata/extents.db"
        }
        copyEvidenceFile(
            source = database,
            target = File(target, "metadata/extents.db"),
        )

        val extents = File(source, "extents")
        if (extents.isDirectory) {
            extents.walkTopDown()
                .filter(File::isFile)
                .forEach { file ->
                    val relative = file.relativeTo(source)
                    copyEvidenceFile(
                        source = file,
                        target = File(target, relative.path),
                    )
                }
        }
    }

    private fun copyEvidenceFile(
        source: File,
        target: File,
    ) {
        target.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "failed to create evidence directory: $parent"
            }
        }
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        check(target.isFile && target.length() > 0L) {
            "canonical evidence file is empty: ${target.path}"
        }
    }

    private fun coverageArtifact(
        sessionId: String,
        coverage: io.github.definitelystable.spongetube.core.engine.CoverageSnapshot,
        playerBufferedAheadUs: Long?,
    ): Map<String, Any?> =
        CoverageEvidenceSnapshot(
            eventSequence = 0,
            eventElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
            sessionId = sessionId,
            coverage = coverage,
            playerBufferedAheadUs = playerBufferedAheadUs,
        ).toArtifactMap()

    private fun writeJson(
        file: File,
        value: Map<String, Any?>,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject(value).toString(2) + "\n")
    }

    private fun writeJsonl(
        file: File,
        rows: List<Map<String, Any?>>,
    ) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            rows.forEach { row ->
                writer.write(JSONObject(row).toString())
                writer.newLine()
            }
        }
    }

    private fun requireSession(sessionId: String?): String {
        val value = sessionId ?: error("session id required")
        require(SESSION_ID.matches(value)) { "invalid session id" }
        return value
    }

    private fun sessionDir(filesDir: File, sessionId: String): File =
        File(File(filesDir, "m1-recovery"), sessionId)

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result = runCatching(block)
            latch.countDown()
        }
        check(latch.await(MAIN_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "timed out waiting for main thread"
        }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
        val N4R_SCENARIOS = setOf(
            "N4R-SHORT",
            "N4R-EXHAUST",
            "N4R-RESTORE",
            "N4R-FLAP",
        )
        val REQUIREMENTS = PlaybackRequirementSet(
            F1FixtureCatalog.ASSET,
            F1FixtureCatalog.REQUIRED_REPRESENTATIONS,
        )

        const val AUTHORITY =
            "io.github.definitelystable.spongetube.m1.recovery"
        const val METHOD_PREPARE_PROCESS_DEATH = "prepareProcessDeath"
        const val METHOD_RECOVER_PROCESS_DEATH = "recoverProcessDeath"
        const val METHOD_START_N4R = "startN4R"
        const val METHOD_STATUS = "status"
        const val METHOD_FINISH_N4R = "finishN4R"
        const val METHOD_LIST_ARTIFACTS = "listArtifacts"

        const val KEY_SCENARIO = "scenario"
        const val KEY_ORIGIN_BASE_URL = "originBaseUrl"
        const val KEY_PID_BEFORE = "pidBefore"
        const val KEY_PID_AFTER = "pidAfter"
        const val KEY_TARGET_PID = "targetPid"
        const val KEY_INITIAL_RESERVE_US = "initialReserveUs"
        const val KEY_FINAL_PLAYHEAD_US = "finalPlayheadUs"
        const val KEY_EVER_PLAYED = "everPlayed"
        const val KEY_STALL_ACTIVE = "stallActive"
        const val KEY_LAST_STALL_SEQUENCE = "lastStallSequence"
        const val KEY_POSITION_US = "positionUs"
        const val KEY_PLAYER_ERRORS = "playerErrors"
        const val KEY_OBSERVED_STALL_SEQUENCE = "observedStallEventSequence"
        const val KEY_RESTORE_COMMAND_ID = "restoreCommandId"
        const val KEY_ARTIFACTS = "artifacts"

        const val COMPLETE_MARKER = "evidence-complete.marker"
        const val PROCESS_STATE = "process-state.json"
        const val CASE_JSON = "case.json"
        const val COVERAGE_BEFORE = "coverage-before-v2.json"
        const val COVERAGE_AFTER = "coverage-after-v2.json"
        const val RECOVERY_REPORT = "recovery-report.json"
        const val TIMELINE_JSONL = "recovery-timeline-v1.jsonl"
        const val FETCH_JSONL = "fetch-events-v3.jsonl"
        const val EXTENT_JSONL = "extent-events-v1.jsonl"
        const val BRIDGE_JSONL = "bridge-events-v1.jsonl"

        const val PROCESS_DEATH_SEED_US = 30_000_000L
        const val SHORT_SEED_US = 30_000_000L
        const val EXHAUST_SEED_US = 10_000_000L
        // M2-D (ADR-0003): one FetchBroker owner is one physical attempt and
        // Media3 never retries; the RecoveryChain (`sponge-recovery-v2`,
        // REMOTE_ATTEMPT = 4) is the only bound on owners per work item.
        // Recorded so the M1 verifier checks the M2 bound, not M1's.
        const val MAX_ATTEMPTS_PER_OWNER = 1
        const val RECOVERY_POLICY_ID = "sponge-recovery-v2"
        const val RECOVERY_REMOTE_ATTEMPT_LIMIT = 4
        const val MEDIA3_MAX_RETRIES = 0
        const val PROGRESS_SAMPLE_US = 250_000L
        const val MAIN_CALL_TIMEOUT_SECONDS = 30L
    }
}
