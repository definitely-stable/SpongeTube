package io.github.definitelystable.spongetube.core.engine.route

import io.github.definitelystable.spongetube.core.storage.CommittedExtent

/**
 * Canonical portable projection of persisted extent identity for M2-F4.
 *
 * This deliberately contains only immutable storage identity/content fields.
 * Filesystem paths, timestamps, platform handles and mutable route/delivery
 * state are excluded. The host oracle compares these objects byte-for-byte
 * (after JSON canonicalization) across route transitions.
 */
internal fun List<CommittedExtent>.toM2PersistedExtentIdentity(): List<Map<String, Any?>> =
    sortedBy { it.extentId.value }.map { extent ->
        linkedMapOf(
            "mediaAssetId" to extent.mediaAssetId.value,
            "extentId" to extent.extentId.value,
            "trackId" to extent.trackId,
            "representationId" to extent.representationId,
            "mediaStartUs" to extent.mediaStartUs,
            "mediaEndUs" to extent.mediaEndUs,
            "byteStart" to extent.byteStart,
            "byteEndExclusive" to extent.byteEndExclusive,
            "dependencyExtentIds" to extent.dependencyExtentIds.map { it.value },
            "length" to extent.length,
            "sha256" to extent.sha256.hex,
        )
    }
