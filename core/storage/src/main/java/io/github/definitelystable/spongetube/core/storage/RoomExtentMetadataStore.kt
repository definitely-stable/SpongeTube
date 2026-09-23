package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File

internal class RoomExtentMetadataStore private constructor(
    private val database: ExtentDatabase,
    val durability: ExtentMetadataDurability,
) : ExtentMetadataStore {
    private val dao = database.extentDao()

    override suspend fun assertWritable(
        spec: ExtentSpec,
        storagePath: String,
    ) = metadataStorageOperation("metadata-preflight") {
        dao.assertWritable(
            candidate = spec.toPreflight(storagePath),
        )
    }

    override suspend fun assertPublishable(
        extent: StoredExtent,
    ) = metadataStorageOperation("metadata-assert-publishable") {
        val entity = extent.toEntity(
            publishedAtEpochMs = 0L,
        )
        dao.assertPublishable(
            entity = entity,
            dependencyIds = extent.dependencyExtentIds
                .map(ExtentId::value)
                .sorted(),
        )
    }

    override suspend fun publish(
        extent: StoredExtent,
    ) = metadataStorageOperation("metadata-publish") {
        val entity = extent.toEntity(
            publishedAtEpochMs = System.currentTimeMillis(),
        )
        val dependencies = extent.dependencyExtentIds.map { dependency ->
            ExtentDependencyEntity(
                extentId = extent.extentId.value,
                dependencyExtentId = dependency.value,
            )
        }

        dao.publish(entity, dependencies)
    }

    override suspend fun snapshot(): List<StoredExtent> =
        metadataStorageOperation("metadata-snapshot") {
            val snapshot = dao.snapshot()
            val dependenciesByExtent = snapshot.dependencies
                .groupBy(ExtentDependencyEntity::extentId)
                .mapValues { (_, rows) ->
                    rows.map { ExtentId(it.dependencyExtentId) }
                }

            snapshot.extents.map { entity ->
                entity.toStoredExtent(
                    dependencies =
                        dependenciesByExtent[entity.extentId].orEmpty(),
                )
            }
        }

    override suspend fun extentById(
        extentId: ExtentId,
    ): StoredExtent? =
        metadataStorageOperation("metadata-lookup") {
            val snapshot = dao.snapshotById(extentId.value)
                ?: return@metadataStorageOperation null
            snapshot.extent.toStoredExtent(
                dependencies = snapshot.dependencyIds.map(::ExtentId),
            )
        }

    override suspend fun quarantine(
        extentId: ExtentId,
        reason: ExtentQuarantineReason,
    ) = metadataStorageOperation("metadata-quarantine") {
        dao.quarantine(extentId.value, reason.name)
    }

    override fun close() {
        database.close()
    }

    companion object {
        suspend fun open(
            context: Context,
            databaseFile: File,
        ): RoomExtentMetadataStore {
            var database: ExtentDatabase? = null

            try {
                val openedDatabase = Room.databaseBuilder(
                    context.applicationContext,
                    ExtentDatabase::class.java,
                    databaseFile.absolutePath,
                )
                    .setDriver(AndroidSQLiteDriver())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .build()
                database = openedDatabase

                val durability = openedDatabase.readDurability()
                if (!durability.meetsM1DurabilityPolicy) {
                    throw ExtentMetadataDurabilityException(durability)
                }
                return RoomExtentMetadataStore(
                    database = openedDatabase,
                    durability = durability,
                )
            } catch (error: Throwable) {
                val failure =
                    metadataStorageFailureOrNull(
                        operation = "metadata-open",
                        cause = error,
                    ) ?: error
                try {
                    database?.close()
                } catch (closeError: Throwable) {
                    failure.addSuppressed(closeError)
                }
                throw failure
            }
        }
    }
}

private fun ExtentSpec.toPreflight(
    storagePath: String,
): ExtentWritePreflight =
    ExtentWritePreflight(
        extentId = extentId.value,
        trackId = trackId,
        representationId = representationId,
        mediaStartUs = mediaStartUs,
        mediaEndUs = mediaEndUs,
        byteStart = byteStart,
        byteEndExclusive = byteEndExclusive,
        dependencyIds = dependencyExtentIds
            .map(ExtentId::value)
            .sorted(),
        length = expectedLength,
        expectedSha256 = expectedSha256?.hex,
        storagePath = storagePath,
    )

private fun StoredExtent.toEntity(
    publishedAtEpochMs: Long,
): ExtentEntity =
    ExtentEntity(
        extentId = extentId.value,
        trackId = trackId,
        representationId = representationId,
        mediaStartUs = mediaStartUs,
        mediaEndUs = mediaEndUs,
        byteStart = byteStart,
        byteEndExclusive = byteEndExclusive,
        length = length,
        sha256 = sha256.hex,
        storagePath = storagePath,
        publicationState = ExtentPublicationState.PUBLISHED.name,
        integrityState = ExtentIntegrityState.VALID.name,
        quarantineReason = null,
        publishedAtEpochMs = publishedAtEpochMs,
    )

private fun ExtentEntity.toStoredExtent(
    dependencies: List<ExtentId>,
): StoredExtent =
    StoredExtent(
        extentId = ExtentId(extentId),
        trackId = trackId,
        representationId = representationId,
        mediaStartUs = mediaStartUs,
        mediaEndUs = mediaEndUs,
        byteStart = byteStart,
        byteEndExclusive = byteEndExclusive,
        dependencyExtentIds = dependencies,
        length = length,
        sha256 = Sha256Digest(sha256),
        storagePath = storagePath,
        publicationState = ExtentPublicationState.valueOf(publicationState),
        integrityState = ExtentIntegrityState.valueOf(integrityState),
        quarantineReason = quarantineReason?.let(
            ExtentQuarantineReason::valueOf,
        ),
    )

private suspend fun ExtentDatabase.readDurability(): ExtentMetadataDurability =
    useWriterConnection { connection ->
        val journalMode = connection.usePrepared("PRAGMA journal_mode") {
            check(it.step()) { "PRAGMA journal_mode returned no row" }
            it.getText(0)
        }
        val synchronous = connection.usePrepared("PRAGMA synchronous") {
            check(it.step()) { "PRAGMA synchronous returned no row" }
            it.getLong(0).toInt()
        }
        val busyTimeoutMs = connection.usePrepared("PRAGMA busy_timeout") {
            check(it.step()) { "PRAGMA busy_timeout returned no row" }
            it.getLong(0)
        }

        ExtentMetadataDurability(
            journalMode = journalMode,
            synchronous = synchronous,
            busyTimeoutMs = busyTimeoutMs,
        )
    }

private suspend inline fun <T> metadataStorageOperation(
    operation: String,
    block: suspend () -> T,
): T {
    try {
        return block()
    } catch (error: Throwable) {
        throw metadataStorageFailureOrNull(
            operation = operation,
            cause = error,
        ) ?: error
    }
}
