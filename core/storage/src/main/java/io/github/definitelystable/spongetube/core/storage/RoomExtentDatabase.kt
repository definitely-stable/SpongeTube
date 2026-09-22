package io.github.definitelystable.spongetube.core.storage

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
import androidx.room3.Upsert

@Entity(
    tableName = "extents",
    indices = [
        Index(value = ["track_id", "representation_id"]),
        Index(value = ["publication_state", "integrity_state"]),
    ],
)
internal data class ExtentEntity(
    @PrimaryKey
    @ColumnInfo(name = "extent_id")
    val extentId: String,
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

@Dao
internal abstract class ExtentDao {
    @Upsert
    abstract suspend fun upsertExtent(entity: ExtentEntity)

    @Query("DELETE FROM extent_dependencies WHERE extent_id = :extentId")
    abstract suspend fun deleteDependencies(extentId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertDependencies(
        dependencies: List<ExtentDependencyEntity>,
    )

    @Transaction
    open suspend fun publish(
        entity: ExtentEntity,
        dependencies: List<ExtentDependencyEntity>,
    ) {
        upsertExtent(entity)
        deleteDependencies(entity.extentId)
        if (dependencies.isNotEmpty()) {
            insertDependencies(dependencies)
        }
    }

    @Query("SELECT * FROM extents")
    abstract suspend fun allExtents(): List<ExtentEntity>

    @Query("SELECT * FROM extent_dependencies")
    abstract suspend fun allDependencies(): List<ExtentDependencyEntity>

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

@Database(
    entities = [
        ExtentEntity::class,
        ExtentDependencyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class ExtentDatabase : RoomDatabase() {
    abstract fun extentDao(): ExtentDao
}
