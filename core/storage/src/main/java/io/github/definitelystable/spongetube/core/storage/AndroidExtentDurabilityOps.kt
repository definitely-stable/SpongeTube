package io.github.definitelystable.spongetube.core.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object AndroidExtentDurabilityOps : ExtentDurabilityOps {
    override fun ensureDirectory(directory: File) {
        if (directory.isDirectory) {
            return
        }

        val parent = directory.parentFile
        if (parent != null && !parent.isDirectory) {
            ensureDirectory(parent)
        }

        try {
            Os.mkdir(
                directory.absolutePath,
                OsConstants.S_IRWXU,
            )
        } catch (error: ErrnoException) {
            if (
                error.errno == OsConstants.EEXIST &&
                directory.isDirectory
            ) {
                return
            }
            throw storageFailure(
                operation = "create-extent-directory",
                cause = error,
            )
        }

        if (parent != null) {
            syncDirectory(parent)
        }
    }

    override fun syncAndClose(output: FileOutputStream) {
        var failure: Throwable? = null
        try {
            output.flush()
            output.fd.sync()
        } catch (error: IOException) {
            failure = error
            throw storageFailure(
                operation = "fsync-extent-temp",
                cause = error,
            )
        } finally {
            try {
                output.close()
            } catch (closeError: Throwable) {
                if (failure != null) {
                    failure.addSuppressed(closeError)
                } else {
                    if (closeError is IOException) {
                        throw storageFailure(
                            operation = "close-extent-temp",
                            cause = closeError,
                        )
                    }
                    throw ExtentStoreException(
                        "failed to close extent temp file",
                        closeError,
                    )
                }
            }
        }
    }

    override fun installAtomically(
        source: File,
        destination: File,
    ) {
        val parent = requireNotNull(destination.parentFile) {
            "extent destination must have a parent directory"
        }

        if (destination.exists()) {
            throw ExtentConflictException(
                "extent destination already exists: " +
                    destination.absolutePath,
            )
        }

        try {
            Os.rename(source.absolutePath, destination.absolutePath)
            syncDirectory(parent)
        } catch (error: ErrnoException) {
            throw storageFailure(
                operation = "install-extent",
                cause = error,
            )
        }
    }

    override fun deleteDurably(file: File) {
        if (!file.exists()) {
            return
        }

        try {
            Os.remove(file.absolutePath)
            file.parentFile?.let(::syncDirectory)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) {
                return
            }
            throw storageFailure(
                operation = "delete-extent",
                cause = error,
            )
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = try {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
        } catch (error: ErrnoException) {
            throw storageFailure(
                operation = "open-directory-fsync",
                cause = error,
            )
        }

        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw storageFailure(
                operation = "fsync-directory",
                cause = error,
            )
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // fsync above is the durability decision point.
            }
        }
    }
}
