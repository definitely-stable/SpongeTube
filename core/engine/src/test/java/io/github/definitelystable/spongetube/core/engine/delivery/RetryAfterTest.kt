package io.github.definitelystable.spongetube.core.engine.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RetryAfterTest {
    /** 2026-09-26T00:00:00Z, a Saturday; the reference instant of M2-D fixtures. */
    private val reference2026 = 1_790_380_800_000L

    /** 1994-11-06T08:49:37Z, the RFC 9110 example instant (a Sunday). */
    private val exampleEpochMs = 784_111_777_000L

    private fun assertMalformed(raw: String?) {
        assertEquals(RetryAfterObservation.MALFORMED, RetryAfter.parse(raw, reference2026), raw)
    }

    @Test
    fun absentIsAbsentAndBlankIsMalformed() {
        assertEquals(RetryAfterObservation.ABSENT, RetryAfter.parse(null, reference2026))
        assertMalformed("")
        assertMalformed(" \t ")
    }

    @Test
    fun delaySecondsAreParsedWithLeadingZerosAndNoClockDomain() {
        val zero = RetryAfter.parse("0", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, zero.rawKind)
        assertEquals(0L, zero.delaySeconds)
        assertNull(zero.notBeforeUtcEpochMs)

        val plain = RetryAfter.parse("120", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, plain.rawKind)
        assertEquals(120L, plain.delaySeconds)
        assertNull(plain.notBeforeUtcEpochMs)

        val padded = RetryAfter.parse(" 120\t", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, padded.rawKind)
        assertEquals(120L, padded.delaySeconds)

        val leadingZeros = RetryAfter.parse("007", reference2026)
        assertEquals(RetryAfterKind.DELAY_SECONDS, leadingZeros.rawKind)
        assertEquals(7L, leadingZeros.delaySeconds)
    }

    @Test
    fun malformedDelaySecondsVectors() {
        listOf(
            "-1",
            "+10",
            "1.5",
            "abc",
            "1 0",
            "\u0661\u0662",
        ).forEach(::assertMalformed)
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
            assertEquals(RetryAfterKind.HTTP_DATE, observation.rawKind, raw)
            assertEquals(exampleEpochMs, observation.notBeforeUtcEpochMs, raw)
            assertNull(observation.delaySeconds, raw)
        }
    }

    @Test
    fun rfc850TwoDigitYearFollowsTheFiftyYearWindow() {
        assertMalformed("Thursday, 01-Jan-76 00:00:00 GMT")

        val year2076 = RetryAfter.parse("Wednesday, 01-Jan-76 00:00:00 GMT", reference2026)
        assertEquals(RetryAfterKind.HTTP_DATE, year2076.rawKind)
        assertEquals(3_345_062_400_000L, year2076.notBeforeUtcEpochMs)

        val year1977 = RetryAfter.parse("Saturday, 01-Jan-77 00:00:00 GMT", reference2026)
        assertEquals(RetryAfterKind.HTTP_DATE, year1977.rawKind)
        assertEquals(220_924_800_000L, year1977.notBeforeUtcEpochMs)
    }

    @Test
    fun invalidHttpDateVectorsAreMalformed() {
        listOf(
            "Sun, 31 Feb 2026 00:00:00 GMT",
            "Mon, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 24:00:00 GMT",
            "Sun, 06 Nov 1994 08:49:60 GMT",
            "Sun, 06 Nov 1994 08:49:37 gmt",
            "Sun, 06 Nov 1994 08:49:37 UTC",
            "Sun, 6 Nov 1994 08:49:37 GMT",
            "sun, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 nov 1994 08:49:37 GMT",
        ).forEach(::assertMalformed)
    }

    @Test
    fun leapDaysFollowTheGregorianRule() {
        val leap2024 = RetryAfter.parse("Thu, 29 Feb 2024 00:00:00 GMT", reference2026)
        assertEquals(RetryAfterKind.HTTP_DATE, leap2024.rawKind)

        assertMalformed("Sat, 29 Feb 2025 00:00:00 GMT")

        val leap2000 = RetryAfter.parse("Tue, 29 Feb 2000 00:00:00 GMT", reference2026)
        assertEquals(RetryAfterKind.HTTP_DATE, leap2000.rawKind)

        assertMalformed("Thu, 29 Feb 1900 00:00:00 GMT")
    }

    @Test
    fun httpDateParserIsStrictAboutAlreadyTrimmedInput() {
        assertEquals(
            exampleEpochMs,
            HttpDate.parseEpochMs("Sun, 06 Nov 1994 08:49:37 GMT", reference2026),
        )
        assertNull(HttpDate.parseEpochMs("Sun, 06 Nov 1994 08:49:37 GMT ", reference2026))
        assertNull(HttpDate.parseEpochMs("Sun, 06 Nov 1994 08:49:37", reference2026))
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

    @Test
    fun delayMsIsDefinedOnlyForDelaySeconds() {
        assertThrows<IllegalArgumentException> {
            RetryAfter.delayMs(RetryAfterObservation.ABSENT)
        }
        assertThrows<IllegalArgumentException> {
            RetryAfter.delayMs(RetryAfterObservation.MALFORMED)
        }
        assertThrows<IllegalArgumentException> {
            RetryAfter.delayMs(
                RetryAfterObservation(RetryAfterKind.HTTP_DATE, notBeforeUtcEpochMs = 0L),
            )
        }
    }

    @Test
    fun observationsRejectInconsistentFieldCombinations() {
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(RetryAfterKind.DELAY_SECONDS)
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(RetryAfterKind.DELAY_SECONDS, delaySeconds = -1L)
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(
                RetryAfterKind.DELAY_SECONDS,
                delaySeconds = RetryAfter.MAX_DELAY_SECONDS + 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(
                RetryAfterKind.DELAY_SECONDS,
                delaySeconds = 1L,
                notBeforeUtcEpochMs = 1L,
            )
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(RetryAfterKind.HTTP_DATE)
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(
                RetryAfterKind.HTTP_DATE,
                delaySeconds = 1L,
                notBeforeUtcEpochMs = 1L,
            )
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(RetryAfterKind.ABSENT, delaySeconds = 1L)
        }
        assertThrows<IllegalArgumentException> {
            RetryAfterObservation(RetryAfterKind.MALFORMED, notBeforeUtcEpochMs = 1L)
        }
    }

    @Test
    fun contractTableMatchesTheHostParserClassification() {
        val table = listOf(
            null to RetryAfterKind.ABSENT,
            "" to RetryAfterKind.MALFORMED,
            " \t " to RetryAfterKind.MALFORMED,
            "0" to RetryAfterKind.DELAY_SECONDS,
            "120" to RetryAfterKind.DELAY_SECONDS,
            " 120\t" to RetryAfterKind.DELAY_SECONDS,
            "007" to RetryAfterKind.DELAY_SECONDS,
            "-1" to RetryAfterKind.MALFORMED,
            "+10" to RetryAfterKind.MALFORMED,
            "1.5" to RetryAfterKind.MALFORMED,
            "abc" to RetryAfterKind.MALFORMED,
            "1 0" to RetryAfterKind.MALFORMED,
            "\u0661\u0662" to RetryAfterKind.MALFORMED,
            "120\n" to RetryAfterKind.MALFORMED,
            "9223372036854775" to RetryAfterKind.DELAY_SECONDS,
            "9223372036854776" to RetryAfterKind.MALFORMED,
            "99999999999999999999999" to RetryAfterKind.MALFORMED,
            "Sun, 06 Nov 1994 08:49:37 GMT" to RetryAfterKind.HTTP_DATE,
            "Sunday, 06-Nov-94 08:49:37 GMT" to RetryAfterKind.HTTP_DATE,
            "Sun Nov  6 08:49:37 1994" to RetryAfterKind.HTTP_DATE,
            "Thursday, 01-Jan-76 00:00:00 GMT" to RetryAfterKind.MALFORMED,
            "Wednesday, 01-Jan-76 00:00:00 GMT" to RetryAfterKind.HTTP_DATE,
            "Saturday, 01-Jan-77 00:00:00 GMT" to RetryAfterKind.HTTP_DATE,
            "Sun, 31 Feb 2026 00:00:00 GMT" to RetryAfterKind.MALFORMED,
            "Mon, 06 Nov 1994 08:49:37 GMT" to RetryAfterKind.MALFORMED,
            "Sun, 06 Nov 1994 24:00:00 GMT" to RetryAfterKind.MALFORMED,
            "Sun, 06 Nov 1994 08:49:60 GMT" to RetryAfterKind.MALFORMED,
            "Sun, 06 Nov 1994 08:49:37 gmt" to RetryAfterKind.MALFORMED,
            "Sun, 06 Nov 1994 08:49:37 UTC" to RetryAfterKind.MALFORMED,
            "Sun, 6 Nov 1994 08:49:37 GMT" to RetryAfterKind.MALFORMED,
            "sun, 06 Nov 1994 08:49:37 GMT" to RetryAfterKind.MALFORMED,
            "Sun, 06 nov 1994 08:49:37 GMT" to RetryAfterKind.MALFORMED,
            "Thu, 29 Feb 2024 00:00:00 GMT" to RetryAfterKind.HTTP_DATE,
            "Sat, 29 Feb 2025 00:00:00 GMT" to RetryAfterKind.MALFORMED,
            "Tue, 29 Feb 2000 00:00:00 GMT" to RetryAfterKind.HTTP_DATE,
            "Thu, 29 Feb 1900 00:00:00 GMT" to RetryAfterKind.MALFORMED,
        )

        table.forEach { (raw, expected) ->
            assertEquals(expected, RetryAfter.parse(raw, reference2026).rawKind, "raw=$raw")
        }
    }
}
