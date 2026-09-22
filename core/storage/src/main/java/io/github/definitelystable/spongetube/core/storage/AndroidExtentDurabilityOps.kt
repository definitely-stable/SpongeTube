package io.github.definitelystable.spongetube.core.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

internal object AndroidExtentDurabilityOps : ExtentDurabilityOps {
    override fun ensureDirectory(directory: File) {
        if (directory.isDirectory) {
            return
        }

        val parent = directory.parentFile
        if (parent != null && !parent.isDirectory) {
            ensureDirectory(parent)
        }

        if (!directory.mkdir() && !directory.isDirectory) {
            throw ExtentStoreException(
                "failed to create extent directory: ${directory.absolutePath}",
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
        } catch (error: Throwable) {
            failure = error
            throw ExtentStoreException("failed to fsync extent temp file", error)
        } finally {
            try {
                output.close()
            } catch (closeError: Throwable) {
                if (failure != null) {
                    failure.addSuppressed(closeError)
                } else {
                    throw ExtentStoreException(
                        "failed to close extent temp file",
                        closeError,
                    )
                }
            }
        }
    }

    override fun renameAtomically(
        source: File,
        destination: File,
    ) {
        if (destination.exists()) {
            throw ExtentConflictException(
                "extent destination already exists: ${destination.absolutePath}",
            )
        }

        try {
            Os.rename(source.absolutePath, destination.absolutePath)
            syncDirectory(
                requireNotNull(destination.parentFile) {
                    "extent destination must have a parent directory"
                },
            )
        } catch (error: ErrnoException) {
            throw ExtentStoreException(
                "failed to atomically publish extent file " +
                    "${source.absolutePath} -> ${destination.absolutePath}",
                error,
            )
        }
    }

    override fun deleteDurably(file: File) {
        if (!file.exists()) {
            return
        }

        try {
            Os.unlink(file.absolutePath)
            file.parentFile?.let(::syncDirectory)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) {
                return
            }
            throw ExtentStoreException(
                "failed to delete extent file: ${file.absolutePath}",
                error,
            )
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = try {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_DIRECTORY,
                0,
            )
        } catch (error: ErrnoException) {
            throw ExtentStoreException(
                "failed to open directory for fsync: ${directory.absolutePath}",
                error,
            )
        }

        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw ExtentStoreException(
                "failed to fsync directory: ${directory.absolutePath}",
                error,
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
