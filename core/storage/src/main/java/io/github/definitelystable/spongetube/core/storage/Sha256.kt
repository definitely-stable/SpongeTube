package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal object Sha256 {
    private const val FILE_BUFFER_SIZE = 64 * 1024
    private const val HEX = "0123456789abcdef"

    fun digest(bytes: ByteArray): Sha256Digest =
        Sha256Digest(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .toLowerHex(),
        )

    fun digestUtf8(value: String): Sha256Digest =
        digest(value.toByteArray(Charsets.UTF_8))

    fun digest(file: File): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(FILE_BUFFER_SIZE)

        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }

        return Sha256Digest(digest.digest().toLowerHex())
    }

    private fun ByteArray.toLowerHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            chars[index * 2] = HEX[unsigned ushr 4]
            chars[index * 2 + 1] = HEX[unsigned and 0x0f]
        }
        return chars.concatToString()
    }
}
