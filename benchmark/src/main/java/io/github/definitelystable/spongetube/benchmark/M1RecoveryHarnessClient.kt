package io.github.definitelystable.spongetube.benchmark

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal class M1RecoveryHarnessClient(
    private val context: Context,
) {
    private val providerUri = Uri.parse("content://$AUTHORITY")

    fun prepareProcessDeath(sessionId: String): Bundle =
        call(METHOD_PREPARE_PROCESS_DEATH, sessionId)

    fun recoverProcessDeath(
        sessionId: String,
        pidBefore: Int,
        pidAfter: Int,
    ): Bundle =
        call(
            METHOD_RECOVER_PROCESS_DEATH,
            sessionId,
            Bundle().apply {
                putInt(KEY_PID_BEFORE, pidBefore)
                putInt(KEY_PID_AFTER, pidAfter)
            },
        )

    fun startN4R(
        sessionId: String,
        scenario: String,
        originBaseUrl: String,
    ): Bundle =
        call(
            METHOD_START_N4R,
            sessionId,
            Bundle().apply {
                putString(KEY_SCENARIO, scenario)
                putString(KEY_ORIGIN_BASE_URL, originBaseUrl)
            },
        )

    fun status(sessionId: String): Bundle =
        call(METHOD_STATUS, sessionId)

    fun finishN4R(
        sessionId: String,
        observedStallSequence: Long? = null,
        restoreCommandId: String? = null,
    ): Bundle =
        call(
            METHOD_FINISH_N4R,
            sessionId,
            Bundle().apply {
                observedStallSequence?.let {
                    putLong(KEY_OBSERVED_STALL_SEQUENCE, it)
                }
                restoreCommandId?.let {
                    putString(KEY_RESTORE_COMMAND_ID, it)
                }
            },
        )

    fun exportEvidence(
        sessionId: String,
        namespace: String = "m1-f-evidence",
    ) {
        val artifacts = call(METHOD_LIST_ARTIFACTS, sessionId)
            .getStringArrayList(KEY_ARTIFACTS)
            ?: error("target returned no M1-F artifact list")
        check(artifacts.isNotEmpty()) { "M1-F artifact list is empty" }

        val stagingDir = if (Build.VERSION.SDK_INT >= 29) {
            File(
                "/sdcard/Android/media/${context.packageName}" +
                    "/additional_test_output/$namespace/$sessionId",
            )
        } else {
            File(
                checkNotNull(context.getExternalFilesDir(null)),
                "m1-test-output/$namespace/$sessionId",
            )
        }
        stagingDir.deleteRecursively()
        check(stagingDir.mkdirs()) {
            "failed to create M1-F evidence staging directory: $stagingDir"
        }

        artifacts.forEach { relative ->
            val uri = Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(sessionId)
                .apply {
                    relative.split('/').forEach(::appendPath)
                }
                .build()
            val target = File(stagingDir, relative)
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { destination ->
                    input.copyTo(destination)
                }
            } ?: error("provider returned no stream for $relative")
            check(target.isFile && target.length() > 0L) {
                "empty M1-F evidence artifact: $relative"
            }
        }
    }

    private fun call(
        method: String,
        sessionId: String,
        extras: Bundle? = null,
    ): Bundle =
        checkNotNull(
            context.contentResolver.call(
                providerUri,
                method,
                sessionId,
                extras,
            ),
        ) {
            "M1 recovery provider returned no result for $method"
        }

    companion object {
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
    }
}

internal class M1MediaLabGateClient(
    controlBaseUrl: String,
) {
    private val base = controlBaseUrl.trimEnd('/')

    fun close(commandId: String): GateState =
        command("close", commandId)

    fun open(commandId: String): GateState =
        command("open", commandId)

    fun state(): GateState =
        request("GET", "/__lab/gate/media", null)

    private fun command(
        action: String,
        commandId: String,
    ): GateState =
        request(
            "POST",
            "/__lab/gate/media/$action",
            commandId,
        )

    private fun request(
        method: String,
        path: String,
        commandId: String?,
    ): GateState {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            commandId?.let {
                connection.setRequestProperty(
                    "X-Sponge-Gate-Command",
                    it,
                )
            }
            val status = connection.responseCode
            check(status == 200) {
                "Media Lab gate $method $path returned $status"
            }
            val payload = connection.inputStream
                .bufferedReader()
                .use { it.readText() }
            val json = JSONObject(payload)
            GateState(
                state = json.getString("state"),
                generation = json.getLong("generation"),
                activeBlockedRequests =
                    json.getInt("activeBlockedRequests"),
            )
        } finally {
            connection.disconnect()
        }
    }

    data class GateState(
        val state: String,
        val generation: Long,
        val activeBlockedRequests: Int,
    )
}
