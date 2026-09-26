package io.github.definitelystable.spongetube.medialab;

/**
 * One provider-plane fault or refresh event of provider-fault-events-v1. Only
 * normalized fields are recorded: request URLs, raw Retry-After values and
 * other raw header values are never retained.
 *
 * <p>Events created by {@link ProviderSimulator} carry no identity yet;
 * {@link ProviderFaultRecorder} assigns {@code sequence} and {@code faultId}
 * before the document is rewritten.
 */
record ProviderFaultEvent(
        long sequence,
        long hostMonotonicNs,
        String faultId,
        String requestCorrelationId,
        RequestKind requestKind,
        FaultKind faultKind,
        Integer statusCode,
        RetryAfter retryAfter,
        ProviderSignal providerSignal,
        String bindingRevision,
        String providerBindingGeneration,
        Long providerWallClockUtcEpochMs) {

    enum RequestKind {
        MEDIA,
        REFRESH
    }

    enum FaultKind {
        HTTP_403_BARE,
        HTTP_429,
        BINDING_STALE,
        REFRESH_SUCCEEDED,
        REFRESH_INCOMPATIBLE,
        REFRESH_FAILED
    }

    enum ProviderSignal {
        NONE,
        BINDING_STALE_CONFIRMED
    }

    /** Normalized Retry-After observation; the raw header value is never retained. */
    record RetryAfter(String rawKind, Long delaySeconds, Long notBeforeUtcEpochMs) {

        static final RetryAfter ABSENT = new RetryAfter("ABSENT", null, null);
        static final RetryAfter MALFORMED = new RetryAfter("MALFORMED", null, null);

        static RetryAfter delaySeconds(long seconds) {
            return new RetryAfter("DELAY_SECONDS", seconds, null);
        }

        static RetryAfter httpDate(long notBeforeUtcEpochMs) {
            return new RetryAfter("HTTP_DATE", null, notBeforeUtcEpochMs);
        }

        String toJson() {
            return "{"
                    + Json.quote("rawKind") + ":" + Json.quote(rawKind) + ","
                    + Json.quote("delaySeconds") + ":" + nullable(delaySeconds) + ","
                    + Json.quote("notBeforeUtcEpochMs") + ":" + nullable(notBeforeUtcEpochMs)
                    + "}";
        }

        private static String nullable(Long value) {
            return value == null ? "null" : Long.toString(value);
        }
    }

    static ProviderFaultEvent media(
            long requestCorrelationId,
            long hostMonotonicNs,
            FaultKind faultKind,
            int statusCode,
            RetryAfter retryAfter,
            ProviderSignal providerSignal,
            String bindingRevision,
            String providerBindingGeneration,
            long providerWallClockUtcEpochMs) {
        return new ProviderFaultEvent(
                0,
                hostMonotonicNs,
                null,
                Long.toString(requestCorrelationId),
                RequestKind.MEDIA,
                faultKind,
                statusCode,
                retryAfter,
                providerSignal,
                bindingRevision,
                providerBindingGeneration,
                providerWallClockUtcEpochMs);
    }

    static ProviderFaultEvent refresh(
            long requestCorrelationId,
            long hostMonotonicNs,
            FaultKind faultKind,
            int statusCode,
            String providerBindingGeneration,
            long providerWallClockUtcEpochMs) {
        return new ProviderFaultEvent(
                0,
                hostMonotonicNs,
                null,
                Long.toString(requestCorrelationId),
                RequestKind.REFRESH,
                faultKind,
                statusCode,
                null,
                null,
                null,
                providerBindingGeneration,
                providerWallClockUtcEpochMs);
    }

    ProviderFaultEvent withIdentity(long sequence, String faultId) {
        return new ProviderFaultEvent(
                sequence,
                hostMonotonicNs,
                faultId,
                requestCorrelationId,
                requestKind,
                faultKind,
                statusCode,
                retryAfter,
                providerSignal,
                bindingRevision,
                providerBindingGeneration,
                providerWallClockUtcEpochMs);
    }

    String toJson() {
        return "{"
                + Json.quote("sequence") + ":" + sequence + ","
                + Json.quote("hostMonotonicNs") + ":" + hostMonotonicNs + ","
                + Json.quote("faultId") + ":" + Json.quote(faultId) + ","
                + Json.quote("requestCorrelationId") + ":" + Json.quote(requestCorrelationId) + ","
                + Json.quote("requestKind") + ":" + Json.quote(requestKind.name()) + ","
                + Json.quote("faultKind") + ":" + Json.quote(faultKind.name()) + ","
                + Json.quote("statusCode") + ":" + nullable(statusCode) + ","
                + Json.quote("retryAfter") + ":" + (retryAfter == null ? "null" : retryAfter.toJson()) + ","
                + Json.quote("providerSignal") + ":"
                + (providerSignal == null ? "null" : Json.quote(providerSignal.name())) + ","
                + Json.quote("bindingRevision") + ":" + Json.quote(bindingRevision) + ","
                + Json.quote("providerBindingGeneration") + ":"
                + Json.quote(providerBindingGeneration) + ","
                + Json.quote("providerWallClockUtcEpochMs") + ":" + nullable(providerWallClockUtcEpochMs)
                + "}";
    }

    private static String nullable(Integer value) {
        return value == null ? "null" : Integer.toString(value);
    }

    private static String nullable(Long value) {
        return value == null ? "null" : Long.toString(value);
    }
}
