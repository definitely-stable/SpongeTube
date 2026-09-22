package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        context = InstrumentationRegistry.getInstrumentation().targetContext
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
    fun rootLeasePreventsConcurrentStoreRecoveryAndWrites() = runBlocking {
        val first = openStore()

        expectThrows<ExtentConflictException> {
            openStore()
        }

        val bytes = "lease-owner".encodeToByteArray()
        val committed = first.writeExtent(
            spec("lease-owner", bytes),
        ) {
            write(bytes)
        }
        first.close()

        val reopened = openStore()
        assertEquals(
            listOf(committed.extentId),
            reopened.committedExtents().map(CommittedExtent::extentId),
        )
        reopened.close()
    }

    @Test
    fun committedExtentSurvivesCloseAndStartupRecovery() = runBlocking {
        val bytes = "android-persisted-extent".encodeToByteArray()
        val spec = spec("persisted", bytes)
        val first = openStore()

        val committed = first.writeExtent(spec) {
            write(bytes)
        }

        assertEquals(1, first.committedExtents().size)
        first.close()

        val reopened = openStore()
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
        val spec = spec("corrupt", bytes)
        val first = openStore()
        val committed = first.writeExtent(spec) {
            write(bytes)
        }
        first.close()

        extentFile(committed.extentId).writeBytes(
            "corrupted".encodeToByteArray(),
        )

        val reopened = openStore()
        assertEquals(
            1,
            reopened.initialRecoveryReport.quarantinedExtents,
        )
        assertTrue(reopened.committedExtents().isEmpty())
        assertFalse(extentFile(committed.extentId).exists())
        reopened.close()
    }

    @Test
    fun missingPublishedFileIsQuarantinedBeforeExposure() = runBlocking {
        val bytes = "valid-before-delete".encodeToByteArray()
        val spec = spec("missing", bytes)
        val first = openStore()
        val committed = first.writeExtent(spec) {
            write(bytes)
        }
        first.close()

        assertTrue(extentFile(committed.extentId).delete())

        val reopened = openStore()
        assertEquals(
            1,
            reopened.initialRecoveryReport.quarantinedExtents,
        )
        assertTrue(reopened.committedExtents().isEmpty())
        reopened.close()
    }

    @Test
    fun quarantinedExtentCanBeRepairedOnlyWithSameImmutableIdentity() =
        runBlocking {
            val bytes = "repairable-extent".encodeToByteArray()
            val originalSpec = spec(
                id = "repairable",
                bytes = bytes,
                includeExpectedSha256 = false,
            )
            val first = openStore()
            val original = first.writeExtent(originalSpec) {
                write(bytes)
            }
            first.close()

            extentFile(original.extentId).writeBytes(
                "bad".encodeToByteArray(),
            )

            val recovering = openStore()
            assertEquals(
                1,
                recovering.initialRecoveryReport.quarantinedExtents,
            )
            assertTrue(recovering.committedExtents().isEmpty())
            assertFalse(extentFile(original.extentId).exists())

            val repaired = recovering.writeExtent(originalSpec) {
                write(bytes)
            }
            assertEquals(original.extentId, repaired.extentId)
            assertEquals(1, recovering.committedExtents().size)
            recovering.close()

            val verified = openStore()
            assertEquals(
                1,
                verified.initialRecoveryReport.verifiedPublishedExtents,
            )
            assertEquals(
                listOf(original.extentId),
                verified.committedExtents().map(CommittedExtent::extentId),
            )
            verified.close()
        }

    @Test
    fun quarantinedExtentRejectsIdentityMutationBeforeCreatingTempFile() =
        runBlocking {
            val originalBytes = "immutable-a".encodeToByteArray()
            val changedBytes = "immutable-b".encodeToByteArray()
            val originalSpec = spec(
                id = "stable-id",
                bytes = originalBytes,
                includeExpectedSha256 = false,
            )
            val first = openStore()
            val original = first.writeExtent(originalSpec) {
                write(originalBytes)
            }
            first.close()

            extentFile(original.extentId).writeBytes(
                "bad".encodeToByteArray(),
            )

            val recovering = openStore()
            assertEquals(
                1,
                recovering.initialRecoveryReport.quarantinedExtents,
            )

            expectThrows<ExtentConflictException> {
                recovering.writeExtent(
                    spec(
                        id = "stable-id",
                        bytes = changedBytes,
                        includeExpectedSha256 = false,
                    ),
                ) {
                    write(changedBytes)
                }
            }

            assertTrue(recovering.committedExtents().isEmpty())
            assertFalse(hasPartFiles())
            assertFalse(extentFile(original.extentId).exists())
            recovering.close()
        }

    @Test
    fun roomV1MigrationPreservesLegacyExtentInReservedAssetScope() =
        runBlocking {
            val extentId = ExtentId("legacy-v1")
            val bytes = "legacy-published-extent".encodeToByteArray()
            val digest = Sha256.digest(bytes)
            val file = extentFile(extentId)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)

            databaseFile.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
                db.execSQL(
                    """
                    CREATE TABLE extents (
                        extent_id TEXT NOT NULL PRIMARY KEY,
                        track_id TEXT NOT NULL,
                        representation_id TEXT NOT NULL,
                        media_start_us INTEGER,
                        media_end_us INTEGER,
                        byte_start INTEGER,
                        byte_end_exclusive INTEGER,
                        length INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        storage_path TEXT NOT NULL,
                        publication_state TEXT NOT NULL,
                        integrity_state TEXT NOT NULL,
                        quarantine_reason TEXT,
                        published_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX index_extents_track_id_representation_id " +
                        "ON extents(track_id, representation_id)",
                )
                db.execSQL(
                    "CREATE INDEX index_extents_publication_state_integrity_state " +
                        "ON extents(publication_state, integrity_state)",
                )
                db.execSQL(
                    """
                    CREATE TABLE extent_dependencies (
                        extent_id TEXT NOT NULL,
                        dependency_extent_id TEXT NOT NULL,
                        PRIMARY KEY(extent_id, dependency_extent_id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX index_extent_dependencies_dependency_extent_id " +
                        "ON extent_dependencies(dependency_extent_id)",
                )
                db.execSQL(
                    "CREATE TABLE room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO room_master_table " +
                        "(id, identity_hash) VALUES(42, ?)",
                    arrayOf("0bf05a98b8873edf57ddf005dbc42bdd"),
                )
                db.execSQL(
                    """
                    INSERT INTO extents(
                        extent_id,
                        track_id,
                        representation_id,
                        media_start_us,
                        media_end_us,
                        byte_start,
                        byte_end_exclusive,
                        length,
                        sha256,
                        storage_path,
                        publication_state,
                        integrity_state,
                        quarantine_reason,
                        published_at_epoch_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                    """.trimIndent(),
                    arrayOf(
                        extentId.value,
                        "video",
                        "v1",
                        0L,
                        10_000_000L,
                        0L,
                        bytes.size.toLong(),
                        bytes.size.toLong(),
                        digest.hex,
                        ExtentPathLayout(root).finalRelativePath(extentId),
                        "PUBLISHED",
                        "VALID",
                        1L,
                    ),
                )
                db.version = 1
            }

            val migrated = openStore()
            assertEquals(
                1,
                migrated.initialRecoveryReport.verifiedPublishedExtents,
            )
            val committed = migrated.committedExtents().single()
            assertEquals(extentId, committed.extentId)
            assertTrue(committed.mediaAssetId.isLegacyUnscoped)
            assertEquals(
                MediaAssetId.LEGACY_UNSCOPED_VALUE,
                committed.mediaAssetId.value,
            )
            migrated.close()
        }

    private suspend fun openStore(): ExtentStore =
        ExtentStore.openAndroidForTest(
            context = context,
            rootDirectory = root,
            databaseFile = databaseFile,
        )

    private fun extentFile(extentId: ExtentId): File =
        File(
            root,
            ExtentPathLayout(root).finalRelativePath(extentId),
        )

    private fun hasPartFiles(): Boolean =
        root.exists() &&
            root.walkTopDown().any {
                it.isFile && it.name.endsWith(".part")
            }

    private fun spec(
        id: String,
        bytes: ByteArray,
        includeExpectedSha256: Boolean = true,
    ): ExtentSpec =
        ExtentSpec(
            mediaAssetId = MediaAssetId("asset-test"),
            extentId = ExtentId(id),
            trackId = "video",
            representationId = "v1",
            mediaStartUs = 0,
            mediaEndUs = 10_000_000,
            byteStart = 0,
            byteEndExclusive = bytes.size.toLong(),
            expectedLength = bytes.size.toLong(),
            expectedSha256 = if (includeExpectedSha256) {
                Sha256.digest(bytes)
            } else {
                null
            },
        )
}

private suspend inline fun <reified T : Throwable> expectThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw AssertionError(
            "expected " + T::class.java.name +
                ", got " + error::class.java.name,
            error,
        )
    }

    throw AssertionError("expected " + T::class.java.name)
}
