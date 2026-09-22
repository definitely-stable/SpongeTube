package io.github.definitelystable.spongetube.core.storage

import java.io.File
import java.security.MessageDigest

internal class ExtentPathLayout(
    private val rootDirectory: File,
) {
    val extentsDirectory: File = File(rootDirectory, "extents")

    fun ensureRoot(durability: ExtentDurabilityOps) {
        durability.ensureDirectory(rootDirectory)
        durability.ensureDirectory(extentsDirectory)
    }

    fun finalRelativePath(extentId: ExtentId): String {
        val key = storageKey(extentId)
        return "extents/${key.substring(0, 2)}/$key.extent"
    }

    fun finalFile(
        extentId: ExtentId,
        durability: ExtentDurabilityOps,
    ): File {
        val key = storageKey(extentId)
        val directory = File(extentsDirectory, key.substring(0, 2))
        durability.ensureDirectory(directory)
        return File(directory, "$key.extent")
    }

    fun createPartFile(
        extentId: ExtentId,
        durability: ExtentDurabilityOps,
    ): File {
        val key = storageKey(extentId)
        val directory = File(extentsDirectory, key.substring(0, 2))
        durability.ensureDirectory(directory)
        return File.createTempFile(".$key.", ".part", directory)
    }

    fun resolveStoredPath(relativePath: String): File {
        require(!File(relativePath).isAbsolute) {
            "stored path must be relative"
        }

        val rootCanonical = rootDirectory.canonicalFile
        val resolved = File(rootDirectory, relativePath).canonicalFile
        val prefix = rootCanonical.path + File.separator

        require(
            resolved == rootCanonical || resolved.path.startsWith(prefix),
        ) {
            "stored path escapes the extent root"
        }

        return resolved
    }

    fun relativePath(file: File): String {
        val rootCanonical = rootDirectory.canonicalFile
        val fileCanonical = file.canonicalFile
        val prefix = rootCanonical.path + File.separator
        require(fileCanonical.path.startsWith(prefix)) {
            "file is outside the extent root"
        }
        return fileCanonical.path
            .removePrefix(prefix)
            .replace(File.separatorChar, '/')
    }

    fun partFiles(): List<File> =
        if (!extentsDirectory.isDirectory) {
            emptyList()
        } else {
            extentsDirectory.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".part") }
                .toList()
        }

    fun finalExtentFiles(): List<File> =
        if (!extentsDirectory.isDirectory) {
            emptyList()
        } else {
            extentsDirectory.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".extent") }
                .toList()
        }

    private fun storageKey(extentId: ExtentId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(extentId.value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
