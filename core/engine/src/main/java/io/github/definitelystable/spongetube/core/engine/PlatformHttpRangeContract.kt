package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.RangeProtocolKind

/**
 * Pure candidate response contract, shared between live callback and host
 * falsification tests. No platform object, URL, token or production API leaks.
 */
internal fun validatePlatformPartialRange(
    start: Long,
    endExclusive: Long,
    resourceLength: Long,
    contentRange: String?,
    contentLength: String?,
): RangeProtocolKind? {
    val rawRange = contentRange ?: return RangeProtocolKind.CONTENT_RANGE_MISSING
    val parsed = HttpRangeFetchExecutor.ContentRange.parse(rawRange)
        ?: return RangeProtocolKind.CONTENT_RANGE_MISMATCH
    if (parsed.start != start || parsed.endInclusive != endExclusive - 1 ||
        (parsed.total != null && parsed.total != resourceLength)
    ) return RangeProtocolKind.CONTENT_RANGE_MISMATCH
    if (contentLength != null &&
        contentLength.trim().toLongOrNull() != endExclusive - start
    ) return RangeProtocolKind.RESPONSE_LENGTH_MISMATCH
    return null
}
