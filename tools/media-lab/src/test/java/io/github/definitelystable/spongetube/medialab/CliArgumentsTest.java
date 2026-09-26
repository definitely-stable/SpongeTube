package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliArgumentsTest {

    @TempDir
    Path temp;

    @Test
    void parsesN0Defaults() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures"));
        Path trace = temp.resolve("trace.jsonl");

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + trace,
                "--session-id=test-1"
        });

        assertEquals(MediaLabProfile.N0, config.profile());
        assertEquals(0, config.dataPort());
        assertEquals(8, config.dataWorkers());
        assertEquals(0, config.controlPort());
        assertEquals(2, config.controlWorkers());
        assertEquals(8192, config.writeQuantumBytes());
        assertNull(config.referencePlaybackBitrateBps());
        assertNull(config.noProgressStartAfterMs());
        assertEquals(temp.resolve("trace.events.jsonl"), config.sessionTracePath());
        assertEquals(temp.resolve("trace.calibration.jsonl"), config.calibrationPath());
    }

    @Test
    void parsesN1ReferenceBitrate() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n1-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("n1.jsonl"),
                "--session-id=n1",
                "--profile=N1",
                "--reference-playback-bitrate-bps=2000000"
        });

        assertEquals(MediaLabProfile.N1, config.profile());
        assertEquals(2_000_000L, config.referencePlaybackBitrateBps());
        assertEquals(1_000_000L, config.resolvedScenario().aggregateRateBps());
    }

    @Test
    void parsesN4ExplicitStartOffset() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n4-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("n4.jsonl"),
                "--session-id=n4",
                "--profile=N4",
                "--no-progress-start-after-ms=15000"
        });

        assertEquals(MediaLabProfile.N4, config.profile());
        assertEquals(15_000L, config.noProgressStartAfterMs());
        assertEquals(
                120_000L,
                config.resolvedScenario().noProgressDurationMs());
    }

    @Test
    void parsesN4RManualGateProfile() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("n4r-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("n4r.jsonl"),
                "--session-id=n4r",
                "--profile=N4R"
        });

        assertEquals(MediaLabProfile.N4R, config.profile());
        assertNull(config.noProgressStartAfterMs());
        assertNull(config.resolvedScenario().noProgressDurationMs());
        assertEquals(temp.resolve("n4r.gate.jsonl"), config.gateTracePath());
    }

    @Test
    void parsesExplicitListenerConfiguration() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("explicit-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("explicit-trace.jsonl"),
                "--session-id=explicit-1",
                "--data-port=18081",
                "--data-workers=6",
                "--control-port=18082",
                "--control-workers=3",
                "--write-quantum-bytes=4096"
        });

        assertEquals(18081, config.dataPort());
        assertEquals(6, config.dataWorkers());
        assertEquals(18082, config.controlPort());
        assertEquals(3, config.controlWorkers());
        assertEquals(4096, config.writeQuantumBytes());
    }

    @Test
    void rejectsTraceInsideFixtureRoot() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("fixtures-trace"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + fixtureRoot.resolve("trace.jsonl"),
                        "--session-id=test-1"
                }));
    }

    @Test
    void rejectsSameExplicitDataAndControlPort() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("same-port-fixtures"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("same-port-trace.jsonl"),
                        "--session-id=test-1",
                        "--data-port=18081",
                        "--control-port=18081"
                }));
    }

    @Test
    void rejectsN1WithoutReferenceBitrate() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("missing-n1"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("missing-n1.jsonl"),
                        "--session-id=n1",
                        "--profile=N1"
                }));
    }

    @Test
    void rejectsN4WithoutExplicitStartOffset() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("missing-n4"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("missing-n4.jsonl"),
                        "--session-id=n4",
                        "--profile=N4"
                }));
    }

    @Test
    void parsesProviderVariantWithDefaultWallClock() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("provider-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("provider.jsonl"),
                "--session-id=provider-1",
                "--profile=N9",
                "--provider-variant=HTTP_429_RETRY_AFTER_DELAY_SECONDS"
        });

        assertEquals(MediaLabProfile.N9, config.profile());
        assertEquals(
                ProviderVariant.HTTP_429_RETRY_AFTER_DELAY_SECONDS,
                config.providerVariant());
        assertEquals(
                ProviderSimulator.DEFAULT_PROVIDER_WALL_CLOCK_EPOCH_MS,
                config.providerWallClockEpochMs());
        assertEquals(
                "N9-HTTP_429_RETRY_AFTER_DELAY_SECONDS",
                config.resolvedScenario().scenarioId());
        assertEquals(temp.resolve("provider.provider-faults.json"), config.providerFaultsPath());
    }

    @Test
    void parsesProviderWallClockEpoch() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("provider-epoch-fixtures"));

        MediaLabConfig config = CliArguments.parse(new String[] {
                "serve",
                "--fixture-root=" + fixtureRoot,
                "--trace=" + temp.resolve("provider-epoch.jsonl"),
                "--session-id=provider-epoch",
                "--profile=N10",
                "--provider-variant=BINDING_EXPIRY_REFRESH",
                "--provider-wall-clock-epoch-ms=1800000000000"
        });

        assertEquals(1_800_000_000_000L, config.providerWallClockEpochMs());
    }

    @Test
    void rejectsProviderProfileWithoutVariant() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("missing-provider-variant"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("missing-provider-variant.jsonl"),
                        "--session-id=provider",
                        "--profile=N8"
                }));
    }

    @Test
    void rejectsProviderVariantOfAnotherFamily() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("provider-family-mismatch"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("provider-family-mismatch.jsonl"),
                        "--session-id=provider",
                        "--profile=N8",
                        "--provider-variant=HTTP_429_RETRY_AFTER_ABSENT"
                }));
    }

    @Test
    void rejectsUnknownProviderVariant() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("unknown-provider-variant"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("unknown-provider-variant.jsonl"),
                        "--session-id=provider",
                        "--profile=N8",
                        "--provider-variant=HTTP_418"
                }));
    }

    @Test
    void rejectsProviderOptionsOnNonProviderProfiles() throws Exception {
        Path fixtureRoot = Files.createDirectory(temp.resolve("provider-options-n0"));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("provider-options-n0.jsonl"),
                        "--session-id=n0",
                        "--profile=N0",
                        "--provider-variant=HTTP_403_BARE"
                }));

        assertThrows(
                IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {
                        "serve",
                        "--fixture-root=" + fixtureRoot,
                        "--trace=" + temp.resolve("provider-options-n4r.jsonl"),
                        "--session-id=n4r",
                        "--profile=N4R",
                        "--provider-wall-clock-epoch-ms=1790337600000"
                }));
    }

    @Test
    void usageDocumentsProviderOptions() {
        String usage = CliArguments.usage();

        assertTrue(usage.contains("--provider-variant=<VARIANT>"));
        assertTrue(usage.contains("--provider-wall-clock-epoch-ms=<ms>"));
        assertTrue(usage.contains("HTTP_403_BARE"));
        assertTrue(usage.contains("BINDING_EXPIRY_REFRESH"));
    }
}
