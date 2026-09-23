# M1 audit response — accepted corrections and deferrals

Date: **2026-09-23**
Status: **Evidence/decision record; canonical decisions are consolidated into the authority documents in the same PR.**

## Purpose

This record captures how the 2026-09-22 audit package (A–G, independent verifiers V1–V4, REPORT and synthesis H) affected the canonical plan. The audit files themselves are analytical inputs and do not override `.work`.

## Accepted now

- Move YouTube delivery feasibility into an explicit parallel risk track during M1 while keeping production adapter coupling in M3.
- Keep a strict provider firewall: SABR/UMP/PO-token/Innertube details do not enter ExtentStore/CoverageIndex contracts.
- Generalize playable coverage from a hard-coded split video+audio assumption to `PlaybackRequirementSet`; canonical F1 remains split A/V.
- Make M1 independent evidence executable before slice closure: real filesystem verifier, artifact producers, executable oracle and exact comparator.
- Add an opaque ExtentStore read surface before PlaybackBridge; do not expose raw storage paths as playback API.
- Separate M1 publication/integrity lifecycle from later retention policy; descriptor freshness is provider/session state.
- Correct observability ownership by milestone.
- Distinguish process-death and power-loss durability and make effective SQLite durability settings observable.
- Keep packed storage deferred until measured file-count/I/O evidence justifies changing the backend.
- Keep cross-process unfinished-attempt resume outside M1 exit criteria.

## Accepted with corrected wording

- Startup full-store hashing is a confirmed code property, but an end-user startup regression is not yet a measured product fact.
- SQLite/Room should not sit in the steady-state byte-serving path, but no fixed latency claim is adopted without measurement.
- Provider pacing/throttling risk is a probe/measurement question; no traffic-camouflage policy is adopted.
- Descriptor expiry requires provider policy derived from evidence; no universal `expire - 60s` rule is adopted.
- Power-loss metadata risk is treated as platform/configuration-sensitive rather than universal across every Android device.

## Deferred pending evidence

- packed append-only storage;
- filesystem/fsync batching beyond correctness-preserving housekeeping;
- incremental/background full-store integrity verification as a product optimization;
- persistent partial attempts across process death;
- provider-specific pacing rules;
- new transport libraries.

## Rejected as current architecture requirements

- fixed 60-second descriptor refresh margin;
- assumption that every provider representation is permanently split VIDEO + AUDIO;
- requirement to move to packed storage solely from theoretical F2FS/fsync arguments;
- requirement to emulate official-provider traffic behaviour without reproducible evidence.

## Sequencing consequence

M1-C interval algebra may continue. M1-D public fetch/transport contracts must remain compatible with M3-A findings. M1-G only executes evidence infrastructure that previous slices already made real; it is not the place where missing oracle/producers are first implemented.
