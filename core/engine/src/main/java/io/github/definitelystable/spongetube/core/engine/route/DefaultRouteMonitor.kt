package io.github.definitelystable.spongetube.core.engine.route

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun interface RouteSignalSink {
    fun offer(signal: RouteSignal)
}

/**
 * One platform registration. Implementations translate platform objects into
 * [RouteSignal]s and hand them to the sink; they never reduce state. The only
 * non-Android implementation is the host-test fake that proves lifecycle.
 */
internal interface RouteSignalPlatform {
    /** Registers exactly one platform observer delivering into [sink]. */
    fun register(sink: RouteSignalSink)

    /** One synchronous lookup after registration, outside any callback. */
    fun bootstrap(sink: RouteSignalSink)

    fun unregister()
}

/**
 * Process-global default-route observer: one monitor, one platform
 * registration, one reducer coroutine.
 *
 * Platform callbacks only `trySend` into an unlimited channel. Route
 * connectivity events are low frequency and dropping one would corrupt the
 * state machine, so the channel is unbounded by design. All state
 * transitions, `routeEpoch` and evidence ordering happen in the single
 * reducer coroutine.
 */
internal class DefaultRouteMonitor private constructor(
    private val platform: RouteSignalPlatform,
    private val clock: () -> Long,
    listener: RouteEventListener?,
) {
    private val signals = Channel<RouteSignal>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val reducer = DefaultRouteReducer(listener)
    private val mutableObservations = MutableStateFlow(reducer.start(clock()))
    private lateinit var reducerJob: Job

    private val sink = RouteSignalSink { signal ->
        if (!closed.get()) {
            signals.trySend(signal)
        }
    }

    val observations: StateFlow<RouteObservation> = mutableObservations.asStateFlow()

    val isClosed: Boolean
        get() = closed.get()

    /**
     * 1. marks closed; 2. unregisters the platform observer; 3. stops
     * accepting callbacks; 4. closes the channel; 5. awaits the reducer, which
     * drains pending signals and records MONITOR_STOPPED. Idempotent: a second
     * call never unregisters again.
     */
    suspend fun shutdown() {
        var failure: Throwable? = null
        if (closed.compareAndSet(false, true)) {
            try {
                platform.unregister()
            } catch (error: Throwable) {
                failure = error
            } finally {
                signals.close()
            }
        }
        reducerJob.join()
        failure?.let { throw it }
    }

    private fun launchReducer(scope: CoroutineScope) {
        reducerJob = scope.launch {
            for (signal in signals) {
                mutableObservations.value = reducer.reduce(signal, clock())
            }
            mutableObservations.value = reducer.stop(clock())
        }
    }

    private fun abandon() {
        closed.set(true)
        signals.close()
        reducerJob.cancel()
    }

    companion object {
        /**
         * Creates the reducer, registers the platform source and runs the
         * initial bootstrap. A failed open leaves nothing registered.
         */
        fun open(
            scope: CoroutineScope,
            platform: RouteSignalPlatform,
            clock: () -> Long,
            listener: RouteEventListener? = null,
        ): DefaultRouteMonitor {
            val monitor = DefaultRouteMonitor(platform, clock, listener)
            monitor.launchReducer(scope)
            try {
                platform.register(monitor.sink)
            } catch (error: Throwable) {
                monitor.abandon()
                throw error
            }
            try {
                platform.bootstrap(monitor.sink)
            } catch (error: Throwable) {
                try {
                    platform.unregister()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                monitor.abandon()
                throw error
            }
            return monitor
        }
    }
}
