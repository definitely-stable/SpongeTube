package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File

internal class RoomExtentMetadataStore private constructor(
    private val database: ExtentDatabase,
) : ExtentMetadataStore {
    private val dao = database.extentDao()

    override suspend fun publish(extent: StoredExtent) {
        val entity = ExtentEntity(
            extentId = extent.extentId.value,
            trackId = extent.trackId,
            representationId = extent.representationId,
            mediaStartUs = extent.mediaStartUs,
            mediaEndUs = extent.mediaEndUs,
            byteStart = extent.byteStart,
            byteEndExclusive = extent.byteEndExclusive,
            length = extent.length,
            sha256 = extent.sha256.hex,
            storagePath = extent.storagePath,
            publicationState = ExtentPublicationState.PUBLISHED.name,
            integrityState = ExtentIntegrityState.VALID.name,
            quarantineReason = null,
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

    override suspend fun snapshot(): List<StoredExtent> {
        val dependenciesByExtent = dao.allDependencies()
            .groupBy(ExtentDependencyEntity::extentId)
            .mapValues { (_, rows) ->
                rows.map { ExtentId(it.dependencyExtentId) }
            }

        return dao.allExtents().map { entity ->
            StoredExtent(
                extentId = ExtentId(entity.extentId),
                trackId = entity.trackId,
                representationId = entity.representationId,
                mediaStartUs = entity.mediaStartUs,
                mediaEndUs = entity.mediaEndUs,
                byteStart = entity.byteStart,
                byteEndExclusive = entity.byteEndExclusive,
                dependencyExtentIds = dependenciesByExtent[entity.extentId].orEmpty(),
                length = entity.length,
                sha256 = Sha256Digest(entity.sha256),
                storagePath = entity.storagePath,
                publicationState = ExtentPublicationState.valueOf(entity.publicationState),
                integrityState = ExtentIntegrityState.valueOf(entity.integrityState),
                quarantineReason = entity.quarantineReason?.let(
                    ExtentQuarantineReason::valueOf,
                ),
            )
        }
    }

    override suspend fun quarantine(
        extentId: ExtentId,
        reason: ExtentQuarantineReason,
    ) {
        dao.quarantine(extentId.value, reason.name)
    }

    override fun close() {
        database.close()
    }

    companion object {
        fun open(
            context: Context,
            databaseFile: File,
        ): RoomExtentMetadataStore {
            val database = Room.databaseBuilder(
                context.applicationContext,
                ExtentDatabase::class.java,
                databaseFile.absolutePath,
            )
                .setDriver(AndroidSQLiteDriver())
                .build()

            return RoomExtentMetadataStore(database)
        }
    }
}
