package io.github.definitelystable.spongetube.measurement

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class BenchmarkEvidenceProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("M0 evidence provider is read-only")
        }

        val segments = uri.pathSegments
        if (segments.size != 2) {
            throw FileNotFoundException("Expected /<session>/<artifact>")
        }

        val sessionId = segments[0]
        val artifactName = segments[1]
        if (!SESSION_ID.matches(sessionId)) {
            throw FileNotFoundException("Invalid session id")
        }
        if (artifactName !in ALLOWED_ARTIFACTS) {
            throw FileNotFoundException("Unsupported evidence artifact")
        }

        val appContext = context
            ?: throw FileNotFoundException("Provider context unavailable")
        val sessionDir = File(
            File(appContext.filesDir, "m0-measurement"),
            sessionId,
        )
        val completeMarker = File(sessionDir, COMPLETE_MARKER)
        if (!completeMarker.isFile) {
            throw FileNotFoundException("Evidence session is not complete")
        }

        val target = File(sessionDir, artifactName)
        if (!target.isFile) {
            throw FileNotFoundException("Evidence artifact is missing")
        }

        return ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != METHOD_FINISH_IF_QUIESCENT) {
            return super.call(method, arg, extras)
        }

        val sessionId = arg
            ?.takeIf(SESSION_ID::matches)
            ?: throw IllegalArgumentException("valid session id required")

        return Bundle().apply {
            putBoolean(
                KEY_FINISHED,
                MeasurementSessionControl.finishIfQuiescent(sessionId),
            )
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
        throw UnsupportedOperationException("read-only provider")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9._-]+")
        const val COMPLETE_MARKER = "evidence-complete.marker"
        const val METHOD_FINISH_IF_QUIESCENT =
            "finishIfQuiescent"
        const val KEY_FINISHED = "finished"
        val ALLOWED_ARTIFACTS = setOf(
            "playback-events.jsonl",
            "playback-summary.json",
            "playback-stats-cross-check.json",
            "baseline-observations.json",
        )
    }
}
