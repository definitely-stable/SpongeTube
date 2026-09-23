# M1-C implementation review — 2026-09-23

Status: **replacement implementation**
Issue: #38
Supersedes draft implementation: #47

## Scope reviewed

The existing M1-C draft was reviewed against current main after the executable evidence kernel (#48), ExtentStore hardening (#49) and the post-merge storage review (#57).

The review treated M1-C as a correctness boundary consumed later by FetchBroker and PlaybackBridge, not as an interval-helper task.

## Confirmed problems in the old draft

### R1 — stale oracle would regress #48

The draft carried an older lightweight `m1_oracle.py`. Merging it would remove the current SQLite exporter, filesystem verifier, CLI and exact comparator.

**Resolution:** evolve the current executable kernel in place. Database/artifact v1 remains supported; Room schema v2 emits asset-scoped committed/coverage v2.

### R2 — CI attempted to generate trust metadata

The old Verify workflow used `--write-verification-metadata`.

**Resolution:** CI remains strict/read-only with respect to dependency trust. Any newly resolved artifact checksum is reviewed and committed explicitly.

### R3 — CoverageIndex was not a read-optimized runtime projection

The old `snapshot()` rebuilt dependency readiness from every committed extent on every query.

**Resolution:** `refresh()` performs the full rebuild, dependency fixed-point closure and interval normalization, then atomically installs an immutable projection. `snapshot()` never queries Room and never walks the dependency graph.

### R4 — seed construction duplicated oracle results

The old seed manifest contained computed per-track coverage, playable coverage and reserve, and the planner imported oracle interval helpers.

**Resolution:** `seed-manifest-v2` is construction-only. The Python producer parses the exact committed F1 MPD and fixture manifest. Runtime CoverageIndex and the independent host oracle calculate coverage separately.

### R5 — canonical seed bytes were not origin-bound strongly enough

The old Android seed path passed `expectedSha256 = null`.

**Resolution:** Android instrumentation verifies fixture resource length and SHA-256 before every write and supplies the exact SHA-256 to ExtentStore.

### R6 — coverage was not scoped by media asset

Track/representation identity alone could allow another media item with the same track labels to contribute intervals.

**Resolution:** Room schema v2, ExtentSpec, StoredExtent and CommittedExtent carry `MediaAssetId`. Coverage keys are exact `MediaAssetId + trackId + representationId`.

### R7 — legacy Room rows needed a safe migration domain

Adding a non-null asset ID cannot pretend that existing v1 rows belong to a newly named media item.

**Resolution:** v1 -> v2 auto-migration assigns the reserved `__legacy_unscoped__` identity. New writes and playback requirement sets cannot use that identity. Migration instrumentation proves the old published row survives without becoming named coverage.

### R8 — dependency readiness must be conservative

Missing, cyclic, cross-asset, cross-track and cross-representation dependencies must not contribute coverage.

**Resolution:** refresh builds a fixed-point READY closure. Every dependency must be present, structurally valid, same asset/track/representation and itself READY.

### R9 — snapshot output could mutate the installed projection

Kotlin read-only collection interfaces do not guarantee an immutable underlying collection.

**Resolution:** requirement/projection/snapshot collections crossing the public boundary are backed by unmodifiable collections; mutation regression coverage verifies the installed projection cannot be corrupted by a consumer.

### R10 — evidence playhead could drift from reserve semantics

A separate evidence `playheadUs` argument could serialize a reserve calculated for another playhead.

**Resolution:** `CoverageSnapshot` owns the playhead used for its calculation. `CoverageEvidenceSnapshot` serializes that bound value and cannot accept another one.

### R11 — wrong-representation negative seed must preserve immutable identity rules

Relabeling the same ExtentId as another representation creates an invalid identity collision unrelated to the intended coverage test.

**Resolution:** alternate-representation seed units get alternate ExtentIds while retaining the same committed fixture bytes. The test isolates exact representation filtering.

## Replacement design

### Storage identity

`MediaAssetId` is persisted in Room schema v2 and treated as part of immutable extent metadata.

### Runtime projection

`CoverageIndex.refresh()`:
1. reads committed extents once;
2. defensively captures them;
3. rejects duplicate IDs;
4. computes conservative dependency closure;
5. groups only READY media intervals by exact asset/track/representation;
6. normalizes exact half-open intervals;
7. atomically installs the new projection.

A failed refresh leaves the prior projection intact.

`CoverageIndex.snapshot()`:
- performs no SQLite/Room access;
- does not traverse the dependency graph;
- intersects already-normalized requirement interval lists;
- computes reserve from the exact playhead and stops at the first hole.

### Fixed seeds

Canonical positive construction:
- S0
- S10
- S30
- S60
- S120

Canonical negative construction:
- S30_VIDEO_HOLE
- S30_AUDIO_HOLE
- S30_MISSING_INIT
- S30_PARTIAL_TAIL
- S30_WRONG_REPRESENTATION

The planner derives boundaries from the real F1 DASH SegmentTimeline, not from a nominal ten-second assumption. Android instrumentation independently parses the committed MPD and verifies every selected resource against the fixture manifest before publishing through the real ExtentStore.

### Evidence generations

Historical v1 remains supported by the executable kernel.

M1-C current artifacts:
- committed-extents-v2 — adds MediaAssetId;
- coverage-snapshot-v2 — adds MediaAssetId;
- seed-manifest-v2 — construction-only;
- verified-extent-files-v1 — unchanged because filesystem facts do not depend on media identity.

## Explicit non-goals retained

- no FetchBroker;
- no PlaybackBridge;
- no provider/YouTube semantics;
- no adaptive Smart Buffer;
- no packed storage;
- no startup hashing optimization;
- no retention/quota schema.

## External implementation check

Room 3 auto-migrations are generated from checked-in schema versions, and Room recommends retaining exported schema files for validation/auto-migration generation. The implementation keeps schema export enabled and commits schema v2.

The repository remains on stable Room 3.0.3; no alpha upgrade is introduced for M1-C.

## Acceptance focus

M1-C is ready only when:
- host interval/dependency/asset tests pass;
- schema and seed producer tests pass;
- Room v1 -> v2 migration passes;
- Android canonical positive/negative seeds pass on the real ExtentStore;
- API 23/API 34 compatibility remains green;
- API 36 Android smoke remains green;
- Linux and Windows strict Verify remain green;
- the v2 independent kernel exactly rejects inflated/cross-asset runtime claims.
