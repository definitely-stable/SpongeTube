package io.github.definitelystable.spongetube.playback.bridge

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Static guard: the bridge module must not contain any Media3 path that can
 * fetch Sponge-managed media outside FetchBroker (M1 §11 "no hidden second
 * upstream").
 */
class SpongeBridgeGuardTest {
    private val moduleRoot = locateModuleRoot()

    @Test
    fun productSourcesContainNoIndependentUpstream() {
        val sources = File(moduleRoot, "src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue(sources.isNotEmpty(), "no bridge sources found under $moduleRoot")

        val violations = sources.flatMap { file ->
            val text = file.readText()
            FORBIDDEN.filter { token -> token in text }
                .map { token -> "${file.name}: $token" }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun buildScriptDeclaresNoNetworkOrCacheDataSourceArtifacts() {
        val script = File(moduleRoot, "build.gradle.kts").readText()
        val forbiddenArtifacts = listOf(
            "media3.datasource.okhttp",
            "media3.datasource.cronet",
            "media3.database",
            "okhttp",
            "cronet",
        )
        assertEquals(
            emptyList<String>(),
            forbiddenArtifacts.filter { it in script },
        )
    }

    private fun locateModuleRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "src/main/java/io/github/definitelystable/spongetube/playback/bridge").isDirectory) {
                return directory
            }
            val nested = File(directory, "playback/bridge")
            if (File(nested, "build.gradle.kts").isFile) {
                return nested
            }
            directory = directory.parentFile
        }
        error("playback/bridge module root not found")
    }

    private companion object {
        val FORBIDDEN = listOf(
            "DefaultHttpDataSource",
            "HttpEngineDataSource",
            "DefaultDataSource",
            "CacheDataSource",
            "OkHttpDataSource",
            "CronetDataSource",
            "setUpstreamDataSourceFactory",
            "HttpURLConnection",
            "DefaultMediaSourceFactory",
        )
    }
}
