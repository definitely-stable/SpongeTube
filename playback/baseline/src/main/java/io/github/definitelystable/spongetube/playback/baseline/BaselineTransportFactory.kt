package io.github.definitelystable.spongetube.playback.baseline

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
internal object BaselineTransportFactory {

    fun create(
        context: Context,
        requested: BaselineTransport,
    ): BaselineTransportResources {
        val effective = BaselineTransportPolicy.resolve(
            requested = requested,
            sdkInt = Build.VERSION.SDK_INT,
            sExtensionVersion = currentSExtensionVersion(),
        )

        return when (effective) {
            EffectiveTransport.HTTP_ENGINE -> createHttpEngine(
                context = context.applicationContext,
                requested = requested,
            )

            EffectiveTransport.DEFAULT_HTTP -> BaselineTransportResources(
                factory = DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT),
                resolution = BaselineTransportResolution(
                    requested = requested,
                    effective = EffectiveTransport.DEFAULT_HTTP,
                ),
                closeAction = null,
            )
        }
    }

    private fun currentSExtensionVersion(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)
        } else {
            0
        }
    }

    @SuppressLint("NewApi")
    private fun createHttpEngine(
        context: Context,
        requested: BaselineTransport,
    ): BaselineTransportResources {
        val callbackExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "spongetube-m0-httpengine").apply {
                isDaemon = true
            }
        }

        val engine = try {
            HttpEngine.Builder(context)
                .setUserAgent(USER_AGENT)
                .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
                .build()
        } catch (throwable: Throwable) {
            callbackExecutor.shutdownNow()
            throw throwable
        }

        val factory = HttpEngineDataSource.Factory(engine, callbackExecutor)

        return BaselineTransportResources(
            factory = factory,
            resolution = BaselineTransportResolution(
                requested = requested,
                effective = EffectiveTransport.HTTP_ENGINE,
            ),
            closeAction = {
                try {
                    engine.shutdown()
                } finally {
                    callbackExecutor.shutdown()
                }
            },
        )
    }

    private const val USER_AGENT = "SpongeTube-M0-C"
}

@UnstableApi
internal class BaselineTransportResources(
    val factory: DataSource.Factory,
    val resolution: BaselineTransportResolution,
    private val closeAction: (() -> Unit)?,
) {
    private val closed = AtomicBoolean(false)

    fun closeAsync() {
        val action = closeAction ?: return
        if (!closed.compareAndSet(false, true)) {
            return
        }

        BaselineTransportCloser.execute(action)
    }
}

private object BaselineTransportCloser {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spongetube-m0-transport-close").apply {
            isDaemon = true
        }
    }

    fun execute(action: () -> Unit) {
        executor.execute(action)
    }
}
