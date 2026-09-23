package io.github.definitelystable.spongetube.testsupport.fixture.f1

import android.content.res.AssetManager
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Loads F1 fixture bytes from packaged test assets (the `test-fixtures/media`
 * directory) and verifies every byte array against `manifest.json` before it
 * is used. A length or SHA-256 mismatch fails closed.
 */
class F1FixtureAssets(
    private val assets: AssetManager,
) {
    val resourceFacts: Map<String, F1ResourceFact> = loadResourceFacts()

    fun verifiedBytes(path: String): ByteArray {
        val fact = requireNotNull(resourceFacts[path]) {
            "fixture manifest has no resource $path"
        }
        val bytes = assets.open(path).use { it.readBytes() }
        check(bytes.size.toLong() == fact.length) {
            "$path length mismatch: manifest=${fact.length} actual=${bytes.size}"
        }
        val actual = sha256Hex(bytes)
        check(actual == fact.sha256) {
            "$path sha256 mismatch: manifest=${fact.sha256} actual=$actual"
        }
        return bytes
    }

    fun catalog(): F1Catalog =
        F1FixtureCatalog.build(
            mpdBytes = verifiedBytes(F1FixtureCatalog.MANIFEST_PATH),
            resources = resourceFacts,
        )

    private fun loadResourceFacts(): Map<String, F1ResourceFact> {
        val raw = assets.open("manifest.json").bufferedReader().use {
            it.readText()
        }
        val fixtures = JSONObject(raw).getJSONArray("fixtures")
        var fixture: JSONObject? = null
        for (index in 0 until fixtures.length()) {
            val candidate = fixtures.getJSONObject(index)
            if (candidate.getString("fixtureId") == "F1") {
                fixture = candidate
                break
            }
        }

        val resources = checkNotNull(fixture) { "F1 fixture missing" }
            .getJSONArray("resources")
        val result = linkedMapOf<String, F1ResourceFact>()
        for (index in 0 until resources.length()) {
            val resource = resources.getJSONObject(index)
            result[resource.getString("relativePath")] = F1ResourceFact(
                length = resource.getLong("sizeBytes"),
                sha256 = resource.getString("sha256"),
            )
        }
        return result
    }
}

fun sha256Hex(
    bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size - offset,
): String {
    val digest = MessageDigest.getInstance("SHA-256").run {
        update(bytes, offset, length)
        digest()
    }
    val alphabet = "0123456789abcdef"
    val chars = CharArray(digest.size * 2)
    digest.forEachIndexed { index, value ->
        val unsigned = value.toInt() and 0xff
        chars[index * 2] = alphabet[unsigned ushr 4]
        chars[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return chars.concatToString()
}
