# Evidence: M1 canonical acceptance

Date: **2026-09-25**

Status: **PASS — 16/16 normative M1 MUST gates**
Issue: #42 (parent #35)
Acceptance infrastructure: PR #70
Evidence-order correction: PR #69
ACC-07 retained-evidence correction: PR #72

## Question

Does the merged M1 Persistent Playback Core satisfy every normative MUST gate in
`.work/VERIFICATION.md` on one exact `main` revision, with retained raw
artifacts and independent coverage reconstruction, without relying on emulator
timing as representative performance evidence?

## Hypothesis

For the exact merged `main` build:

1. all `M1-ACC-01..16` gates pass exactly once with no missing, duplicate,
   skipped or unknown gate;
2. runtime CoverageIndex snapshots equal the independent host reconstruction
   from committed metadata plus independently stat/hash-verified immutable
   files for all ten canonical seeds;
3. FetchBroker cancellation/ownership is retained as `fetch-events-v3` and
   independently verified, including joined-consumer release, the CANCELLING
   replacement barrier, late SUCCESS handoff and late INTERNAL_FAILURE handoff
   without hidden refetch or request-budget reset;
4. origin media ownership, seek behavior, process death, N4 recovery and HTTP
   range continuation satisfy their owning-slice verifiers; and
5. every proof referenced by the aggregate acceptance index is retained and
   bound by path, byte size and SHA-256.

Any violation must fail the canonical aggregate.

## Build

- Commit: `ebe316a3fbe52f0f0e0372ecb542dd118a560a06`
- Merge: PR #72, `test(m1): retain canonical ACC-07 ownership evidence`
- Workflow run: `36052212115`
- Successful attempt: `2`
- Aggregate run ID: `m1-canonical-36052212115-2`
- Variant: Android benchmark/canonical M1 acceptance
- GitHub Actions hosts: `ubuntu-24.04`; Windows cross-platform Verify on
  `windows-2025`
- JDK: Temurin 17
- Gradle: 9.6.0, wrapper SHA-256
  `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`
- Media3: 1.11.1
- Playback mode in M1 run manifest: `SPONGE`

Canonical identity from `m1-run-manifest-v1`:

- F1 fixture-manifest SHA-256:
  `237e64f1d08601946f2a1e57829d458adc1850dac26bb00893d8ddf857c83208`
- `.work/VERIFICATION.md` SHA-256:
  `111fb65671eea3809d991a48633c25902aff33b09bd2489d1239a1cf537551ab`
- canonical ten-seed matrix SHA-256:
  `1cd3178b8becd802e8474514fb606ea46f42a4cf4a6555499b3696b872b06100`
- canonical scenario SHA-256:
  `65fd573fe264556115be7b6fd1caa6c71397cfcc10a625fc9a2b1f4090e257b5`

## Rerun provenance

Attempt 1 of run `36052212115` failed before M1 Android smoke execution while
preparing the API 36 emulator:

`Error on ZipFile unknown archive`

The build had not entered M1 storage/coverage/fetch/playback smoke steps.
Separate Verify and Android Smoke runs on the same commit were already green.
Only the failed/dependent canonical jobs were rerun. Attempt 2 used the exact
same `main` commit and completed successfully. No repository change was made
between the failed infrastructure attempt and the successful canonical attempt.

## Environment

- Canonical semantic/correctness device: Android API 36 emulator.
- Compatibility boundary: API 23 and API 34 emulators, both PASS.
- The process-death case verifies a real target PID change.
- Storage evidence uses isolated test roots containing the copied Room metadata
  database plus immutable extent files; the host verifier independently stats
  and SHA-256 hashes those files.
- Battery state, thermal state and physical-device resource behavior are not
  acceptance inputs and were not used for a performance claim.

## Fixture

- Media fixture: F1.
- Split required VIDEO + AUDIO DASH fixture with separate initialization data.
- Positive construction seeds:
  `S0`, `S10`, `S30`, `S60`, `S120`.
- Negative construction seeds:
  `S30_VIDEO_HOLE`, `S30_AUDIO_HOLE`, `S30_MISSING_INIT`,
  `S30_PARTIAL_TAIL`, `S30_WRONG_REPRESENTATION`.
- Each seed retains its own `seed-manifest-v2`, runtime
  `coverage-snapshot-v2`, committed metadata, independently verified file
  facts and oracle coverage reconstruction.

## Network profile

M1 correctness uses deterministic Media Lab profiles rather than a
representative public-network benchmark:

- N0 for FetchBroker/PlaybackBridge origin acceptance;
- N4R manual body-gate scenarios for SHORT, EXHAUST and RESTORE recovery;
- N0/N1 smoke evidence is retained by Android Smoke.

Cross-clock acceptance never subtracts or orders Media Lab and Android clocks.
Causal joins use the command/request/fetch correlation identities defined in
`.work/VERIFICATION.md`.

## Baseline

The correctness baseline is the independent host oracle, not runtime output
repeated back as truth:

`committed Room metadata + host stat/SHA-256 of immutable files -> independent interval reconstruction -> exact comparison with runtime CoverageIndex`.

For FetchBroker cancellation ownership, the baseline is retained
`fetch-events-v3` plus the independent ACC-07 semantic verifier. For origin
ownership, the baseline is the attempt-correlated Media Lab request trace. For
recovery, the baseline is `recovery-summary-v2` derived from recovery
timelines, gate events, broker evidence, origin trace and the post-reopen
independent coverage reconstruction.

## Procedure

The merge of PR #72 triggered `M1 Acceptance` on the exact merged `main`
SHA. Run `36052212115`, attempt 2, invoked the accepted owning workflows:

1. **Verify** — host/schema/state-machine tests plus independently verified
   retained ACC-07 and ACC-15 evidence;
