package io.github.definitelystable.spongetube.core.storage

import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.room3.Update

@Entity(
    tableName = "extents",
    indices = [
        Index(value = ["media_asset_id", "track_id", "representation_id"]),
        Index(value = ["publication_state", "integrity_state"]),
    ],
)
internal data class ExtentEntity(
    @PrimaryKey
    @ColumnInfo(name = "extent_id")
    val extentId: String,
    @ColumnInfo(
        name = "media_asset_id",
        defaultValue = "'__legacy_unscoped__'",
    )
    val mediaAssetId: String,
    @ColumnInfo(name = "track_id")
    val trackId: String,
    @ColumnInfo(name = "representation_id")
    val representationId: String,
    @ColumnInfo(name = "media_start_us")
    val mediaStartUs: Long?,
    @ColumnInfo(name = "media_end_us")
    val mediaEndUs: Long?,
    @ColumnInfo(name = "byte_start")
    val byteStart: Long?,
    @ColumnInfo(name = "byte_end_exclusive")
    val byteEndExclusive: Long?,
    val length: Long,
    val sha256: String,
    @ColumnInfo(name = "storage_path")
    val storagePath: String,
    @ColumnInfo(name = "publication_state")
    val publicationState: String,
    @ColumnInfo(name = "integrity_state")
    val integrityState: String,
    @ColumnInfo(name = "quarantine_reason")
    val quarantineReason: String?,
    @ColumnInfo(name = "published_at_epoch_ms")
    val publishedAtEpochMs: Long,
)

@Entity(
    tableName = "extent_dependencies",
    primaryKeys = ["extent_id", "dependency_extent_id"],
    indices = [Index(value = ["dependency_extent_id"])],
)
internal data class ExtentDependencyEntity(
    @ColumnInfo(name = "extent_id")
    val extentId: String,
    @ColumnInfo(name = "dependency_extent_id")
    val dependencyExtentId: String,
)

internal data class ExtentSnapshotRows(
    val extents: List<ExtentEntity>,
    val dependencies: List<ExtentDependencyEntity>,
)

internal data class ExtentWritePreflight(
    val mediaAssetId: String,
    val extentId: String,
    val trackId: String,
    val representationId: String,
    val mediaStartUs: Long?,
    val mediaEndUs: Long?,
    val byteStart: Long?,
    val byteEndExclusive: Long?,
    val dependencyIds: List<String>,
    val length: Long,
    val expectedSha256: String?,
    val storagePath: String,
)

@Dao
internal abstract class ExtentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertExtent(entity: ExtentEntity)

    @Update
    abstract suspend fun updateExtent(entity: ExtentEntity)

    @Query("SELECT * FROM extents WHERE extent_id = :extentId LIMIT 1")
    abstract suspend fun extentById(extentId: String): ExtentEntity?

    @Query(
        "SELECT dependency_extent_id FROM extent_dependencies " +
            "WHERE extent_id = :extentId ORDER BY dependency_extent_id",
    )
    abstract suspend fun dependencyIdsForExtent(
        extentId: String,
    ): List<String>

    @Query("DELETE FROM extent_dependencies WHERE extent_id = :extentId")
    abstract suspend fun deleteDependencies(extentId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertDependencies(
        dependencies: List<ExtentDependencyEntity>,
    )

    @Transaction
    open suspend fun assertWritable(candidate: ExtentWritePreflight) {
        val existing = extentById(candidate.extentId) ?: return
        validatePreflightCandidate(
            existing = existing,
            existingDependencyIds = dependencyIdsForExtent(candidate.extentId),
            candidate = candidate,
        )
    }

    @Transaction
    open suspend fun assertPublishable(
        entity: ExtentEntity,
        dependencyIds: List<String>,
    ) {
        val existing = extentById(entity.extentId) ?: return
        validateRepairCandidate(
            existing = existing,
            existingDependencyIds = dependencyIdsForExtent(entity.extentId),
            candidate = entity,
            candidateDependencyIds = dependencyIds,
        )
    }

    @Transaction
    open suspend fun publish(
        entity: ExtentEntity,
        dependencies: List<ExtentDependencyEntity>,
    ) {
        val existing = extentById(entity.extentId)
        if (existing == null) {
            insertExtent(entity)
        } else {
            validateRepairCandidate(
                existing = existing,
                existingDependencyIds = dependencyIdsForExtent(entity.extentId),
                candidate = entity,
                candidateDependencyIds = dependencies
                    .map(ExtentDependencyEntity::dependencyExtentId)
                    .sorted(),
            )
            updateExtent(entity)
        }

        deleteDependencies(entity.extentId)
        if (dependencies.isNotEmpty()) {
            insertDependencies(dependencies)
        }
    }

    @Query("SELECT * FROM extents")
    abstract suspend fun allExtents(): List<ExtentEntity>

    @Query("SELECT * FROM extent_dependencies")
    abstract suspend fun allDependencies(): List<ExtentDependencyEntity>

    @Transaction
    open suspend fun snapshot(): ExtentSnapshotRows =
        ExtentSnapshotRows(
            extents = allExtents(),
            dependencies = allDependencies(),
        )

    @Query(
        """
        UPDATE extents
        SET publication_state = 'QUARANTINED',
            integrity_state = 'CORRUPT',
            quarantine_reason = :reason
        WHERE extent_id = :extentId
        """,
    )
    abstract suspend fun quarantine(
        extentId: String,
        reason: String,
    )
}

