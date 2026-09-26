package io.github.definitelystable.spongetube.core.engine.delivery

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-level compatibility proof for the strict `Retry-After` / HTTP-date
 * parser (M2-D, `.work/milestones/M2.md` section 20).
 *
 * This test carries no lab arguments and therefore runs in the plain
 * `:core:engine:connectedDebugAndroidTest` suite on every API, including API
 * 23: a parser that silently depended on an API-24+ platform method would fail
 * here with `NoSuchMethodError` instead of only in a filtered scenario run.
 */
@RunWith(AndroidJUnit4::class)
class RetryAfterAndroidTest {
    /** 2026-09-26T00:00:00Z, a Saturday; the reference instant of the M2-D fixtures. */
    private val reference2026 = 1_790_380_800_000L

    /** 1994-11-06T08:49:37Z, the RFC 9110 example instant (a Sunday). */
    private val exampleEpochMs = 784_111_777_000L

    private fun assertMalformed(raw: String?) {
        assertEquals(RetryAfterObservation.MALFORMED, RetryAfter.parse(raw, reference2026))
    }

    @Test
    fun delaySecondsAreClassifiedWithoutAClockDomain() {
        assertEquals(RetryAfterObservation.ABSENT, RetryAfter.parse(null, reference2026))

        val zero = RetryAfter.parse("0", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, zero.rawKind)
        assertEquals(0L, zero.delaySeconds)
        assertNull(zero.notBeforeUtcEpochMs)

        val padded = RetryAfter.parse(" 120\t", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, padded.rawKind)
        assertEquals(120L, padded.delaySeconds)
        assertEquals(120_000L, RetryAfter.delayMs(padded))

        val leadingZeros = RetryAfter.parse("007", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, leadingZeros.rawKind)
        assertEquals(7L, leadingZeros.delaySeconds)

        listOf("", " \t ", "-1", "+10", "1.5", "abc", "1 0").forEach(::assertMalformed)
    }

    @Test
    fun delaySecondsOverflowIsMalformedAndNeverClamped() {
        val maximum = RetryAfter.parse("9223372036854775", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, maximum.rawKind)
        assertEquals(RetryAfter.MAX_DELAY_SECONDS, maximum.delaySeconds)
        assertEquals(9_223_372_036_854_775_000L, RetryAfter.delayMs(maximum))

        assertMalformed("9223372036854776")
        assertMalformed("99999999999999999999999")
    }

    @Test
    fun imfFixdateRfc850AndAsctimeResolveTheSameInstant() {
        listOf(
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994",
        ).forEach { raw ->
            val observation = RetryAfter.parse(raw, reference2026)
            assertEquals(RetryAfterKind.HTTP_DATE, observation.rawKind)
            assertEquals(exampleEpochMs, observation.notBeforeUtcEpochMs)
            assertNull(observation.delaySeconds)
        }
    }

    @Test
    fun rfc850TwoDigitYearFollowsTheReferenceYearWindow() {
        assertMalformed("Thursday, 01-Jan-76 00:00:00 GMT")

        val year2076 = RetryAfter.parse("Wednesday, 01-Jan-76 00:00:00 GMT", reference2026)
        assertEquals(RetryAfterKind.HTTP_DATE, year2076.rawKind)
        assertEquals(3_345_062_400_000L, year2076.notBeforeUtcEpochMs)

        val year1977 = RetryAfter.parse("Saturday, 01-Jan-77 00:00:00 GMT", reference2026)
        assertEquals(RetryAfterKind.HTTP_DATE, year1977.rawKind)
        assertEquals(220_924_800_000L, year1977.notBeforeUtcEpochMs)
    }

    @Test
    fun invalidWeekdayAndGrammarVectorsAreMalformed() {
        listOf(
            "Mon, 06 Nov 1994 08:49:37 GMT",
            "Sun, 31 Feb 2026 00:00:00 GMT",
            "Sun, 06 Nov 1994 24:00:00 GMT",
            "Sun, 06 Nov 1994 08:49:60 GMT",
            "Sun, 06 Nov 1994 08:49:37 gmt",
            "Sun, 06 Nov 1994 08:49:37 UTC",
            "Sun, 6 Nov 1994 08:49:37 GMT",
            "sun, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 nov 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 08:49:37",
        ).forEach(::assertMalformed)
    }

    @Test
    fun leapDaysFollowTheGregorianRule() {
        assertEquals(
            RetryAfterKind.HTTP_DATE,
            RetryAfter.parse("Thu, 29 Feb 2024 00:00:00 GMT", reference2026).rawKind,
        )
        assertMalformed("Sat, 29 Feb 2025 00:00:00 GMT")
        assertEquals(
            RetryAfterKind.HTTP_DATE,
            RetryAfter.parse("Tue, 29 Feb 2000 00:00:00 GMT", reference2026).rawKind,
        )
        assertMalformed("Thu, 29 Feb 1900 00:00:00 GMT")
    }

    @Test
    fun waitMsUsesOnlyTheProviderWallClockDomain() {
        val delay = RetryAfter.parse("2", reference2026)
        assertEquals(2_000L, RetryAfter.waitMs(delay, reference2026))
        assertEquals(2_000L, RetryAfter.waitMs(delay, 0L))

        val scheduled = RetryAfter.parse("Sun, 06 Nov 1994 08:49:37 GMT", reference2026)
        assertEquals(2_000L, RetryAfter.waitMs(scheduled, exampleEpochMs - 2_000L))
        assertEquals(0L, RetryAfter.waitMs(scheduled, exampleEpochMs))
        assertEquals(0L, RetryAfter.waitMs(scheduled, exampleEpochMs + 60_000L))

        assertNull(RetryAfter.waitMs(RetryAfterObservation.ABSENT, reference2026))
        assertNull(RetryAfter.waitMs(RetryAfterObservation.MALFORMED, reference2026))
    }
}
