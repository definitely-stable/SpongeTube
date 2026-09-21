# Evidence: M0-D measurement harness

Date: **2026-09-21**

## Question

Can SpongeTube produce internally consistent, machine-readable Android playback, Media Lab network, calibration and Perfetto evidence on the canonical F1/N0 correctness scenario without cross-clock arithmetic or representative-performance claims?

## Hypothesis

A single API 36 emulator run of the DIRECT baseline against canonical F1/N0 produces:
- COMPLETE custom playback derivation;
- zero playback errors;
- a request trace whose network/unique/duplicate byte accounting is self-consistent;
- matching run/session/scenario identity across manifest, Android and Media Lab artifacts;
- a retained raw Perfetto trace with a versioned summary;
- an explicit emulator-only limitation.

## Build

- Head commit: `cfb5dec63681c2e3dc1daf9c5837c911a0b177d4`
- Workflow: **M0-D Android Evidence #49**
- Run ID: **35626869424**
- Variant: `benchmark`
- App debuggable: `false`
- App profileable: `true`
- Media3: **1.11.1**
- Benchmark: **1.5.0**
- Compilation mode: `None`
- Startup mode: `COLD`

## Environment

- Device: Android emulator `sdk_gphone64_x86_64`
- API: **36**
- ABI: `x86_64`
- Fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`
- This run is correctness evidence only. It is not representative physical-device performance evidence.

## Fixture

- Fixture: **F1**
- Fixture manifest SHA-256: `237e64f1d08601946f2a1e57829d458adc1850dac26bb00893d8ddf857c83208`
- MPD SHA-256: `e0e05820165ae7c93c81ee8a71a4a3c1412bf6262416dcb1709c769caee07a56`

## Network profile

- Scenario: **N0**
- Scenario SHA-256: `b1f472ab6fcd2b029de8fd0342f6a87eba1b1ae35f3bddc39876190e79beef0f`
- Artificial first-body delay: **0 ms**
- Artificial rate limit: none
- No-progress window: none

## Baseline

- Mode: `DIRECT`
- Cache state: `NONE`
- Requested transport: `RECOMMENDED_PLATFORM`
- Effective transport: `HTTP_ENGINE`

## Procedure

1. Build non-debuggable/profileable benchmark target and Media Lab.
2. Boot a clean API 36 AVD.
3. Start canonical F1/N0 Media Lab and establish `adb reverse`.
4. Run one Macrobenchmark correctness capture.
5. Close the target playback lifecycle and export completed Android evidence through the benchmark-only, signature-gated evidence provider.
6. Stop Media Lab and summarize request/session/calibration traces.
7. Retain raw Perfetto and create versioned summary.
8. Compose run manifest and result; bind result to manifest SHA-256.

## Results

Playback:
- result status: **COMPLETE**
- TTFF: **1,243,665,869 ns**
- stall count: **0**
- stall total: **0 ns**
- progress-intent wall time: **4,846,871,636 ns**
- rebuffer ratio: **0.0**
- playback errors: **0**

Network:
- requests: **16**
- network bytes: **4,758,593**
- unique range bytes: **4,758,593**
- duplicate range bytes: **0**
- HTTP errors: **0**

Calibration:
- observed first-body delay: **0 ms**
- first-body delay error: **0 ms**
- media bytes written: **4,758,593**
- max scheduler slip: **0 ms**

Perfetto:
- raw trace bytes: **10,570,091**
- raw trace SHA-256: `fdb1f45eaea8a92be16ecbd70c17049e4cabcf75f4b2782d946f09c7e3d736f3`
- summary status: **PARTIAL**, intentionally, because stable numeric TraceProcessor extraction is not populated by the correctness smoke; missing process metrics are null rather than fabricated.

Identity:
- manifest SHA-256 referenced by result: `b4737922ac6030dd819b43056c20bb8658f53570ad2650d6ab9859fe0ed86c37`
- run/session/scenario identity matches across the result, Android summary and network summary.
- host and Android monotonic clocks remain separate; no cross-domain subtraction is used.

## Raw artifacts

- GitHub Actions run: **35626869424**
- Artifact: `m0-d-android-evidence`
- Artifact ID: **10651889495**
- Artifact archive digest: `sha256:336fa0b2c968f345174d028534bcbfffb5416593ef7fe4c49084b67f8433625d`
- Retention configured by workflow: **14 days**

## Decision

M0-D measurement/evidence harness is accepted for deterministic emulator correctness evidence.

The custom event reducer is normative for SpongeTube playback metrics. Media3 PlaybackStats remains a semantic cross-check. Run identity is owned by the versioned manifest; the result is bound to that manifest by SHA-256.

## Limitations

- Emulator timing is non-representative and is not used as a product/device performance conclusion.
- Perfetto process numeric extraction is intentionally incomplete in this correctness smoke; raw trace is retained.
- This run is F1/N0/DIRECT only. Cross-baseline N0/N1/N4 acceptance belongs to M0-F.
