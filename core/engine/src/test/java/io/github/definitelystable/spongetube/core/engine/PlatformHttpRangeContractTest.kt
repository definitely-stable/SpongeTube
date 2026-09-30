package io.github.definitelystable.spongetube.core.engine

import io.github.definitelystable.spongetube.core.engine.recovery.RangeProtocolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class PlatformHttpRangeContractTest {
    private fun validate(
        range: String?,
        length: String? = "1000",
        start: Long = 100L,
        end: Long = 1100L,
        total: Long = 4096L,
    ): RangeProtocolKind? = validatePlatformPartialRange(
        start = start, endExclusive = end, resourceLength = total,
        contentRange = range, contentLength = length,
    )

    @Test
    fun validPartialRangeWithExplicitAndWildcardTotal() {
        assertNull(validate("bytes 100-1099/4096"))
        assertNull(validate("bytes 100-1099/*"))
        assertNull(validate("bytes 100-1099/4096", null))
    }

    @Test
    fun missingAndMalformedRangeAreNotEquivalent() {
        assertEquals(RangeProtocolKind.CONTENT_RANGE_MISSING, validate(null))
        assertEquals(RangeProtocolKind.CONTENT_RANGE_MISMATCH, validate("100-1099/4096"))
        assertEquals(RangeProtocolKind.CONTENT_RANGE_MISMATCH, validate("bytes 100-1099/garbage"))
    }

    @Test
    fun mismatchInStartEndOrResourceTotalFailsClosed() {
        for (range in listOf(
            "bytes 99-1099/4096",
            "bytes 100-1100/4096",
            "bytes 100-1099/4097",
        )) {
            assertEquals(RangeProtocolKind.CONTENT_RANGE_MISMATCH, validate(range))
        }
    }

    @Test
    fun invalidDeclaredBodyLengthFailsClosed() {
        for (length in listOf("-1", "garbage", "999", "1001")) {
            assertEquals(
                RangeProtocolKind.RESPONSE_LENGTH_MISMATCH,
                validate("bytes 100-1099/4096", length),
            )
        }
    }
}
