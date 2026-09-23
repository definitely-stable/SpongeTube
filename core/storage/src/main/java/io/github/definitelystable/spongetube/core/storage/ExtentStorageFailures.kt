package io.github.definitelystable.spongetube.core.storage

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
    val errno = generateSequence(cause as Throwable?) { it.cause }
        .filterIsInstance<ErrnoException>()
        .firstOrNull()
        ?.errno

    val kind = if (errno == OsConstants.ENOSPC) {
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
