package io.github.definitelystable.spongetube.core.storage

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

internal data class StoredExtent(
    val extentId: ExtentId,
    val trackId: String,
    val representationId: String,
    val mediaStartUs: Long?,
    val mediaEndUs: Long?,
    val byteStart: Long?,
    val byteEndExclusive: Long?,
    val dependencyExtentIds: List<ExtentId>,
    val length: Long,
    val sha256: Sha256Digest,
    val storagePath: String,
    val publicationState: ExtentPublicationState,
    val integrityState: ExtentIntegrityState,
    val quarantineReason: ExtentQuarantineReason?,
)

internal interface ExtentMetadataStore : Closeable {
    suspend fun assertWritable(
        spec: ExtentSpec,
        storagePath: String,
    )

    suspend fun publish(extent: StoredExtent)

    suspend fun snapshot(): List<StoredExtent>

    suspend fun quarantine(
        extentId: ExtentId,
        reason: ExtentQuarantineReason,
    )
}

internal interface ExtentDurabilityOps {
    fun ensureDirectory(directory: File)

    fun syncAndClose(output: FileOutputStream)

    fun installAtomicallyNoReplace(source: File, destination: File)

    fun deleteDurably(file: File)
}

internal enum class ExtentFaultPoint {
    AFTER_RECEIVING,
    AFTER_SEAL,
    AFTER_VERIFY,
    AFTER_DURABLE_BEFORE_PUBLISH,
    AFTER_PUBLISH,
}

internal fun interface ExtentFaultInjector {
    fun hit(point: ExtentFaultPoint)

    companion object {
        val NONE = ExtentFaultInjector { }
    }
}

internal class SimulatedProcessCrash(
    val point: ExtentFaultPoint,
) : RuntimeException("simulated process crash at $point")
