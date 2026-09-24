# Evidence: M1 canonical acceptance

Date: **2026-09-24**

Status: **PASS — 16/16 normative M1 MUST gates**
Issue: #42 (parent #35)
Acceptance infrastructure: PR #70

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
3. origin media ownership, seek behavior, process death, N4 recovery and HTTP
   range continuation satisfy their owning-slice verifiers; and
4. every proof referenced by the aggregate acceptance index is retained and
   bound by path, byte size and SHA-256.

Any violation must fail the canonical aggregate.

## Build

- Commit: `15ee95ca573deed8a83b3542739bd29570ca4e3f`
- Merge: PR #70, `ci(m1): orchestrate canonical M1 acceptance`
- Workflow run: `36013249019`, attempt `1`
- Aggregate run ID: `m1-canonical-36013249019-1`
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

For origin ownership, the baseline is the attempt-correlated Media Lab request
trace. For recovery, the baseline is `recovery-summary-v2` derived from
recovery timelines, gate events, broker evidence, origin trace and the
post-reopen independent coverage reconstruction.

## Procedure

The merge of PR #70 triggered `M1 Acceptance` on the exact merged `main`
SHA. Run `36013249019` invoked the already-accepted owning workflows:

1. **Verify** — host/schema/state-machine tests and retained ACC-07/ACC-15
   evidence;
2. **Android Smoke** — API 36 M1-B/C/D/E evidence and independent oracle
   checks;
3. **Android Compatibility** — API 23 and API 34 compatibility;
4. **M1 Recovery** — actual process death and N4R SHORT/EXHAUST/RESTORE.

The final aggregate downloaded all six raw artifact sets, then executed:

```text
python3 scripts/measurement/m1_acceptance.py collect \
  --evidence-root build/m1-acceptance/evidence \
  --generated-root build/m1-acceptance/generated \
  --run-id m1-canonical-36013249019-1 \
  --git-commit 15ee95ca573deed8a83b3542739bd29570ca4e3f \
  --output build/m1-acceptance/summary/m1-acceptance-index-v1.json

python3 scripts/measurement/m1_acceptance.py verify-index \
  --index build/m1-acceptance/summary/m1-acceptance-index-v1.json \
  --evidence-root build/m1-acceptance/evidence \
  --generated-root build/m1-acceptance/generated \
  --expected-git-commit 15ee95ca573deed8a83b3542739bd29570ca4e3f
```

The aggregate independently rechecked retained M1-C runtime/oracle coverage,
required exactly 16 unique PASS gates, validated the run/index schemas, checked
manifest/run/commit identity and rehashed every indexed proof file.

## Results

The aggregate reported:

```text
M1 canonical acceptance PASS: 16/16 MUST gates
M1 acceptance index verified
```

`m1-acceptance-index-v1` contains **703 indexed proof files**.

| Gate | Result | Canonical proof |
| --- | --- | --- |
| M1-ACC-01 | PASS | M1-B single-extent lifecycle + committed metadata + verified file |
| M1-ACC-02 | PASS | six crash boundaries; no phantom coverage |
| M1-ACC-03 | PASS | missing/corrupt published extent quarantined; zero playable contribution |
| M1-ACC-04 | PASS | S0/S10/S30/S60/S120 runtime == independent reconstruction |
| M1-ACC-05 | PASS | five negative seeds stop reserve at the first invalid required-track condition |
| M1-ACC-06 | PASS | concurrent consumers share one FetchBroker owner/physical attempt |
| M1-ACC-07 | PASS | retained FetchBroker cancellation/barrier/request-budget state-machine tests |
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
`https://github.com/definitely-stable/SpongeTube/actions/runs/36013249019`

The SHA-256 values below are the GitHub Actions artifact ZIP digests verified by
the aggregate download/upload steps.

| Artifact | ID | Size (bytes) | SHA-256 |
| --- | ---: | ---: | --- |
| `m1-host-verification-evidence` | 10813263183 | 13,697 | `e56ba9453eb5c4afe2c807e1afbb90742f419bfeb6dc182ab34bac5051c56baa` |
| `m1-acc15-evidence` | 10813322696 | 820 | `189a4eb45d5ce8920fef12f88104ff29f39e1258c18157026879c793c6e0b47c` |
| `android-smoke-evidence` | 10813503560 | 93,281,990 | `fb3f52414de7a22afd5c37be23b08c968d9450e1f13ab6ba2b5719a689ba6dad` |
| `m1-recovery-evidence` | 10813413115 | 12,706,740 | `bcce3ab95816164af1c36013bcd90f8f533632e4568856d91c4328d924aaf093` |
| `android-compat-api-23` | 10813936617 | 150,470 | `c86ce4d8894954f8c36fc523c183d63dac3e29d8cea659710cb41a2671dc548c` |
| `android-compat-api-34` | 10814440171 | 324,315 | `fae5de3d154e52228da67bcc4d2a10aaac9bf980579d97c2bc96dadc0582d32e` |
| `m1-canonical-acceptance-summary` | 10813513609 | 29,377 | `e009286e1687084ee77d37398a62c82646b49b80504976c1f38a950e85d222f0` |

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
