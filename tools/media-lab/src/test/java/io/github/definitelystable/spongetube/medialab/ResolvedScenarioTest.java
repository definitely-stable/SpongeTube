package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolvedScenarioTest {

    @TempDir
    Path temp;

    @Test
    void resolvesN1FromReferencePlaybackBitrate() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures"));

        MediaLabConfig config = new MediaLabConfig(
                fixtureRoot,
                temp.resolve("trace.jsonl"),
                "n1",
                MediaLabProfile.N1,
                0,
                8,
                0,
                2,
                2_000_000L,
                null,
                8192);

        ResolvedScenario scenario = config.resolvedScenario();

        assertEquals(120, scenario.firstBodyDelayMs());
        assertEquals(1_000_000L, scenario.aggregateRateBps());
        assertEquals(0.50d, scenario.aggregateRateRatio());
        assertEquals(64, scenario.scenarioHash().length());
        assertTrue(scenario.toJson().contains("\"scenarioHash\""));
    }

    @Test
    void hashIsStableAndChangesWithResolvedInputs() {
        ResolvedScenario first = ResolvedScenario.testScenario(
                "test", MediaLabProfile.N1, 120, 1_000_000L, 8192, null, null);
        ResolvedScenario same = ResolvedScenario.testScenario(
                "test", MediaLabProfile.N1, 120, 1_000_000L, 8192, null, null);
        ResolvedScenario changedRate = ResolvedScenario.testScenario(
                "test", MediaLabProfile.N1, 120, 900_000L, 8192, null, null);
        ResolvedScenario changedQuantum = ResolvedScenario.testScenario(
                "test", MediaLabProfile.N1, 120, 1_000_000L, 4096, null, null);

        assertEquals(first.scenarioHash(), same.scenarioHash());
        assertNotEquals(first.scenarioHash(), changedRate.scenarioHash());
        assertNotEquals(first.scenarioHash(), changedQuantum.scenarioHash());
    }

    @Test
    void existingProfileHashesAreUnchanged() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("hash-fixtures"));
        Path trace = temp.resolve("hash.jsonl");

        assertEquals(
                "b1f472ab6fcd2b029de8fd0342f6a87eba1b1ae35f3bddc39876190e79beef0f",
                new MediaLabConfig(
                                fixtureRoot,
                                trace,
                                "hash",
                                MediaLabProfile.N0,
                                0,
                                8,
                                0,
                                2,
                                null,
                                null,
                                8192)
                        .resolvedScenario()
                        .scenarioHash());
        assertEquals(
                "e6eda2b4acb4abcbc5c980c213b5a14833baea1ede21e3f52d6f74b087594d5a",
                new MediaLabConfig(
                                fixtureRoot,
                                trace,
                                "hash",
                                MediaLabProfile.N1,
                                0,
                                8,
                                0,
                                2,
                                2_000_000L,
                                null,
                                8192)
                        .resolvedScenario()
                        .scenarioHash());
        assertEquals(
                "f0bd5a8a2d22d5ab773ff5f9f9ee8fad8816c35f62390b38de11f85dc43b94a4",
                new MediaLabConfig(
                                fixtureRoot,
                                trace,
                                "hash",
                                MediaLabProfile.N4,
                                0,
                                8,
                                0,
                                2,
                                null,
                                15_000L,
                                8192)
                        .resolvedScenario()
                        .scenarioHash());
        assertEquals(
                "a361277ebd13ad544bb4e5c241c9e72df63ea887a08149de08f71e3fd81cc8f7",
                new MediaLabConfig(
                                fixtureRoot,
                                trace,
                                "hash",
                                MediaLabProfile.N4R,
                                0,
                                8,
                                0,
                                2,
                                null,
                                null,
                                8192)
                        .resolvedScenario()
                        .scenarioHash());
    }

    @Test
    void providerScenariosCarryFamilyVariantIdentityWithoutDeliveryImpairment() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("provider-fixtures"));
        Path trace = temp.resolve("provider.jsonl");

        for (ProviderVariant variant : ProviderVariant.values()) {
            MediaLabConfig config = new MediaLabConfig(
                    fixtureRoot,
                    trace,
                    "provider",
                    variant.family(),
                    0,
                    8,
                    0,
                    2,
                    null,
                    null,
                    8192,
                    variant,
                    null);
            ResolvedScenario scenario = config.resolvedScenario();

            assertEquals(variant.family().name() + "-" + variant.name(), scenario.scenarioId());
            assertEquals(variant.family(), scenario.profile());
            assertEquals(0, scenario.firstBodyDelayMs());
            assertNull(scenario.referencePlaybackBitrateBps());
            assertNull(scenario.aggregateRateRatio());
            assertNull(scenario.aggregateRateBps());
            assertNull(scenario.noProgressStartAfterMs());
            assertNull(scenario.noProgressDurationMs());
            assertNull(scenario.randomSeed());
            assertEquals(8192, scenario.writeQuantumBytes());
            assertEquals(64, scenario.scenarioHash().length());
            assertEquals(
                    ProviderSimulator.DEFAULT_PROVIDER_WALL_CLOCK_EPOCH_MS,
                    config.providerWallClockEpochMs());
            assertTrue(scenario.toJson().contains("\"scenarioHash\""));
        }

        MediaLabConfig n9First = new MediaLabConfig(
                fixtureRoot,
                trace,
                "provider",
                MediaLabProfile.N9,
                0,
                8,
                0,
                2,
                null,
                null,
                8192,
                ProviderVariant.HTTP_429_RETRY_AFTER_DELAY_SECONDS,
                null);
        MediaLabConfig n9Second = new MediaLabConfig(
                fixtureRoot,
                trace,
                "provider",
                MediaLabProfile.N9,
                0,
                8,
                0,
                2,
                null,
                null,
                8192,
                ProviderVariant.HTTP_429_RETRY_AFTER_HTTP_DATE,
                null);

        assertNotEquals(
                n9First.resolvedScenario().scenarioHash(),
                n9Second.resolvedScenario().scenarioHash());
        assertEquals(
                "N9-HTTP_429_RETRY_AFTER_DELAY_SECONDS",
                n9First.resolvedScenario().scenarioId());
    }
}
