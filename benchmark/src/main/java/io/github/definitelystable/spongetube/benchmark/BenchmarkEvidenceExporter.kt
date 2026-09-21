package io.github.definitelystable.spongetube.benchmark

import android.content.Context
import android.net.Uri
import java.io.File

internal object BenchmarkEvidenceExporter {

    fun exportCompleted(
        context: Context,
        sessionId: String,
        namespace: String,
    ): File {
        require(SESSION_ID.matches(sessionId)) {
            "unsupported session id: $sessionId"
        }
        require(NAMESPACE.matches(namespace)) {
            "unsupported evidence namespace: $namespace"
        }

        val stagingDir = File(
            "/sdcard/Android/media/${context.packageName}" +
                "/additional_test_output/$namespace/$sessionId",
        )
        check(stagingDir.deleteRecursively()) {
            "failed to clear evidence staging directory: $stagingDir"
        }
        check(stagingDir.mkdirs()) {
            "failed to create evidence staging directory: $stagingDir"
        }

        var lastFailure = "provider not attempted"
        for (attempt in 1..FINALIZE_ATTEMPTS) {
            val attemptResult = runCatching {
                EVIDENCE_FILES.forEach { artifactName ->
                    val uri = Uri.Builder()
                        .scheme("content")
                        .authority(EVIDENCE_AUTHORITY)
                        .appendPath(sessionId)
                        .appendPath(artifactName)
                        .build()
                    val target = File(stagingDir, artifactName)
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        ?: error(
                            "provider returned null stream for $artifactName",
                        )
                    check(target.length() > 0L) {
                        "empty evidence artifact: $artifactName"
                    }
                }
            }

            if (attemptResult.isSuccess) {
                return stagingDir
            }

            lastFailure =
                attemptResult.exceptionOrNull()?.toString() ?: "unknown failure"
            EVIDENCE_FILES.forEach { File(stagingDir, it).delete() }
            if (attempt < FINALIZE_ATTEMPTS) {
                Thread.sleep(FINALIZE_POLL_MS)
            }
        }

        error(
            "target evidence was not exportable after " +
                "$FINALIZE_ATTEMPTS attempts: $lastFailure",
        )
    }

    val evidenceFiles: List<String>
        get() = EVIDENCE_FILES

    private val SESSION_ID = Regex("[A-Za-z0-9._-]+")
    private val NAMESPACE = Regex("[A-Za-z0-9._/-]+")
    private const val EVIDENCE_AUTHORITY =
        "io.github.definitelystable.spongetube.m0.evidence"
    private val EVIDENCE_FILES = listOf(
        "playback-events.jsonl",
        "playback-summary.json",
        "playback-stats-cross-check.json",
        "baseline-observations.json",
    )
    private const val FINALIZE_ATTEMPTS = 100
    private const val FINALIZE_POLL_MS = 100L
}
