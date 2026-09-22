package io.github.definitelystable.spongetube.core.storage

import java.io.File

internal data class FileIntegrityFact(
    val exists: Boolean,
    val length: Long?,
    val sha256: Sha256Digest?,
)

internal object FileIntegrity {
    fun inspect(file: File): FileIntegrityFact {
        if (!file.isFile) {
            return FileIntegrityFact(
                exists = false,
                length = null,
                sha256 = null,
            )
        }

        return FileIntegrityFact(
            exists = true,
            length = file.length(),
            sha256 = Sha256.digest(file),
        )
    }
}
