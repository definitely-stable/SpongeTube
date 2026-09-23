# ADR-0002: PlaybackBridge Media3 seam

Status: **Accepted**
Date: **2026-09-23**

## Context

M1 §11 requires Media3 to remain the decoder/player owner while Sponge Core stays the only owner of remote media fetches: published coverage is read locally, every remote miss goes through FetchBroker (joining in-flight work), cached seek needs no remote request and no hidden second upstream may exist in Media3.

Constraints from the code at M1-D (`main @ 33b5e84`):

- FetchBroker publishes a whole FetchUnit through `ExtentStore.writeExtent`; consumers see only a terminal outcome. Reading in-flight bytes would bypass the PUBLISHED + VALID contract.
- FetchBroker, FetchRequest, consumers and executor are deliberately `internal` until the YouTube delivery path (#50) is understood.
- `ExtentStore.openRead` costs one Room lookup per open; CoverageIndex is an immutable in-memory projection.
- Core must not depend on the player library.
- Every Media3 load retry re-opens the DataSource; without an explicit policy each retry would create a fresh broker owner with a fresh attempt budget (unbounded retries).

## Decision drivers

- single remote owner (ARCHITECTURE: Sponge Core owns remote media fetches);
- conservative coverage: only PUBLISHED + VALID bytes reach the decoder;
- no Room/SQLite access per Media3 `read`;
- no premature freeze of the broker/transport API before #50;
- deterministic, clock-free evidence that no hidden upstream exists.

## Options considered

### Option A — Media3 adapter module over a narrow engine read seam

`:playback:bridge` (Media3) implements `DataSource`/`DataSource.Factory` and a bounded `LoadErrorHandlingPolicy`. `:core:engine` exposes a Media3-free `PlaybackReadSession` (resource/byte reads over a `PlaybackPlan`) plus `PlaybackBridgeRuntime` behind `@SpongeBridgeApi` (`RequiresOptIn`, ERROR). FetchBroker stays internal.

Benefits: Core stays player-independent; broker API stays unfrozen; the seam is resource/byte based so DASH, progressive or merged sources can sit on top.

Costs/risks: an opt-in engine surface that must be reviewed when #50 lands.

### Option B — Media3 inside `:core:engine`

Benefits: no seam type. Costs: Core depends on the player; rejected by the Core/player ownership split.

### Option C — Media3 `CacheDataSource` over an upstream wrapper that calls FetchBroker

Benefits: reuses Media3 cache plumbing. Costs: a second cache/index (SimpleCache) beside ExtentStore, a Media3-owned upstream path and coverage that is not the Sponge PUBLISHED + VALID contract; rejected.

## Decision

Option A.

- MediaSource form for M1: `DashMediaSource` over the F1 fixture with `sponge://<authority>/<resourceKey>` URIs. The player's only `MediaSource.Factory` is this DASH factory; any other scheme or authority is refused by the DataSource. The YouTube MediaSource shape (DASH/progressive/merging/SABR) is an adapter decision after #50 on top of the same engine seam.
- The DASH manifest is served from packaged, length/SHA-256 verified bytes (`InlinePlaybackResource`); the origin never serves the manifest, so every origin data-plane request is a broker attempt without exceptions.
- Miss semantics: acquire the whole FetchUnit as a PLAYBACK consumer, wait for durable publication, refresh CoverageIndex, read locally. No in-flight bytes are exposed.
- `CoverageIndex.resolveExtent` is identity-aware and distinguishes READY / PUBLISHED_NOT_READY(missing dependencies) / IDENTITY_CONFLICT / ABSENT. A matching ExtentId is never enough to admit local bytes: immutable asset/track/representation/range/dependency/length/digest metadata must match the playback plan before read or repair.
- `SpongeLoadErrorHandlingPolicy` retries only bridge fetch failures that may clear (retryable/terminal transport failure, cancelled shared owner), at most `maxRetries` times with a fixed delay; everything else is fatal; no fallback selection. Parameters are **Provisional** (3 retries, 1000 ms) and recorded in evidence; M1-F proves boundedness under N4. Media3 buffer constants are not changed.
- The DataSource reports `isNetwork = false`; network work is FetchBroker's. The Media3 adapter preserves `DataSource.open` edge semantics: bounded requests may extend past EOF, `position == EOF` opens and reads EOF immediately, and only `position > EOF` maps to `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`.
- `HttpRangeFetchExecutor` is the minimal deterministic-origin transport for M1 (206 + exact Content-Range only). Production transport selection stays M2.

## Consequences

Positive:
- one remote owner is structural, and a static guard plus the origin bijection check it;
- cached seeks are local by construction (no network DataSource exists);
- FetchBroker acquisition causality is explicit: NEW_OWNER, JOINED_RUNNING and WAITED_CANCELLING are distinct; bridge JOIN evidence is emitted only for JOINED_RUNNING;
- no Room access on `read`; one metadata lookup per extent open.

Negative:
- a miss waits for a whole FetchUnit before any byte reaches the decoder (latency cost for large units; not optimized in M1);
- `@SpongeBridgeApi` is an opt-in public surface that #50 may reshape;
- immutable committed metadata is matched against the playback plan before `openRead`; the read handle itself still checks length only, so same-length on-disk corruption after admission reaches the decoder until the next recovery scan (accepted M1 limitation).

Operational/recovery implications:
- close order is `player.release()` -> `PlaybackBridgeRuntime.shutdown()` -> `ExtentStore.close()`;
- thread interruption during a miss wait surfaces as `InterruptedIOException` and releases the broker lease; E6 verifies this with a real ExoPlayer release.

## Verification

- engine JVM tests include local/miss/join/cancellation and same-ExtentId foreign-identity rejection; bridge tests include scheme refusal, static no-upstream guard, bounded policy and Media3 `DataSource.open` EOF/range contract;
- instrumented E1-E6 on API 36 with Media Lab, verified by `scripts/ci/verify-m1-e-evidence.sh` (`m1_bridge_evidence.py` + `m1_oracle.py`);
- evidence summary: `.work/evidence/2026-09-23-m1-e-playback-bridge.md`.

## Supersession

None.

## Canonical-doc impact

- `.work/VERIFICATION.md` §22.7: `bridge-events-v1` artifact, producer and independent checks.
- `.work/milestones/M1.md` §14: bridge read events in the canonical evidence list.
- `.work/PRODUCT.md`, `.work/ARCHITECTURE.md`, `.work/ROADMAP.md`: no change; this ADR implements the existing ownership invariant.
