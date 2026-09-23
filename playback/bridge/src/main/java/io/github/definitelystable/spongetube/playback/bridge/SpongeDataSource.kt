package io.github.definitelystable.spongetube.playback.bridge

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeException
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeFailure
import io.github.definitelystable.spongetube.core.engine.PlaybackBridgeRuntime
import io.github.definitelystable.spongetube.core.engine.PlaybackReadSession
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi

/**
 * Media3 DataSource over Sponge published coverage.
 *
 * Every `open` creates one engine [PlaybackReadSession]: local published
 * extents are read directly; misses wait for FetchBroker publication. There is
 * no upstream DataSource and no Media3 cache. `isNetwork = false`: local
 * serves are not network transfers, and a miss wait is FetchBroker work, not
 * a Media3-owned network transfer (M1-E D8).
 */
@UnstableApi
@OptIn(SpongeBridgeApi::class)
class SpongeDataSource internal constructor(
    private val runtime: PlaybackBridgeRuntime,
    private val authority: String,
) : BaseDataSource(/* isNetwork= */ false) {
    private var session: PlaybackReadSession? = null
    private var uri: Uri? = null
    private var transferStarted = false

    override fun open(dataSpec: DataSpec): Long {
        val resourceKey = SpongeUris.resourceKey(dataSpec.uri.toString(), authority)
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val readSession = runtime.newReadSession()
        session = readSession
        val resource = runtime.plan.resource(resourceKey)
        val engineLength = if (
            resource != null &&
            dataSpec.length != C.LENGTH_UNSET.toLong() &&
            dataSpec.position <= resource.length
        ) {
            minOf(dataSpec.length, resource.length - dataSpec.position)
        } else {
            dataSpec.length
        }
        val available = try {
            readSession.open(
                resourceKey,
                dataSpec.position,
                engineLength,
            )
        } catch (error: PlaybackBridgeException) {
            if (error.failure == PlaybackBridgeFailure.POSITION_OUT_OF_RANGE) {
                throw DataSourceException(
                    error,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
            }
            throw error
        }
        transferStarted = true
        transferStarted(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            dataSpec.length
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) {
            return 0
        }
        val readSession = checkNotNull(session) { "SpongeDataSource is not open" }
        val read = readSession.read(buffer, offset, length)
        if (read == PlaybackReadSession.END_OF_INPUT) {
            return C.RESULT_END_OF_INPUT
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val readSession = session
        session = null
        try {
            readSession?.close()
        } finally {
            if (transferStarted) {
                transferStarted = false
                transferEnded()
            }
        }
    }
}

/** Creates [SpongeDataSource] instances bound to one bridge runtime. */
@UnstableApi
@OptIn(SpongeBridgeApi::class)
class SpongeDataSourceFactory(
    private val runtime: PlaybackBridgeRuntime,
    private val authority: String,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SpongeDataSource(runtime, authority)
}
