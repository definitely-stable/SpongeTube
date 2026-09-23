package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.MediaAssetId
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import java.security.MessageDigest
import java.util.Collections

/**
 * One FetchUnit of a playback resource: the exact immutable ExtentSpec that
 * the FetchBroker publishes for [fetchKey], placed at [resourceStart] inside
 * the resource byte space.
 *
 * [transportKey] is an opaque locator resolved by the transport session; it is
 * never storage or single-flight identity.
 */
@SpongeBridgeApi
class PlaybackFetchUnit(
    val fetchKey: FetchKey,
    val extentSpec: ExtentSpec,
    val resourceStart: Long,
    val transportKey: String,
) {
    init {
        require(resourceStart >= 0) { "resourceStart must be >= 0" }
        require(transportKey.isNotBlank()) { "transportKey must not be blank" }
        extentSpec.byteStart?.let { byteStart ->
            require(byteStart == resourceStart) {
                "ranged extent byteStart must equal its resource offset"
            }
        }
    }

    val extentId: ExtentId
        get() = extentSpec.extentId

    val length: Long
        get() = extentSpec.expectedLength

    val resourceEndExclusive: Long
        get() = resourceStart + length
}

/** A byte resource addressable by the player, e.g. an MPD, init or segment. */
@SpongeBridgeApi
sealed class PlaybackResource(
    val key: String,
) {
    init {
        require(key.isNotBlank()) { "resource key must not be blank" }
        require(!key.startsWith("/")) { "resource key must be relative" }
    }

    abstract val length: Long
}

/**
 * Resource served from bytes packaged with the plan (M1-E D3: the F1 MPD).
 * Construction verifies exact length and SHA-256; mismatches fail closed, so
 * the manifest never needs an origin request.
 */
@SpongeBridgeApi
class InlinePlaybackResource(
    key: String,
    bytes: ByteArray,
    expectedLength: Long,
    expectedSha256: Sha256Digest,
) : PlaybackResource(key) {
    private val bytes: ByteArray = bytes.copyOf()

    override val length: Long = expectedLength

    init {
        require(this.bytes.size.toLong() == expectedLength) {
            "inline resource $key length mismatch: expected=$expectedLength " +
                "actual=${this.bytes.size}"
        }
        val actual = sha256Hex(this.bytes)
        require(actual == expectedSha256.hex) {
            "inline resource $key sha256 mismatch: expected=$expectedSha256 " +
                "actual=$actual"
        }
    }

    internal fun copyInto(
        position: Long,
        target: ByteArray,
        offset: Int,
        count: Int,
    ): Int {
        val available = (length - position).coerceAtMost(count.toLong()).toInt()
        if (available <= 0) {
            return -1
        }
        System.arraycopy(bytes, position.toInt(), target, offset, available)
        return available
    }
}

/**
 * Resource whose bytes are exactly tiled by immutable FetchUnits. Either one
 * whole-resource unit (`byteStart == null`) or contiguous ranged units.
 */
@SpongeBridgeApi
class ExtentPlaybackResource(
    key: String,
    override val length: Long,
    units: List<PlaybackFetchUnit>,
) : PlaybackResource(key) {
    val units: List<PlaybackFetchUnit> = Collections.unmodifiableList(
        units.sortedBy(PlaybackFetchUnit::resourceStart),
    )

    init {
        require(length > 0) { "resource length must be > 0" }
        require(this.units.isNotEmpty()) { "resource $key has no fetch units" }
        var expectedStart = 0L
        for (unit in this.units) {
            require(unit.resourceStart == expectedStart) {
                "resource $key units must tile contiguously from 0"
            }
            if (unit.extentSpec.byteStart == null) {
                require(this.units.size == 1) {
                    "a whole-resource unit cannot be combined with ranged units"
                }
            }
            expectedStart = unit.resourceEndExclusive
        }
        require(expectedStart == length) {
            "resource $key units cover $expectedStart of $length bytes"
        }
    }

    internal fun unitAt(position: Long): PlaybackFetchUnit {
        require(position in 0 until length) { "position outside resource" }
        var low = 0
        var high = units.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val unit = units[middle]
            when {
                position < unit.resourceStart -> high = middle - 1
                position >= unit.resourceEndExclusive -> low = middle + 1
                else -> return unit
            }
        }
        error("tiled resource has no unit at $position")
    }
}

/**
 * Exact resource -> FetchUnit layout of the selected playback plan.
 *
 * Ownership split (M1-E F5): the plan owns layout and identity; CoverageIndex
 * owns readiness; ExtentStore owns bytes; FetchBroker owns remote misses.
 */
@SpongeBridgeApi
class PlaybackPlan(
    val mediaAssetId: MediaAssetId,
    resources: List<PlaybackResource>,
) {
    val resources: List<PlaybackResource> =
        Collections.unmodifiableList(resources.toList())

    private val byKey: Map<String, PlaybackResource>
    private val unitsByExtentId: Map<ExtentId, PlaybackFetchUnit>
    private val unitsByFetchKey: Map<FetchKey, PlaybackFetchUnit>
    private val resourceLengthByFetchKey: Map<FetchKey, Long>

    init {
        require(!mediaAssetId.isLegacyUnscoped) {
            "legacy unscoped media asset cannot be a playback plan"
        }
        byKey = this.resources.associateBy(PlaybackResource::key)
        require(byKey.size == this.resources.size) { "duplicate resource key" }

        val extentUnits = linkedMapOf<ExtentId, PlaybackFetchUnit>()
        val fetchUnits = linkedMapOf<FetchKey, PlaybackFetchUnit>()
        val lengths = linkedMapOf<FetchKey, Long>()
        for (resource in this.resources) {
            if (resource !is ExtentPlaybackResource) {
                continue
            }
            for (unit in resource.units) {
                require(unit.extentSpec.mediaAssetId == mediaAssetId) {
                    "unit ${unit.extentId} belongs to another media asset"
                }
                require(extentUnits.put(unit.extentId, unit) == null) {
                    "duplicate extent id in plan: ${unit.extentId}"
                }
                require(fetchUnits.put(unit.fetchKey, unit) == null) {
                    "duplicate fetch key in plan: ${unit.fetchKey}"
                }
                lengths[unit.fetchKey] = resource.length
            }
        }
        for (unit in extentUnits.values) {
            for (dependency in unit.extentSpec.dependencyExtentIds) {
                require(dependency in extentUnits) {
                    "dependency $dependency of ${unit.extentId} is not in the plan"
                }
            }
        }
        unitsByExtentId = Collections.unmodifiableMap(extentUnits)
        unitsByFetchKey = Collections.unmodifiableMap(fetchUnits)
        resourceLengthByFetchKey = Collections.unmodifiableMap(lengths)
    }

    fun resource(key: String): PlaybackResource? = byKey[key]

    fun unitForExtent(extentId: ExtentId): PlaybackFetchUnit? =
        unitsByExtentId[extentId]

    fun unitForFetchKey(fetchKey: FetchKey): PlaybackFetchUnit? =
        unitsByFetchKey[fetchKey]

    internal fun transportResourceLength(fetchKey: FetchKey): Long? =
        resourceLengthByFetchKey[fetchKey]
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val alphabet = "0123456789abcdef"
    val chars = CharArray(digest.size * 2)
    digest.forEachIndexed { index, value ->
        val unsigned = value.toInt() and 0xff
        chars[index * 2] = alphabet[unsigned ushr 4]
        chars[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return chars.concatToString()
}
