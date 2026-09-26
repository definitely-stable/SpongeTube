package io.github.definitelystable.spongetube.medialab;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * IMF-fixdate formatting for deterministic provider responses (RFC 9110 5.6.7).
 * The lab runs on the host JDK, so java.time is used here; this is simulator
 * semantics and never a live-provider date source.
 */
final class HttpDates {

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    private HttpDates() {
    }

    /** Formats a UTC epoch instant as an IMF-fixdate, e.g. "Fri, 25 Sep 2026 12:00:02 GMT". */
    static String imfFixdate(long utcEpochMs) {
        ZonedDateTime utc = Instant.ofEpochMilli(utcEpochMs).atZone(ZoneOffset.UTC);
        return IMF_FIXDATE.format(utc);
    }
}
