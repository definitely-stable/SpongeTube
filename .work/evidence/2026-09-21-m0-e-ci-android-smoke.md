# Evidence: M0-E CI, Android smoke and supply chain

Date: **2026-09-21**

## Question

Can SpongeTube expose stable, least-privilege pre-merge checks and run deterministic API 36 Android smoke coverage for canonical N0/N1 while retaining the same versioned evidence contract established by M0-D?

## Accepted head

- Commit: `99b5bff3145b453d089a4290c65616f5630382c2`
- PR: **#28**
- Runner: `ubuntu-24.04`
- Emulator: Android API **36**, `x86_64`, `sdk_gphone64_x86_64`

## Stable checks

### verify

Workflow run **35628276958**, Verify #3: **SUCCESS**.

Verified:
- full-SHA pinned checkout/setup-java/setup-gradle;
- JDK 17;
- Gradle distribution SHA present;
- strict dependency verification;
- measurement contract tests;
- build/check/benchmark assembly;
- configuration-cache replay;
- read-only PR Gradle cache;
- finite timeout and superseded-run cancellation.

The stable job/check name is exactly `verify`.

### android-smoke

Workflow run **35628277015**, Android Smoke #3: **SUCCESS**.

The stable job/check name is exactly `android-smoke`.

The smoke uses one explicit SDK/AVD + adb implementation. Gradle Managed Devices are not a parallel M0 path.

## N0 smoke

- runId: `android-smoke-n0`
- sessionId: `android-smoke-n0-1`
- result: **COMPLETE**
- playback errors: **0**
- stalls: **0**
- network requests: **16**
- network bytes: **4,758,593**
- unique range bytes: **4,758,593**
- duplicate range bytes: **0**
- HTTP errors: **0**
- observed first-body delay: **0 ms**

## N1 smoke

- runId: `android-smoke-n1`
- sessionId: `android-smoke-n1-1`
- result: **COMPLETE**
- playback errors: **0**
- stalls: **0**
- configured aggregate rate: **310,090 bps**
- observed aggregate rate: **301,588 bps**
- rate error: **-2.741785%**
- configured first-body delay: **120 ms**
- observed first-body delay: **120 ms**
- max scheduler slip: **5 ms**
- network requests: **6**
- network bytes: **331,818**
- unique range bytes: **331,818**
- duplicate range bytes: **0**
- HTTP errors: **0**

## Artifact integrity

- Artifact: `android-smoke-evidence`
- Artifact ID: **10654306120**
- Archive digest: `sha256:a68401171e7457d7cace53513d11aceb6eaec1dcb6112ce2f72f7f151be244d1`
- Retention: **14 days**
- Both profiles retain Android event/summary/cross-check artifacts, Media Lab request/session/calibration traces, network summary, run manifest and result.

## Supply-chain decision

M0 uses:
- pinned third-party Action SHAs;
- pinned Gradle distribution SHA-256;
- Gradle dependency verification;
- grouped weekly Dependabot for Gradle and GitHub Actions;
- no dependency auto-merge;
- read-only default workflow permissions unless a separate documented need exists.

## Emulator decision

The sole M0 emulator path is explicit Android SDK system-image/AVD creation plus adb orchestration on the pinned Ubuntu runner. The same path has now succeeded for M0-D Macrobenchmark evidence and M0-E N0/N1 smoke.

API 23 minimum compatibility and API 34 transport-boundary checks are periodic compatibility work, not M0 correctness blockers.

## Decision

M0-E is accepted.

The executable gate contract is `verify` + `android-smoke`. Repository ruleset enforcement is an administrative repository setting; the connected GitHub integration cannot mutate it and M0 does not treat that administrative limitation as unresolved product correctness work.

## Limitation

All Android observations here are emulator correctness/reproducibility evidence. They are not representative physical-device performance claims.
