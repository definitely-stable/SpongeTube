package io.github.definitelystable.spongetube.measurement

import androidx.tracing.Trace

internal object PlaybackTraceSections {

    private const val PREPARE = "SpongeTube:M0:prepare"
    private const val SEEK = "SpongeTube:M0:seek"
    private const val REBUFFER = "SpongeTube:M0:rebuffer"

    fun enableForMeasurement() {
        Trace.forceEnableAppTracing()
    }

    fun beginPrepare(cookie: Int) {
        Trace.beginAsyncSection(PREPARE, cookie)
    }

    fun endPrepare(cookie: Int) {
        Trace.endAsyncSection(PREPARE, cookie)
    }

    fun beginSeek(cookie: Int) {
        Trace.beginAsyncSection(SEEK, cookie)
    }

    fun endSeek(cookie: Int) {
        Trace.endAsyncSection(SEEK, cookie)
    }

    fun beginRebuffer(cookie: Int) {
        Trace.beginAsyncSection(REBUFFER, cookie)
    }

    fun endRebuffer(cookie: Int) {
        Trace.endAsyncSection(REBUFFER, cookie)
    }
}
