package io.github.definitelystable.spongetube.core.storage

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal interface ExtentStoreLease : Closeable

internal class FileExtentStoreLease private constructor(
    private val rootKey: String,
    private val file: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
) : ExtentStoreLease {
    @Volatile
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
        }

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
        } finally {
            releaseProcessOwnership(rootKey)
        }

        failure?.let {
            throw ExtentStoreException(
                "failed to release extent store lease",
                it,
            )
        }
    }

    companion object {
        private val processOwnershipLock = Any()
        private val processOwnedRoots = mutableSetOf<String>()

        fun acquire(rootDirectory: File): FileExtentStoreLease {
            val rootKey = rootDirectory.canonicalFile.path
            reserveProcessOwnership(rootKey)

            val leaseFile = File(rootDirectory, ".store.lock")
            var file: RandomAccessFile? = null
            var channel: FileChannel? = null

            try {
                val openedFile = RandomAccessFile(leaseFile, "rw")
                file = openedFile

                val openedChannel = openedFile.channel
                channel = openedChannel

                val lock = try {
                    openedChannel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }

                if (lock == null) {
                    throw ExtentConflictException(
                        "extent store root is already owned: " +
                            rootDirectory.absolutePath,
                    )
                }

                return FileExtentStoreLease(
                    rootKey = rootKey,
                    file = openedFile,
                    channel = openedChannel,
                    lock = lock,
                )
            } catch (error: Throwable) {
                try {
                    channel?.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }

                try {
                    file?.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }

                releaseProcessOwnership(rootKey)

                if (
                    error is ExtentConflictException ||
                    error is ExtentStoreException
                ) {
                    throw error
                }

                throw ExtentStoreException(
                    "failed to acquire extent store lease: " +
                        leaseFile.absolutePath,
                    error,
                )
            }
        }

        private fun reserveProcessOwnership(rootKey: String) {
            synchronized(processOwnershipLock) {
                if (!processOwnedRoots.add(rootKey)) {
                    throw ExtentConflictException(
                        "extent store root is already owned: " + rootKey,
                    )
                }
            }
        }

        private fun releaseProcessOwnership(rootKey: String) {
            synchronized(processOwnershipLock) {
                processOwnedRoots.remove(rootKey)
            }
        }
    }
}

internal object NoOpExtentStoreLease : ExtentStoreLease {
    override fun close() = Unit
}
