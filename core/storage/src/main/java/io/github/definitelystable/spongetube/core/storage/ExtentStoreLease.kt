package io.github.definitelystable.spongetube.core.storage

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal interface ExtentStoreLease : Closeable

internal class FileExtentStoreLease private constructor(
    private val file: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
) : ExtentStoreLease {
    override fun close() {
        var failure: Throwable? = null

        try {
            lock.release()
        } catch (error: Throwable) {
            failure = error
        }

        try {
            channel.close()
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure.addSuppressed(error)
            }
        }

        try {
            file.close()
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure.addSuppressed(error)
            }
        }

        failure?.let {
            throw ExtentStoreException(
                "failed to release extent store lease",
                it,
            )
        }
    }

    companion object {
        fun acquire(rootDirectory: File): FileExtentStoreLease {
            val leaseFile = File(rootDirectory, ".store.lock")
            val file = RandomAccessFile(leaseFile, "rw")
            val channel = file.channel

            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Throwable) {
                runCatching { channel.close() }
                runCatching { file.close() }
                throw ExtentStoreException(
                    "failed to acquire extent store lease: " +
                        leaseFile.absolutePath,
                    error,
                )
            }

            if (lock == null) {
                runCatching { channel.close() }
                runCatching { file.close() }
                throw ExtentConflictException(
                    "extent store root is already owned: " +
                        rootDirectory.absolutePath,
                )
            }

            return FileExtentStoreLease(
                file = file,
                channel = channel,
                lock = lock,
            )
        }
    }
}

internal object NoOpExtentStoreLease : ExtentStoreLease {
    override fun close() = Unit
}