private fun validatePreflightCandidate(
    existing: ExtentEntity,
    existingDependencyIds: List<String>,
    candidate: ExtentWritePreflight,
) {
    val immutableIdentityMatches =
        existing.extentId == candidate.extentId &&
            existing.mediaAssetId == candidate.mediaAssetId &&
            existing.trackId == candidate.trackId &&
            existing.representationId == candidate.representationId &&
            existing.mediaStartUs == candidate.mediaStartUs &&
            existing.mediaEndUs == candidate.mediaEndUs &&
            existing.byteStart == candidate.byteStart &&
            existing.byteEndExclusive == candidate.byteEndExclusive &&
            existing.length == candidate.length &&
            (
                candidate.expectedSha256 == null ||
                    existing.sha256 == candidate.expectedSha256
            ) &&
            existing.storagePath == candidate.storagePath &&
            existingDependencyIds.sorted() == candidate.dependencyIds.sorted()

    requireRepairableMatch(existing, immutableIdentityMatches)
}

private fun validateRepairCandidate(
    existing: ExtentEntity,
    existingDependencyIds: List<String>,
    candidate: ExtentEntity,
    candidateDependencyIds: List<String>,
) {
    val immutableIdentityMatches =
        existing.extentId == candidate.extentId &&
            existing.mediaAssetId == candidate.mediaAssetId &&
            existing.trackId == candidate.trackId &&
            existing.representationId == candidate.representationId &&
            existing.mediaStartUs == candidate.mediaStartUs &&
            existing.mediaEndUs == candidate.mediaEndUs &&
            existing.byteStart == candidate.byteStart &&
            existing.byteEndExclusive == candidate.byteEndExclusive &&
            existing.length == candidate.length &&
            existing.sha256 == candidate.sha256 &&
            existing.storagePath == candidate.storagePath &&
            existingDependencyIds.sorted() == candidateDependencyIds.sorted()

    requireRepairableMatch(existing, immutableIdentityMatches)
}

private fun requireRepairableMatch(
    existing: ExtentEntity,
    immutableIdentityMatches: Boolean,
) {
    val repairable =
        existing.publicationState == ExtentPublicationState.QUARANTINED.name &&
            existing.integrityState == ExtentIntegrityState.CORRUPT.name

    if (!repairable || !immutableIdentityMatches) {
        throw ExtentConflictException(
            "extent id is already bound to different or active immutable metadata: " +
                existing.extentId,
        )
    }
}

@Database(
    entities = [
        ExtentEntity::class,
        ExtentDependencyEntity::class,
    ],
    version = 2,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
    exportSchema = true,
)
internal abstract class ExtentDatabase : RoomDatabase() {
    abstract fun extentDao(): ExtentDao
}
