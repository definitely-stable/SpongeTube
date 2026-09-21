package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
