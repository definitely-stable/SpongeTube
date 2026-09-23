package io.github.definitelystable.spongetube.core.storage

import java.io.IOException

@JvmInline
value class MediaAssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "media asset id must not be blank" }
        require(value.length <= 256) { "media asset id must be <= 256 characters" }
    }

    val isLegacyUnscoped: Boolean
        get() = value == LEGACY_UNSCOPED_VALUE

    override fun toString(): String = value

    companion object {
        const val LEGACY_UNSCOPED_VALUE = "__legacy_unscoped__"
    }
}

@JvmInline
value class ExtentId(val value: String) {
    init {
        require(value.isNotBlank()) { "extent id must not be blank" }
        require(value.length <= 256) { "extent id must be <= 256 characters" }
    }

    override fun toString(): String = value
}

@JvmInline
value class Sha256Digest(val hex: String) {
    init {
        require(SHA256_REGEX.matches(hex)) {
            "sha256 must be 64 lowercase hexadecimal characters"
        }
    }

    override fun toString(): String = hex

    private companion object {
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

data class ExtentSpec(
    val mediaAssetId: MediaAssetId,
    val extentId: ExtentId,
    val trackId: String,
    val representationId: String,
    val mediaStartUs: Long?,
    val mediaEndUs: Long?,
    val byteStart: Long?,
    val byteEndExclusive: Long?,
    val dependencyExtentIds: List<ExtentId> = emptyList(),
    val expectedLength: Long,
    val expectedSha256: Sha256Digest? = null,
) {
    init {
        require(!mediaAssetId.isLegacyUnscoped) {
            "legacy unscoped media asset id is reserved for migration"
        }
        require(trackId.isNotBlank()) { "trackId must not be blank" }
        require(representationId.isNotBlank()) { "representationId must not be blank" }
        require(expectedLength > 0) { "expectedLength must be > 0" }

        val hasMediaStart = mediaStartUs != null
        val hasMediaEnd = mediaEndUs != null
        require(hasMediaStart == hasMediaEnd) {
            "mediaStartUs and mediaEndUs must both be null or both be present"
        }
        if (mediaStartUs != null && mediaEndUs != null) {
            require(mediaStartUs >= 0) { "mediaStartUs must be >= 0" }
            require(mediaEndUs > mediaStartUs) {
                "mediaEndUs must be greater than mediaStartUs"
            }
        }

        val hasByteStart = byteStart != null
        val hasByteEnd = byteEndExclusive != null
        require(hasByteStart == hasByteEnd) {
            "byteStart and byteEndExclusive must both be null or both be present"
        }
        if (byteStart != null && byteEndExclusive != null) {
            require(byteStart >= 0) { "byteStart must be >= 0" }
            require(byteEndExclusive > byteStart) {
                "byteEndExclusive must be greater than byteStart"
            }
            require(byteEndExclusive - byteStart == expectedLength) {
                "byte range length must equal expectedLength"
            }
        }

        require(dependencyExtentIds.none { it == extentId }) {
            "an extent cannot depend on itself"
        }
        require(dependencyExtentIds.distinct().size == dependencyExtentIds.size) {
            "dependency extent ids must be unique"
        }
    }
}

interface ExtentSink {
    suspend fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    )
}

internal enum class ExtentPublicationState {
    PUBLISHED,
    QUARANTINED,
}

internal enum class ExtentIntegrityState {
    VALID,
    CORRUPT,
}

internal enum class ExtentQuarantineReason {
    MISSING_FILE,
    LENGTH_MISMATCH,
    SHA256_MISMATCH,
    UNEXPECTED_PATH,
}

enum class ExtentLifecycleState {
    RECEIVING,
    SEALED,
    VERIFIED,
    DURABLE,
    PUBLISHED,
}

data class ExtentLifecycleEvent(
    val extentId: ExtentId,
    val state: ExtentLifecycleState,
    val actualLength: Long? = null,
    val sha256: Sha256Digest? = null,
)

fun interface ExtentLifecycleListener {
    fun onEvent(event: ExtentLifecycleEvent)
}

data class CommittedExtent(
    val mediaAssetId: MediaAssetId,
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
)

data class RecoveryReport(
    val deletedPartFiles: Int,
    val deletedOrphanFiles: Int,
    val deletedQuarantinedFiles: Int,
    val quarantinedExtents: Int,
    val verifiedPublishedExtents: Int,
) {
    companion object {
        val EMPTY = RecoveryReport(
            deletedPartFiles = 0,
            deletedOrphanFiles = 0,
            deletedQuarantinedFiles = 0,
            quarantinedExtents = 0,
            verifiedPublishedExtents = 0,
        )
    }
}

open class ExtentStoreException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class ExtentIntegrityException(
    val extentId: ExtentId,
    val expectedLength: Long,
    val receivedLength: Long,
    val persistedLength: Long,
    val expectedSha256: Sha256Digest?,
    val receivedSha256: Sha256Digest,
    val persistedSha256: Sha256Digest,
) : ExtentStoreException(
    "Extent $extentId failed integrity verification: " +
        "length received=$receivedLength persisted=$persistedLength " +
        "expected=$expectedLength; sha256 received=$receivedSha256 " +
        "persisted=$persistedSha256 origin=" +
        (expectedSha256?.toString() ?: "<not-provided>"),
)

class ExtentConflictException(message: String) : ExtentStoreException(message)

class ExtentClosedException : ExtentStoreException("extent store is closed")
