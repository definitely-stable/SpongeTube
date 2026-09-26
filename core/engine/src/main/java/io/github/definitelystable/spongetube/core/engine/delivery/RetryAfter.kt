package io.github.definitelystable.spongetube.core.engine.delivery

/** Normalized classification of one raw `Retry-After` observation. */
internal enum class RetryAfterKind {
    ABSENT,
    DELAY_SECONDS,
    HTTP_DATE,
    MALFORMED,
}

/**
 * Normalized `Retry-After` observation (RFC 9110 10.2.3, M2.md 10.2).
 *
 * [DELAY_SECONDS] is a duration with no clock domain; [HTTP_DATE] is a
 * PROVIDER_WALL_CLOCK instant. [ABSENT] and [MALFORMED] carry nothing. The
 * raw header value is never retained.
 */
internal data class RetryAfterObservation(
    val rawKind: RetryAfterKind,
    val delaySeconds: Long? = null,
    val notBeforeUtcEpochMs: Long? = null,
) {
    init {
        when (rawKind) {
            RetryAfterKind.DELAY_SECONDS -> {
                require(notBeforeUtcEpochMs == null) {
                    "DELAY_SECONDS carries no PROVIDER_WALL_CLOCK instant"
                }
                val seconds = delaySeconds
                require(seconds != null && seconds in 0..RetryAfter.MAX_DELAY_SECONDS) {
                    "DELAY_SECONDS requires delaySeconds in 0..${RetryAfter.MAX_DELAY_SECONDS}"
                }
            }
            RetryAfterKind.HTTP_DATE -> {
                require(delaySeconds == null) { "HTTP_DATE carries no duration" }
                require(notBeforeUtcEpochMs != null) {
                    "HTTP_DATE requires a PROVIDER_WALL_CLOCK instant"
                }
            }
            RetryAfterKind.ABSENT, RetryAfterKind.MALFORMED -> {
                require(delaySeconds == null && notBeforeUtcEpochMs == null) {
                    "$rawKind carries no observation"
                }
            }
        }
    }

    companion object {
        val ABSENT = RetryAfterObservation(RetryAfterKind.ABSENT)
        val MALFORMED = RetryAfterObservation(RetryAfterKind.MALFORMED)
    }
}

/**
 * Strict `Retry-After` normalization (RFC 9110 10.2.3).
 *
 * The classification matches `scripts/measurement/m2_contracts.parse_retry_after`
 * for every well-formed input; see [HttpDate] for the date grammar. Parsing is
 * pure and deterministic: no locale, time zone or platform date API is used.
 */
internal object RetryAfter {
    /** delay-seconds above this cannot be expressed in milliseconds: MALFORMED. */
    const val MAX_DELAY_SECONDS: Long = Long.MAX_VALUE / 1_000

    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * `null` is ABSENT. Otherwise only leading/trailing SP and HTAB are
     * trimmed; an empty remainder is MALFORMED. `^[0-9]+$` is delay-seconds
     * (leading zeros allowed, `0` valid, overflow or a value above
     * [MAX_DELAY_SECONDS] is MALFORMED and never clamped). Anything else is
     * parsed as an HTTP-date with [referenceUtcEpochMs] resolving the RFC 850
     * two-digit year.
     */
    fun parse(raw: String?, referenceUtcEpochMs: Long): RetryAfterObservation {
        if (raw == null) {
            return RetryAfterObservation.ABSENT
        }
        val value = raw.trim(' ', '\t')
        if (value.isEmpty()) {
            return RetryAfterObservation.MALFORMED
        }
        if (value.all { it in '0'..'9' }) {
            val seconds = parseDelaySeconds(value) ?: return RetryAfterObservation.MALFORMED
            return RetryAfterObservation(RetryAfterKind.DELAY_SECONDS, delaySeconds = seconds)
        }
        val notBefore = HttpDate.parseEpochMs(value, referenceUtcEpochMs)
            ?: return RetryAfterObservation.MALFORMED
        return RetryAfterObservation(RetryAfterKind.HTTP_DATE, notBeforeUtcEpochMs = notBefore)
    }

    /** DELAY_SECONDS only; overflow-free by [MAX_DELAY_SECONDS]. */
    fun delayMs(observation: RetryAfterObservation): Long =
        requireDelaySeconds(observation) * MILLIS_PER_SECOND

    /**
     * Provider-directed wait in ms, computed only inside PROVIDER_WALL_CLOCK:
     * DELAY_SECONDS -> `delaySeconds * 1000`; HTTP_DATE ->
     * `max(0, notBefore - now)` (an elapsed instant waits 0 ms);
     * ABSENT/MALFORMED -> null.
     */
    fun waitMs(observation: RetryAfterObservation, providerNowUtcEpochMs: Long): Long? =
        when (observation.rawKind) {
            RetryAfterKind.DELAY_SECONDS -> requireDelaySeconds(observation) * MILLIS_PER_SECOND
            RetryAfterKind.HTTP_DATE -> {
                val notBefore = requireNotNull(observation.notBeforeUtcEpochMs)
                if (notBefore <= providerNowUtcEpochMs) 0L else notBefore - providerNowUtcEpochMs
            }
            RetryAfterKind.ABSENT, RetryAfterKind.MALFORMED -> null
        }

    /**
     * Digit loop with explicit overflow detection: returns null as soon as the
     * value would exceed [MAX_DELAY_SECONDS], so no silent wrap or exception
     * drives the classification.
     */
    private fun parseDelaySeconds(value: String): Long? {
        var result = 0L
        for (digit in value) {
            val numeric = digit - '0'
            if (result > (MAX_DELAY_SECONDS - numeric) / 10) {
                return null
            }
            result = result * 10 + numeric
        }
        return result
    }

