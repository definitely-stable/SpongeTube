package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
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
    fun roomMetadataUsesExplicitTruncateFullDurability() = runBlocking {
        val first = openStore()
        val firstDurability = first.metadataDurability
        assertEquals("truncate", firstDurability.journalMode.lowercase())
        assertEquals(2, firstDurability.synchronous)
        assertTrue(firstDurability.busyTimeoutMs >= 3_000L)
        assertTrue(firstDurability.meetsM1DurabilityPolicy)
        first.close()

        val reopened = openStore()
        val reopenedDurability = reopened.metadataDurability
        assertEquals("truncate", reopenedDurability.journalMode.lowercase())
        assertEquals(2, reopenedDurability.synchronous)
        assertTrue(reopenedDurability.meetsM1DurabilityPolicy)
        reopened.close()
    }

    @Test
    fun opaqueReadHandleSurvivesReopenAndReturnsExactBytes() = runBlocking {
        val bytes = "android-read-surface".encodeToByteArray()
        val first = openStore()
        val committed = first.writeExtent(spec("android-read", bytes)) {
            write(bytes)
        }
        first.close()

        val reopened = openStore()
        val handle = checkNotNull(reopened.openRead(committed.extentId))
        val actual = ByteArray(bytes.size)

        assertEquals(bytes.size, handle.readAt(0, actual))
        assertEquals(bytes.toList(), actual.toList())
        assertEquals(-1, handle.readAt(bytes.size.toLong(), actual))

        expectThrows<ExtentConflictException> {
            reopened.close()
        }

        handle.close()
        reopened.close()
    }

    @Test
    fun metadataFullMapsToNoSpaceWithoutMaskingConstraints() {
        val full = metadataStorageFailureOrNull(
            operation = "metadata-publish",
            cause = IllegalStateException(
                "room wrapper",
                SQLiteFullException("database or disk is full"),
            ),
        )
        assertEquals(ExtentStorageFailureKind.NO_SPACE, full?.kind)
        assertEquals("metadata-publish", full?.operation)

        val diskIo = metadataStorageFailureOrNull(
            operation = "metadata-publish",
            cause = SQLiteDiskIOException("disk I/O error"),
        )
        assertEquals(ExtentStorageFailureKind.IO, diskIo?.kind)

        val constraint = metadataStorageFailureOrNull(
            operation = "metadata-publish",
            cause = SQLiteConstraintException("constraint"),
        )
        assertEquals(null, constraint)
    }

    @Test
    fun storageFailureClassifiesEnospc() {
        val failure = storageFailure(
            operation = "test-write",
            cause = ErrnoException("write", OsConstants.ENOSPC),
        )
        assertEquals(ExtentStorageFailureKind.NO_SPACE, failure.kind)
        assertEquals("test-write", failure.operation)
    }

    @Test
    fun storageFailureClassifiesEdquotAsNoSpace() {
        val failure = storageFailure(
            operation = "test-quota",
            cause = ErrnoException("write", OsConstants.EDQUOT),
        )
        assertEquals(ExtentStorageFailureKind.NO_SPACE, failure.kind)
        assertEquals("test-quota", failure.operation)
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
        assertEquals(null, reopened.openRead(committed.extentId))
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
    fun sameExtentIdCannotBeReboundAcrossAssets() = runBlocking {
        val bytes = "globally-unique-extent".encodeToByteArray()
        val store = openStore()
        val first = spec(
            id = "global-id",
            bytes = bytes,
        )

        store.writeExtent(first) {
            write(bytes)
        }

        expectThrows<ExtentConflictException> {
            store.writeExtent(
                first.copy(
                    mediaAssetId = MediaAssetId("asset-other"),
                ),
            ) {
                write(bytes)
            }
        }

        val committed = store.committedExtents().single()
        assertEquals(MediaAssetId("asset-test"), committed.mediaAssetId)
        store.close()
    }

    @Test
    fun roomV1MigrationDiscardsUnscopedCacheAndAllowsNamedRewrite() =
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
                    arrayOf<Any?>("0bf05a98b8873edf57ddf005dbc42bdd"),
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
                    arrayOf<Any?>(
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
                migrated.initialRecoveryReport.deletedOrphanFiles,
            )
            assertEquals(
                0,
                migrated.initialRecoveryReport.verifiedPublishedExtents,
            )
            assertTrue(migrated.committedExtents().isEmpty())
            assertFalse(extentFile(extentId).exists())

            val rewritten = migrated.writeExtent(
                spec(
                    id = extentId.value,
                    bytes = bytes,
                ),
            ) {
                write(bytes)
            }

            assertEquals(extentId, rewritten.extentId)
            assertEquals(MediaAssetId("asset-test"), rewritten.mediaAssetId)
            assertTrue(extentFile(extentId).isFile)
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
