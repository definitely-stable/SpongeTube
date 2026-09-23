package io.github.definitelystable.spongetube.core.storage

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants

enum class ExtentStorageFailureKind {
    NO_SPACE,
    IO,
}

class ExtentStorageException internal constructor(
    val kind: ExtentStorageFailureKind,
    val operation: String,
    cause: Throwable,
) : ExtentStoreException(
    "extent storage operation failed: operation=$operation kind=$kind",
    cause,
)

internal fun storageFailure(
    operation: String,
    cause: Throwable,
): ExtentStorageException {
    val causes = failureChain(cause)
    val errno = causes
        .filterIsInstance<ErrnoException>()
        .firstOrNull()
        ?.errno

    val kind = if (
        causes.any { it is SQLiteFullException } ||
        errno == OsConstants.ENOSPC ||
        errno == OsConstants.EDQUOT
    ) {
        ExtentStorageFailureKind.NO_SPACE
    } else {
        ExtentStorageFailureKind.IO
    }

    return ExtentStorageException(
        kind = kind,
        operation = operation,
        cause = cause,
    )
}

internal fun metadataStorageFailureOrNull(
    operation: String,
    cause: Throwable,
): ExtentStorageException? {
    val causes = failureChain(cause)
    val isMetadataStorageFailure = causes.any {
        it is SQLiteFullException || it is SQLiteDiskIOException
    }
    if (!isMetadataStorageFailure) {
        return null
    }

    return storageFailure(
        operation = operation,
        cause = cause,
    )
}

private fun failureChain(cause: Throwable): List<Throwable> =
    generateSequence(cause as Throwable?) { it.cause }.toList()