    private fun requireDelaySeconds(observation: RetryAfterObservation): Long {
        require(observation.rawKind == RetryAfterKind.DELAY_SECONDS) {
            "only DELAY_SECONDS has a millisecond duration"
        }
        return requireNotNull(observation.delaySeconds)
    }
}

/**
 * Strict RFC 9110 5.6.7 HTTP-date parser: exact and case-sensitive, `GMT`
 * only, weekday must match the date. Supports IMF-fixdate, the obsolete
 * RFC 850 form and asctime. Converted to UTC epoch ms with Howard Hinnant's
 * days-from-civil algorithm using Kotlin integer math only; no `java.time`,
 * `Calendar`, `SimpleDateFormat` or API-24 `Math.floorDiv` (minSdk 23 has no
 * core-library desugaring).
 */
internal object HttpDate {
    private val IMF_FIXDATE = Regex(
        "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), ([0-9]{2}) ([A-Z][a-z]{2}) ([0-9]{4}) " +
            "([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$",
    )

    private val RFC_850 = Regex(
        "^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), " +
            "([0-9]{2})-([A-Z][a-z]{2})-([0-9]{2}) " +
            "([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$",
    )

    private val ASCTIME = Regex(
        "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) ([A-Z][a-z]{2}) ([ 0-9][0-9]) " +
            "([0-9]{2}):([0-9]{2}):([0-9]{2}) ([0-9]{4})$",
    )

    private val SHORT_WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private val LONG_WEEKDAYS =
        listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private val MONTHS =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private const val MILLIS_PER_DAY = 86_400_000L
    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * Parses one trimmed candidate [value] to UTC epoch ms, or null when it is
     * not a valid HTTP-date. [referenceUtcEpochMs] only resolves the RFC 850
     * two-digit year.
     */
    fun parseEpochMs(value: String, referenceUtcEpochMs: Long): Long? {
        IMF_FIXDATE.matchEntire(value)?.let { match ->
            val (weekday, day, month, year, hour, minute, second) = match.destructured
            return epochMsOrNull(
                weekday = weekday,
                day = day.toInt(),
                month = month,
                year = year.toInt(),
                hour = hour.toInt(),
                minute = minute.toInt(),
                second = second.toInt(),
                shortWeekday = true,
            )
        }
        RFC_850.matchEntire(value)?.let { match ->
            val (weekday, day, month, year, hour, minute, second) = match.destructured
            return epochMsOrNull(
                weekday = weekday,
                day = day.toInt(),
                month = month,
                year = rfc850Year(year.toInt(), referenceUtcEpochMs),
                hour = hour.toInt(),
                minute = minute.toInt(),
                second = second.toInt(),
                shortWeekday = false,
            )
        }
        ASCTIME.matchEntire(value)?.let { match ->
            val (weekday, month, day, hour, minute, second, year) = match.destructured
            return epochMsOrNull(
                weekday = weekday,
                day = day.trim().toInt(),
                month = month,
                year = year.toInt(),
                hour = hour.toInt(),
                minute = minute.toInt(),
                second = second.toInt(),
                shortWeekday = true,
            )
        }
        return null
    }

    private fun epochMsOrNull(
        weekday: String,
        day: Int,
        month: String,
        year: Int,
        hour: Int,
        minute: Int,
        second: Int,
        shortWeekday: Boolean,
    ): Long? {
        if (year < 1 || hour > 23 || minute > 59 || second > 59) {
            return null
        }
        val monthIndex = MONTHS.indexOf(month)
        if (monthIndex < 0) {
            return null
        }
        val monthNumber = monthIndex + 1
        if (day < 1 || day > daysInMonth(year, monthNumber)) {
            return null
        }
        val epochDays = daysFromCivil(year, monthNumber, day)
        val expectedWeekday = if (shortWeekday) {
            SHORT_WEEKDAYS[weekdayIndex(epochDays)]
        } else {
            LONG_WEEKDAYS[weekdayIndex(epochDays)]
        }
        if (weekday != expectedWeekday) {
            return null
        }
        val secondOfDay = hour * 3_600L + minute * 60L + second
        return epochDays * MILLIS_PER_DAY + secondOfDay * MILLIS_PER_SECOND
    }

    /**
     * RFC 9110 5.6.7 two-digit year: the century of the reference UTC year
     * plus `yy`; a result more than 50 years after the reference is read as
     * the most recent past year with those digits.
     */
    private fun rfc850Year(twoDigits: Int, referenceUtcEpochMs: Long): Int {
        val referenceYear = utcYear(referenceUtcEpochMs)
        val century = referenceYear - referenceYear % 100
        var year = century + twoDigits
        if (year > referenceYear + 50) {
            year -= 100
        }
        return year
    }

    /** UTC year of an epoch-ms instant (Howard Hinnant's civil_from_days). */
    private fun utcYear(utcEpochMs: Long): Int {
        val epochDays = utcEpochMs.floorDiv(MILLIS_PER_DAY)
        val z = epochDays + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val year = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = if (mp < 10) mp + 3 else mp - 9
        return (year + if (month <= 2) 1 else 0).toInt()
    }

    /** Howard Hinnant's days_from_civil; 1970-01-01 is day 0. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year.toLong()
        if (month <= 2) {
            y -= 1
        }
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yoe * 365 + yoe / 4 - yoe / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /** 0 = Monday ... 6 = Sunday; 1970-01-01 is a Thursday. */
    private fun weekdayIndex(epochDays: Long): Int =
        (epochDays + 3).mod(7L).toInt()

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeapYear(year)) 29 else 28
    }

    /** Proleptic Gregorian leap rule. */
    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}
