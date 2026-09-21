package io.github.definitelystable.spongetube.measurement

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object MeasurementSessionControl {

    private data class Registration(
        val sessionId: String,
        val finishIfQuiescent: () -> Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var registration: Registration? = null

    @Synchronized
    fun register(
        sessionId: String,
        finishIfQuiescent: () -> Boolean,
    ) {
        require(sessionId.isNotBlank()) {
            "sessionId must not be blank"
        }
        registration = Registration(
            sessionId = sessionId,
            finishIfQuiescent = finishIfQuiescent,
        )
    }

    @Synchronized
    fun clear(sessionId: String) {
        if (registration?.sessionId == sessionId) {
            registration = null
        }
    }

    fun finishIfQuiescent(sessionId: String): Boolean {
        require(sessionId.isNotBlank()) {
            "sessionId must not be blank"
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return finishOnMainThread(sessionId)
        }

        val latch = CountDownLatch(1)
        var finished = false
        var failure: Throwable? = null

        mainHandler.post {
            try {
                finished = finishOnMainThread(sessionId)
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                latch.countDown()
            }
        }

        check(latch.await(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "timed out waiting for measurement finalization on main thread"
        }
        failure?.let { throw it }
        return finished
    }

    @Synchronized
    private fun finishOnMainThread(sessionId: String): Boolean {
        val active = registration ?: return false
        if (active.sessionId != sessionId) {
            return false
        }
        return active.finishIfQuiescent()
    }

    private const val MAIN_THREAD_TIMEOUT_SECONDS = 5L
}
