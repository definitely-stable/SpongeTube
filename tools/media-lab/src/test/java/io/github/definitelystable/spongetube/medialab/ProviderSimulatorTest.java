package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ProviderSimulatorTest {

    private static final long WALL_CLOCK = ProviderSimulator.DEFAULT_PROVIDER_WALL_CLOCK_EPOCH_MS;

    @Test
    void variantsAreBoundToExactlyOneFamily() {
        assertEquals(MediaLabProfile.N8, ProviderVariant.HTTP_403_BARE.family());
        assertEquals(MediaLabProfile.N9, ProviderVariant.HTTP_429_RETRY_AFTER_DELAY_SECONDS.family());
        assertEquals(MediaLabProfile.N9, ProviderVariant.HTTP_429_RETRY_AFTER_HTTP_DATE.family());
        assertEquals(MediaLabProfile.N9, ProviderVariant.HTTP_429_RETRY_AFTER_ABSENT.family());
        assertEquals(MediaLabProfile.N9, ProviderVariant.HTTP_429_RETRY_AFTER_MALFORMED.family());
        assertEquals(MediaLabProfile.N10, ProviderVariant.BINDING_EXPIRY_REFRESH.family());
        assertEquals(MediaLabProfile.N10, ProviderVariant.BINDING_REFRESH_INCOMPATIBLE.family());
        assertEquals(MediaLabProfile.N10, ProviderVariant.BINDING_REFRESH_FAILED.family());

        assertNull(ProviderVariant.parse(null));
        assertEquals(
                ProviderVariant.HTTP_403_BARE,
                ProviderVariant.parse("HTTP_403_BARE"));
        assertThrows(IllegalArgumentException.class, () -> ProviderVariant.parse("HTTP_418"));
        assertThrows(IllegalArgumentException.class, () -> ProviderVariant.parse("http_403_bare"));

        assertTrue(MediaLabProfile.N8.isProviderFamily());
        assertTrue(MediaLabProfile.N9.isProviderFamily());
        assertTrue(MediaLabProfile.N10.isProviderFamily());
        assertFalse(MediaLabProfile.N0.isProviderFamily());
        assertFalse(MediaLabProfile.N4R.isProviderFamily());
    }

    @Test
    void formatsImfFixdateWithMatchingWeekday() {
        assertEquals("Fri, 25 Sep 2026 12:00:02 GMT", HttpDates.imfFixdate(WALL_CLOCK + 2_000L));
        assertEquals(
                DayOfWeek.FRIDAY,
                Instant.ofEpochMilli(WALL_CLOCK + 2_000L).atZone(ZoneOffset.UTC).getDayOfWeek());
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", HttpDates.imfFixdate(0L));
    }

    @Test
    void n8RejectsEveryMediaGetWithBareForbidden() {
        ProviderSimulator simulator = simulator(ProviderVariant.HTTP_403_BARE);

        ProviderSimulator.MediaDecision first =
                simulator.mediaGet(11L, "gen-1", "binding-3", 100L);
        assertTrue(first.isFault());
        assertEquals(403, first.statusCode());
        assertEquals("provider_forbidden", first.errorCode());
        assertNull(first.retryAfterHeader());
        assertFalse(first.staleBindingHeader());
        assertEquals(
                ProviderFaultEvent.FaultKind.HTTP_403_BARE,
                first.faultEvent().faultKind());
        assertEquals(ProviderFaultEvent.RequestKind.MEDIA, first.faultEvent().requestKind());
        assertEquals(403, first.faultEvent().statusCode());
        assertNull(first.faultEvent().retryAfter());
        assertEquals(ProviderFaultEvent.ProviderSignal.NONE, first.faultEvent().providerSignal());
        assertEquals("binding-3", first.faultEvent().bindingRevision());
        assertEquals("gen-1", first.faultEvent().providerBindingGeneration());
        assertEquals(WALL_CLOCK, first.faultEvent().providerWallClockUtcEpochMs());
        assertEquals("11", first.faultEvent().requestCorrelationId());
        assertEquals(100L, first.faultEvent().hostMonotonicNs());

        ProviderSimulator.MediaDecision second =
                simulator.mediaGet(12L, "gen-1", "binding-0", 200L);
        assertTrue(second.isFault());
        assertEquals(403, second.statusCode());
        assertNull(second.faultEvent().bindingRevision());
        assertEquals(2, simulator.mediaGetCount());
    }

    @Test
    void n9InjectsRetryAfterOnlyOnFirstMediaGet() {
        Map<ProviderVariant, String> expectedHeaders = Map.of(
                ProviderVariant.HTTP_429_RETRY_AFTER_DELAY_SECONDS, "2",
                ProviderVariant.HTTP_429_RETRY_AFTER_HTTP_DATE,
                HttpDates.imfFixdate(WALL_CLOCK + 2_000L),
                ProviderVariant.HTTP_429_RETRY_AFTER_MALFORMED, "soon");
        List<ProviderVariant> variants = List.of(
                ProviderVariant.HTTP_429_RETRY_AFTER_DELAY_SECONDS,
                ProviderVariant.HTTP_429_RETRY_AFTER_HTTP_DATE,
                ProviderVariant.HTTP_429_RETRY_AFTER_ABSENT,
                ProviderVariant.HTTP_429_RETRY_AFTER_MALFORMED);

        for (ProviderVariant variant : variants) {
            ProviderSimulator simulator = simulator(variant);

            ProviderSimulator.MediaDecision first =
                    simulator.mediaGet(1L, "gen-1", null, 10L);
            assertTrue(first.isFault(), variant.name());
            assertEquals(429, first.statusCode());
            assertEquals("provider_rate_limited", first.errorCode());
            assertEquals(expectedHeaders.get(variant), first.retryAfterHeader(), variant.name());
            assertEquals(ProviderFaultEvent.FaultKind.HTTP_429, first.faultEvent().faultKind());
            assertEquals(ProviderFaultEvent.ProviderSignal.NONE, first.faultEvent().providerSignal());

            ProviderFaultEvent.RetryAfter retryAfter = first.faultEvent().retryAfter();
            switch (variant) {
                case HTTP_429_RETRY_AFTER_DELAY_SECONDS -> {
                    assertEquals("DELAY_SECONDS", retryAfter.rawKind());
                    assertEquals(2L, retryAfter.delaySeconds());
                    assertNull(retryAfter.notBeforeUtcEpochMs());
                }
                case HTTP_429_RETRY_AFTER_HTTP_DATE -> {
                    assertEquals("HTTP_DATE", retryAfter.rawKind());
                    assertNull(retryAfter.delaySeconds());
                    assertEquals(WALL_CLOCK + 2_000L, retryAfter.notBeforeUtcEpochMs());
                }
                case HTTP_429_RETRY_AFTER_ABSENT -> {
                    assertEquals("ABSENT", retryAfter.rawKind());
                    assertNull(retryAfter.delaySeconds());
                    assertNull(retryAfter.notBeforeUtcEpochMs());
                }
                default -> {
                    assertEquals("MALFORMED", retryAfter.rawKind());
                    assertNull(retryAfter.delaySeconds());
                    assertNull(retryAfter.notBeforeUtcEpochMs());
                }
            }

            ProviderSimulator.MediaDecision second =
                    simulator.mediaGet(2L, "gen-1", null, 20L);
            assertFalse(second.isFault(), variant.name());
            assertNull(second.faultEvent());
            assertEquals(2, simulator.mediaGetCount());
        }
    }

    @Test
    void n10RejectsStaleGenerationUntilRefreshSucceeds() {
        ProviderSimulator simulator = simulator(ProviderVariant.BINDING_EXPIRY_REFRESH);

        assertTrue(simulator.isIssuedGeneration("gen-1"));
        assertFalse(simulator.isIssuedGeneration("gen-2"));
        assertFalse(simulator.isIssuedGeneration("gen-0"));
        assertFalse(simulator.isIssuedGeneration("gen-01"));
        assertFalse(simulator.isIssuedGeneration(null));
        assertEquals(1, simulator.currentGeneration());

        ProviderSimulator.MediaDecision stale = simulator.mediaGet(1L, "gen-1", null, 10L);
        assertTrue(stale.isFault());
        assertEquals(403, stale.statusCode());
        assertEquals("binding_stale", stale.errorCode());
        assertTrue(stale.staleBindingHeader());
        assertEquals(ProviderFaultEvent.FaultKind.BINDING_STALE, stale.faultEvent().faultKind());
        assertEquals(
                ProviderFaultEvent.ProviderSignal.BINDING_STALE_CONFIRMED,
                stale.faultEvent().providerSignal());
        assertEquals("gen-1", stale.faultEvent().providerBindingGeneration());

        ProviderSimulator.RefreshDecision wrongGeneration =
                simulator.refresh(2L, "gen-2", null, 20L);
        assertEquals(409, wrongGeneration.statusCode());
        assertEquals(ProviderFaultEvent.FaultKind.REFRESH_FAILED, wrongGeneration.faultEvent().faultKind());
        assertEquals(ProviderFaultEvent.RequestKind.REFRESH, wrongGeneration.faultEvent().requestKind());
        assertEquals(409, wrongGeneration.faultEvent().statusCode());
        assertNull(wrongGeneration.faultEvent().providerBindingGeneration());
        assertEquals(1, simulator.currentGeneration());

        ProviderSimulator.RefreshDecision refreshed =
                simulator.refresh(3L, "gen-1", null, 30L);
        assertTrue(refreshed.successful());
        assertEquals(200, refreshed.statusCode());
        assertEquals(ProviderFaultEvent.FaultKind.REFRESH_SUCCEEDED, refreshed.faultEvent().faultKind());
        assertEquals("gen-2", refreshed.faultEvent().providerBindingGeneration());
        assertEquals(200, refreshed.faultEvent().statusCode());
        assertTrue(refreshed.bodyJson().contains("\"generation\":\"gen-2\""));
        assertTrue(refreshed.bodyJson().contains("\"/fixtures/F0/sample.bin\":256"));
        assertEquals(2, simulator.currentGeneration());
        assertTrue(simulator.isIssuedGeneration("gen-2"));

        assertFalse(simulator.mediaGet(4L, "gen-2", null, 40L).isFault());
        ProviderSimulator.MediaDecision staleAgain = simulator.mediaGet(5L, "gen-1", null, 50L);
        assertTrue(staleAgain.isFault());
        assertEquals(ProviderFaultEvent.FaultKind.BINDING_STALE, staleAgain.faultEvent().faultKind());
    }

    @Test
    void n10IncompatibleRefreshReportsLengthPlusOne() {
        ProviderSimulator simulator = simulator(ProviderVariant.BINDING_REFRESH_INCOMPATIBLE);

        ProviderSimulator.RefreshDecision refreshed =
                simulator.refresh(1L, "gen-1", null, 10L);

        assertTrue(refreshed.successful());
        assertEquals(ProviderFaultEvent.FaultKind.REFRESH_INCOMPATIBLE, refreshed.faultEvent().faultKind());
        assertEquals("gen-2", refreshed.faultEvent().providerBindingGeneration());
        assertTrue(refreshed.bodyJson().contains("\"generation\":\"gen-2\""));
        assertTrue(refreshed.bodyJson().contains("\"/fixtures/F0/sample.bin\":257"));
        assertTrue(refreshed.bodyJson().contains("\"/fixtures/F1/manifest.mpd\":13"));
        assertEquals(2, simulator.currentGeneration());
        assertFalse(simulator.mediaGet(2L, "gen-2", null, 20L).isFault());
    }

    @Test
    void n10FailedRefreshKeepsGeneration() {
        ProviderSimulator simulator = simulator(ProviderVariant.BINDING_REFRESH_FAILED);

        ProviderSimulator.RefreshDecision failed = simulator.refresh(1L, "gen-1", null, 10L);

        assertFalse(failed.successful());
        assertEquals(503, failed.statusCode());
        assertEquals(ProviderFaultEvent.FaultKind.REFRESH_FAILED, failed.faultEvent().faultKind());
        assertEquals(503, failed.faultEvent().statusCode());
        assertNull(failed.faultEvent().providerBindingGeneration());
        assertEquals(1, simulator.currentGeneration());
        assertFalse(simulator.isIssuedGeneration("gen-2"));
        assertTrue(simulator.mediaGet(2L, "gen-1", null, 20L).isFault());
    }

    @Test
    void refreshIsOnlySupportedByTheBindingFamily() {
        assertTrue(simulator(ProviderVariant.BINDING_EXPIRY_REFRESH).supportsRefresh());
        assertFalse(simulator(ProviderVariant.HTTP_403_BARE).supportsRefresh());
        assertFalse(simulator(ProviderVariant.HTTP_429_RETRY_AFTER_ABSENT).supportsRefresh());
        assertThrows(
                IllegalStateException.class,
                () -> simulator(ProviderVariant.HTTP_403_BARE).refresh(1L, "gen-1", null, 10L));
    }

    @Test
    void eventJsonCarriesEverySchemaKeyAndPreservesNulls() {
        ProviderFaultEvent media = ProviderFaultEvent.media(
                        7L,
                        123L,
                        ProviderFaultEvent.FaultKind.HTTP_403_BARE,
                        403,
                        null,
                        ProviderFaultEvent.ProviderSignal.NONE,
                        null,
                        "gen-1",
                        WALL_CLOCK)
                .withIdentity(1L, "fault-1");

        Map<String, Object> mediaJson = json(media.toJson());
        assertEquals(
                List.of(
                        "sequence",
                        "hostMonotonicNs",
                        "faultId",
                        "requestCorrelationId",
                        "requestKind",
                        "faultKind",
                        "statusCode",
                        "retryAfter",
                        "providerSignal",
                        "bindingRevision",
                        "providerBindingGeneration",
                        "providerWallClockUtcEpochMs"),
                List.copyOf(mediaJson.keySet()));
        assertEquals(1L, mediaJson.get("sequence"));
        assertEquals("fault-1", mediaJson.get("faultId"));
        assertEquals("7", mediaJson.get("requestCorrelationId"));
        assertEquals("MEDIA", mediaJson.get("requestKind"));
        assertEquals(403L, mediaJson.get("statusCode"));
        assertNull(mediaJson.get("retryAfter"));
        assertEquals("NONE", mediaJson.get("providerSignal"));
        assertNull(mediaJson.get("bindingRevision"));
        assertEquals("gen-1", mediaJson.get("providerBindingGeneration"));
        assertEquals(WALL_CLOCK, mediaJson.get("providerWallClockUtcEpochMs"));

        ProviderFaultEvent retryAfter = ProviderFaultEvent.media(
                        8L,
                        456L,
                        ProviderFaultEvent.FaultKind.HTTP_429,
                        429,
                        ProviderFaultEvent.RetryAfter.delaySeconds(2L),
                        ProviderFaultEvent.ProviderSignal.NONE,
                        "binding-9",
                        "gen-1",
                        WALL_CLOCK)
                .withIdentity(2L, "fault-2");
        Map<String, Object> nested = json(retryAfter.toJson());
        Map<String, Object> normalized = map(nested.get("retryAfter"));
        assertEquals(
                List.of("rawKind", "delaySeconds", "notBeforeUtcEpochMs"),
                List.copyOf(normalized.keySet()));
        assertEquals("DELAY_SECONDS", normalized.get("rawKind"));
        assertEquals(2L, normalized.get("delaySeconds"));
        assertNull(normalized.get("notBeforeUtcEpochMs"));

        ProviderFaultEvent refresh = ProviderFaultEvent.refresh(
                        9L,
                        789L,
                        ProviderFaultEvent.FaultKind.REFRESH_SUCCEEDED,
                        200,
                        "gen-2",
                        WALL_CLOCK)
                .withIdentity(3L, "fault-3");
        Map<String, Object> refreshJson = json(refresh.toJson());
        assertEquals("REFRESH", refreshJson.get("requestKind"));
        assertNull(refreshJson.get("retryAfter"));
        assertNull(refreshJson.get("providerSignal"));
        assertNull(refreshJson.get("bindingRevision"));
        assertEquals("gen-2", refreshJson.get("providerBindingGeneration"));
    }

    @Test
    void generationNamesRoundTrip() {
        assertEquals("gen-1", ProviderSimulator.generationName(1));
        assertEquals("gen-42", ProviderSimulator.generationName(42));
        assertEquals(1, ProviderSimulator.generationNumber("gen-1"));
        assertEquals(42, ProviderSimulator.generationNumber("gen-42"));
        assertEquals(-1, ProviderSimulator.generationNumber("gen-0"));
        assertEquals(-1, ProviderSimulator.generationNumber("gen-01"));
        assertEquals(-1, ProviderSimulator.generationNumber("gen-"));
        assertEquals(-1, ProviderSimulator.generationNumber("GEN-1"));
        assertEquals(-1, ProviderSimulator.generationNumber(null));
    }

    private static ProviderSimulator simulator(ProviderVariant variant) {
        Map<String, Long> lengths = new TreeMap<>();
        lengths.put("/fixtures/F0/sample.bin", 256L);
        lengths.put("/fixtures/F1/manifest.mpd", 12L);
        return new ProviderSimulator(variant, WALL_CLOCK, lengths);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String value) {
        return (Map<String, Object>) MiniJsonParser.parse(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
