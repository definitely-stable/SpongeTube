package io.github.definitelystable.spongetube.playback.bridge

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeRuntime
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi

/**
 * Wires one ExoPlayer to PlaybackBridge (ADR-0002).
 *
 * The player's only MediaSource.Factory is a DASH factory over
 * [SpongeDataSourceFactory] with [SpongeLoadErrorHandlingPolicy], so no
 * default HTTP/cache DataSource can be created for Sponge-managed media. The
 * DASH form is the M1 fixture adapter; YouTube delivery shape is decided after
 * #50 behind the same resource/byte engine seam.
 */
@UnstableApi
@OptIn(SpongeBridgeApi::class)
object SpongePlayback {
    fun mediaSourceFactory(
        runtime: PlaybackBridgeRuntime,
        authority: String,
        policy: SpongeLoadErrorHandlingPolicy,
    ): MediaSource.Factory =
        DashMediaSource.Factory(SpongeDataSourceFactory(runtime, authority))
            .setLoadErrorHandlingPolicy(policy)

    fun createPlayer(
        context: Context,
        runtime: PlaybackBridgeRuntime,
        authority: String,
        policy: SpongeLoadErrorHandlingPolicy,
    ): ExoPlayer =
        ExoPlayer.Builder(
            context.applicationContext,
            mediaSourceFactory(runtime, authority, policy),
        ).build()

    fun mediaItem(
        authority: String,
        manifestKey: String,
    ): MediaItem = MediaItem.fromUri(SpongeUris.uri(authority, manifestKey))
}
