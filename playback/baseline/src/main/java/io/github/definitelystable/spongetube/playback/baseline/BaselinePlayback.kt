package io.github.definitelystable.spongetube.playback.baseline

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
object BaselinePlayback {

    fun prepare(
        context: Context,
        spec: BaselinePlaybackSpec,
    ): PreparedBaselinePlayback {
        val cacheSnapshot = when (spec.mode) {
            BaselineMode.DIRECT -> null
            BaselineMode.STANDARD_CACHE -> BaselineCacheStore.prepare(
                context = context,
                cacheState = spec.cacheState,
            )
        }

        val transport = BaselineTransportFactory.create(
            context = context,
            requested = spec.transport,
        )

        return try {
            val dataSourceFactory: DataSource.Factory = when (spec.mode) {
                BaselineMode.DIRECT -> transport.factory
                BaselineMode.STANDARD_CACHE -> CacheDataSource.Factory()
                    .setCache(checkNotNull(cacheSnapshot).cache)
                    .setUpstreamDataSourceFactory(transport.factory)
            }

            PreparedBaselinePlayback(
                spec = spec,
                identity = BaselinePlaybackIdentity(
                    mode = spec.mode,
                    cacheState = spec.cacheState,
                    requestedTransport = spec.transport,
                    effectiveTransport = transport.resolution.effective,
                ),
                dataSourceFactory = dataSourceFactory,
                cacheSnapshot = cacheSnapshot,
                transport = transport,
            )
        } catch (throwable: Throwable) {
            transport.closeAsync()
            throw throwable
        }
    }

    fun createSession(
        context: Context,
        prepared: PreparedBaselinePlayback,
    ): BaselinePlaybackSession {
        val resources = prepared.consume()

        var player: ExoPlayer? = null

        return try {
            player = ExoPlayer.Builder(context.applicationContext).build()
            val mediaItem = MediaItem.fromUri(resources.spec.mediaUri)
            val mediaSource = DashMediaSource.Factory(resources.dataSourceFactory)
                .createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)

            BaselinePlaybackSession(
                player = player,
                identity = resources.identity,
                cache = resources.cacheSnapshot,
                transport = resources.transport,
            )
        } catch (throwable: Throwable) {
            player?.release()
            resources.transport.closeAsync()
            throw throwable
        }
    }

    fun standardCacheBytes(): Long = BaselineCacheStore.cacheBytes()
}

@UnstableApi
class PreparedBaselinePlayback internal constructor(
    val spec: BaselinePlaybackSpec,
    val identity: BaselinePlaybackIdentity,
    internal val dataSourceFactory: DataSource.Factory,
    internal val cacheSnapshot: BaselineCacheSnapshot?,
    internal val transport: BaselineTransportResources,
) : AutoCloseable {

    private val consumed = AtomicBoolean(false)

    internal fun consume(): PreparedResources {
        check(consumed.compareAndSet(false, true)) {
            "PreparedBaselinePlayback has already been consumed or closed"
        }

        return PreparedResources(
            spec = spec,
            identity = identity,
            dataSourceFactory = dataSourceFactory,
            cacheSnapshot = cacheSnapshot,
            transport = transport,
        )
    }

    override fun close() {
        if (consumed.compareAndSet(false, true)) {
            transport.closeAsync()
        }
    }
}

@UnstableApi
internal data class PreparedResources(
    val spec: BaselinePlaybackSpec,
    val identity: BaselinePlaybackIdentity,
    val dataSourceFactory: DataSource.Factory,
    val cacheSnapshot: BaselineCacheSnapshot?,
    val transport: BaselineTransportResources,
)

@UnstableApi
class BaselinePlaybackSession internal constructor(
    val player: Player,
    val identity: BaselinePlaybackIdentity,
    private val cache: BaselineCacheSnapshot?,
    private val transport: BaselineTransportResources,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    val cacheBytesAtPreparation: Long
        get() = cache?.bytesAtPreparation ?: 0L

    fun cacheBytesNow(): Long = cache?.cache?.cacheSpace ?: 0L

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        player.release()
        transport.closeAsync()
    }
}
