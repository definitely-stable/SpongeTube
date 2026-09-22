package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtentStoreAndroidTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(
            context.filesDir,
            "m1b-test-" + UUID.randomUUID().toString(),
        )
        databaseFile = File(root, "metadata/extents.db")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun committedExtentSurvivesCloseAndStartupRecovery() = runBlocking {
        val bytes = "android-persisted-extent".encodeToByteArray()
        val first = ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )
        val writer = first.openWriter(spec("persisted", bytes))
        writer.write(bytes)
        val committed = writer.commit()
        assertEquals(1, first.committedExtents().size)
        first.close()

        val reopened = ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )
        assertEquals(
            1,
            reopened.initialRecoveryReport.verifiedPublishedExtents,
        )
        assertEquals(
            listOf(committed.extentId),
            reopened.committedExtents().map(CommittedExtent::extentId),
        )
        reopened.close()
    }

    @Test
    fun corruptPublishedFileIsQuarantinedBeforeExposure() = runBlocking {
        val bytes = "valid-before-corruption".encodeToByteArray()
        val first = ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )
        val writer = first.openWriter(spec("corrupt", bytes))
        writer.write(bytes)
        val committed = writer.commit()
        first.close()

        File(root, committed.storagePath).writeBytes(
            "corrupted".encodeToByteArray(),
        )

        val reopened = ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )
        assertEquals(
            1,
            reopened.initialRecoveryReport.quarantinedExtents,
        )
        assertTrue(reopened.committedExtents().isEmpty())
        reopened.close()
    }

    @Test
    fun missingPublishedFileIsQuarantinedBeforeExposure() = runBlocking {
        val bytes = "valid-before-delete".encodeToByteArray()
        val first = ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )
        val writer = first.openWriter(spec("missing", bytes))
        writer.write(bytes)
        val committed = writer.commit()
        first.close()

        assertTrue(File(root, committed.storagePath).delete())

        val reopened = ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )
        assertEquals(
            1,
            reopened.initialRecoveryReport.quarantinedExtents,
        )
        assertTrue(reopened.committedExtents().isEmpty())
        reopened.close()
    }

    private fun spec(
        id: String,
        bytes: ByteArray,
    ): ExtentSpec =
        ExtentSpec(
            extentId = ExtentId(id),
            trackId = "video",
            representationId = "v1",
            mediaStartUs = 0,
            mediaEndUs = 10_000_000,
            byteStart = 0,
            byteEndExclusive = bytes.size.toLong(),
            expectedLength = bytes.size.toLong(),
            expectedSha256 = sha256(bytes),
        )

    private fun sha256(bytes: ByteArray): Sha256Digest =
        Sha256Digest(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") {
                    "%02x".format(it)
                },
        )
}