2. **Android Smoke** — API 36 M1-B/C/D/E evidence and independent oracle
   checks;
3. **Android Compatibility** — API 23 and API 34 compatibility;
4. **M1 Recovery** — actual process death and N4R SHORT/EXHAUST/RESTORE.

The final aggregate downloaded all seven raw evidence artifact sets and
executed the canonical collector/verifier against exact commit
`ebe316a3fbe52f0f0e0372ecb542dd118a560a06`.

The aggregate independently rechecked retained M1-C runtime/oracle coverage,
required exactly 16 unique PASS gates, validated the run/index schemas, checked
manifest/run/commit identity and rehashed every indexed proof file.

## Results

The aggregate reported:

```text
M1 canonical acceptance PASS: 16/16 MUST gates
M1 acceptance index verified
```

`m1-acceptance-index-v1` contains **713 indexed proof files**.

| Gate | Result | Canonical proof |
| --- | --- | --- |
| M1-ACC-01 | PASS | M1-B single-extent lifecycle + committed metadata + verified file |
| M1-ACC-02 | PASS | six crash boundaries; no phantom coverage |
| M1-ACC-03 | PASS | missing/corrupt published extent quarantined; zero playable contribution |
| M1-ACC-04 | PASS | S0/S10/S30/S60/S120 runtime == independent reconstruction |
| M1-ACC-05 | PASS | five negative seeds stop reserve at the first invalid required-track condition |
| M1-ACC-06 | PASS | concurrent consumers share one FetchBroker owner/physical attempt |
| M1-ACC-07 | PASS | retained fetch-events-v3: joined release, cancellation barrier restart, late SUCCESS and late INTERNAL_FAILURE handoff; independently verified |
| M1-ACC-08 | PASS | playback joins reserve owner, raises priority, no duplicate restart |
| M1-ACC-09 | PASS | cached seek requires no remote media request |
| M1-ACC-10 | PASS | missing seek MISS -> broker attempt -> SUCCESS -> LOCAL_SERVE; origin is broker-owned |
| M1-ACC-11 | PASS | actual process PID changes; valid coverage survives exactly |
| M1-ACC-12 | PASS | N4R-SHORT stays within durable reserve without reserve-exhaustion rebuffer |
| M1-ACC-13 | PASS | N4R-EXHAUST stalls only consistently with exhausted local horizon; retry budgets bounded |
| M1-ACC-14 | PASS | N4R-RESTORE resumes the same player after durable publication |
| M1-ACC-15 | PASS | matching 206 accepted; incompatible full/range responses append zero accepted bytes |
| M1-ACC-16 | PASS | runtime CoverageIndex exactly matches metadata + independently verified files + offline reconstruction |

Compatibility results:

- API 36 canonical correctness: PASS
- API 23 compatibility: PASS
- API 34 compatibility: PASS

## Raw artifacts

Canonical workflow:
`https://github.com/definitely-stable/SpongeTube/actions/runs/36052212115`

The SHA-256 values below are the GitHub Actions artifact ZIP digests verified by
the aggregate download/upload steps.

| Artifact | ID | Size (bytes) | SHA-256 |
| --- | ---: | ---: | --- |
| `m1-host-verification-evidence` | 10830983807 | 14,428 | `dfd3067869a3af69737ddd99ec313af8cc3e7527c4207b6761da3afca13382e3` |
| `m1-acc07-evidence` | 10830902840 | 4,576 | `529f5736774f2bfc96616ed50a2d8bb084cd9d0db5156c8ea3373ebe54ca7313` |
| `m1-acc15-evidence` | 10830364524 | 820 | `2756e68314c0174b5e13fc3585f494bff67f2672edf93312823f10c8d93b9270` |
| `android-smoke-evidence` | 10844613535 | 93,609,060 | `8ed1650023522946d18a4b8a553b8e9f0a618c4ff55e327a446a10d14f3504c4` |
| `m1-recovery-evidence` | 10830723788 | 12,706,525 | `e67e0dc88061addbcf65f9fea3ef1ac3c5d36b4eef3814cbc07818de7a9276e0` |
| `android-compat-api-23` | 10831221322 | 150,846 | `ac8498edf3dcf078ba2876490933a34da2b098cfa29a419092453e4c329ea177` |
| `android-compat-api-34` | 10830409967 | 301,598 | `3d78be1152e054e2c2460de812b33cc649d2d0fde0e5c71b00938ec42e44eb76` |
| `m1-canonical-acceptance-summary` | 10844078141 | 29,812 | `cb55ca8a081d31aed1c4119ff808745ea62516ac7ee8db8296fa5cf229aad8b8` |

The summary artifact contains:

- `m1-run-manifest-v1.json`;
- `m1-acceptance-index-v1.json`;
- `seed-matrix-v1.json`;
- `scenario-v1.json`;
- `must-matrix.tsv`;
- `acceptance.txt`.

## Decision

**Accept M1 Persistent Playback Core.**

The canonical exact-main run satisfies all 16 normative MUST gates and the
runtime/independent coverage calculations agree exactly for the complete
canonical seed matrix. M1-G may close, followed by parent M1 #35.

## Limitations

- API 36 emulator evidence establishes deterministic correctness and recovery,
  not representative physical-device performance.
- No p95/p99 latency, throughput, battery, thermal or transport-winner claim is
  made.
- API 23/API 34 are compatibility checks; canonical semantic evidence runs once
  on API 36 as specified by the M1 contract.
- N4R-FLAP is a SHOULD gate, not an M1 MUST exit criterion.
- Smart Buffer adaptation, provider refresh, VPN/default-route policy, packed
  storage, production GC and background Keep Offline remain outside M1.
