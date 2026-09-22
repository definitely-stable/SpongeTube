package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

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
            sha256 = Sha256Digest(sha256(file)),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest()
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
