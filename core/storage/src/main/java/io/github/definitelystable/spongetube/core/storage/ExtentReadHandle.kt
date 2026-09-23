package io.github.definitelystable.spongetube.core.storage

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ExtentReadHandle internal constructor(
    val extent: CommittedExtent,
    private val channel: FileChannel,
    private val onClosed: () -> Unit,
) : Closeable {
    private val lock = Any()
    private var closed = false

    val length: Long
        get() = extent.length

    fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): Int = synchronized(lock) {
        checkOpen()
        require(position >= 0L) { "position must be >= 0" }
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        require(offset <= buffer.size) { "offset exceeds byte array size" }
        require(length <= buffer.size - offset) {
            "offset + length exceeds byte array size"
        }

        if (length == 0) {
            return@synchronized 0
        }
        if (position >= extent.length) {
            return@synchronized -1
        }

        val boundedLength = minOf(
            length.toLong(),
            extent.length - position,
        ).toInt()

        try {
            channel.read(
                ByteBuffer.wrap(buffer, offset, boundedLength),
                position,
            )
        } catch (error: IOException) {
            throw storageFailure(
                operation = "read-extent",
                cause = error,
            )
        }
    }

    override fun close() {
        val failure = synchronized(lock) {
            if (closed) {
                return
            }
            closed = true

            try {
                channel.close()
                null
            } catch (error: IOException) {
                storageFailure(
                    operation = "close-extent-read",
                    cause = error,
                )
            }
        }

        try {
            onClosed()
        } catch (releaseError: Throwable) {
            if (failure != null) {
                failure.addSuppressed(releaseError)
            } else {
                throw releaseError
            }
        }

        failure?.let { throw it }
    }

    private fun checkOpen() {
        if (closed) {
            throw ExtentReadHandleClosedException(extent.extentId)
        }
    }
}

class ExtentReadHandleClosedException internal constructor(
    extentId: ExtentId,
) : ExtentStoreException(
    "extent read handle is closed: $extentId",
)
