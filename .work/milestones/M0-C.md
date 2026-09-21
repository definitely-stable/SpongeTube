# M0-C — Media3 Baseline Playback Modes

Status: **Implementation plan**
Issue: #6
Depends on: #4, #5

## 1. Purpose

M0-C establishes two non-Sponge reference playback paths against the same canonical F1 fixture:

```text
A DIRECT
Media3 -> selected standard HTTP transport -> Media Lab

B STANDARD_CACHE
Media3 -> CacheDataSource -> SimpleCache -> same selected transport -> Media Lab
```

These are reference subjects for later M0-D/M0-F measurement. M0-C does not implement Sponge Core, prefetch, persistent reserve policy, provider extraction, route policy, or product buffering behavior.

## 2. Module boundary

Add one Android library:

```text
:playback:baseline
```

Responsibilities:
- baseline mode/cache-state contracts;
- transport resolution;
- Media3 DataSource/MediaSource construction;
- the process-owned standard playback cache;
- ExoPlayer session construction and teardown;
- small test seams needed to prove the baseline contract.

The module contains no Compose/UI code.

The `:app` module remains a developer lab shell and owns the temporary human-facing controls/video surface.

## 3. Dependency baseline

Use Media3 **1.11.1** consistently for every Media3 artifact.

Required artifacts:
- media3-common;
- media3-exoplayer;
- media3-exoplayer-dash;
- media3-datasource;
- media3-database;
- media3-ui in `:app` only.

Do not add OkHttp, Cronet Embedded, Ktor, Room, KSP, DI, download-service, or background-work dependencies.

## 4. Baseline identity

Every session resolves these explicit dimensions:

```text
mode:
  DIRECT
  STANDARD_CACHE

cacheState:
  NONE
  COLD
  WARM

requestedTransport:
  RECOMMENDED_PLATFORM
  DEFAULT_HTTP

effectiveTransport:
  HTTP_ENGINE
  DEFAULT_HTTP
```

Valid combinations:
- DIRECT + NONE;
- STANDARD_CACHE + COLD;
- STANDARD_CACHE + WARM.

Invalid combinations fail immediately instead of silently normalizing.

M0-D later serializes these values into the run manifest.

## 5. Transport seam

### DEFAULT_HTTP

Use `DefaultHttpDataSource.Factory`.

### RECOMMENDED_PLATFORM

Use platform `HttpEngine` when the runtime supports it:
- API 34+, or
- Android S extension level >= 7.

Otherwise resolve explicitly to `DEFAULT_HTTP`.

Use `HttpEngineDataSource.Factory` with a real asynchronous executor.

The HttpEngine HTTP cache is explicitly disabled. STANDARD_CACHE must be the only persistent playback cache in the baseline, so transport-internal HTTP cache cannot contaminate DIRECT-vs-CACHE comparisons.

Do not bind HttpEngine to a specific Android `Network`; the baseline follows the system-selected route.

Transport resolution is observable in the session identity.

## 6. DASH media source

Use `DashMediaSource.Factory(DataSource.Factory)` explicitly for F1.

The same resolved DataSource.Factory is used for MPD and A/V media requests.

F1 already contains exactly:
- one H.264 video representation;
- one AAC-LC audio representation.

Do not tune track selection or ExoPlayer buffer constants in M0-C.

## 7. STANDARD_CACHE

Use:
- `SimpleCache`;
- `StandaloneDatabaseProvider`;
- `LeastRecentlyUsedCacheEvictor`;
- dedicated directory `cacheDir/m0-standard-cache`;
- quota: **64 MiB**.

64 MiB is deliberately larger than the ~14 MiB F1 corpus while remaining small enough for a lab-only cache.

### Ownership

One process owns exactly one `SimpleCache` instance for this directory.

The cache is process-scoped rather than session-scoped. This matches Media3 guidance and prevents directory-lock races.

### COLD

Before the run, remove every existing cache resource through the Cache API.

COLD means zero retained media coverage. It does not require generating a new cache UID or deleting database files.

### WARM

Reuse the existing cache without clearing it.

WARM means retained coverage from a defined earlier run. It does not mean "assume the whole item is cached".

## 8. Session lifecycle

Preparation is split from player creation:

1. worker-side preparation:
   - validate identity;
   - initialize/reuse SimpleCache when required;
   - clear it for COLD;
   - resolve/build transport resources;

2. main-thread player creation:
   - build default ExoPlayer;
   - inject explicit DASH MediaSource factory;
   - set canonical F1 media source;
   - do not customize LoadControl/buffer parameters.

Session close:
- releases ExoPlayer first;
- then closes transport-owned resources;
- does not release the process cache per playback session.

No request is issued before the caller explicitly calls prepare/play on the player.

## 9. Lab UI

Update the M0 app shell only enough to exercise the baseline:

- canonical F1 endpoint: `http://localhost:18080/fixtures/F1/manifest.mpd`;
- mode buttons: DIRECT / CACHE COLD / CACHE WARM;
- transport buttons: RECOMMENDED / DEFAULT HTTP;
- effective transport display;
- cache bytes display;
- PlayerView with stock Media3 controls;
- player state/error display;
- reload action.

The stock PlayerView controls provide play/pause/resume and seeking. No product UI is introduced.

## 10. Error semantics

A network/offline failure must surface as an explicit Media3 `PlaybackException`/state in the lab shell.

The baseline must not:
- crash;
- retry through a different custom transport;
- silently bind another network;
- invoke any Sponge recovery logic.

## 11. Verification layers

### JVM/unit

Prove:
- mode/cache-state validation;
- RECOMMENDED_PLATFORM runtime resolution;
- API 34 and S-extension-7 HttpEngine eligibility;
- older-runtime fallback;
- cache quota remains larger than canonical F1;
- baseline identity is stable and explicit.

### Build

```text
./gradlew check assembleDebug
```

Configuration cache and dependency verification remain required.

### Android smoke

A temporary M0-C evidence workflow may use an API 36 emulator to prove the real Media3 path before #6 closes:

1. start Media Lab N0 with canonical F1;
2. `adb reverse tcp:18080 tcp:<data-port>`;
3. DIRECT prepare/play/pause/resume/forward/back seek;
4. prove separate A/V DASH requests in server trace;
5. STANDARD_CACHE COLD creates retained coverage;
6. remove the host path;
7. STANDARD_CACHE WARM replays a previously retained interval;
8. unreachable uncached data surfaces an explicit playback error rather than a crash.

The temporary smoke workflow is removed before merge. M0-E later owns the permanent `android-smoke` check.

## 12. Evidence

Commit a concise report under:

```text
.work/evidence/YYYY-MM-DD-m0-c-media3-baselines.md
```

Record:
- Media3 version;
- Android API/runtime;
- F1 identity;
- requested/effective transport;
- cache mode/state;
- exercised operations;
- host trace observations;
- known limitations.

This is correctness evidence only, not representative performance evidence.

## 13. Non-goals

M0-C does not add:
- Sponge prefetch or download-ahead;
- FetchBroker;
- PlayableCoverage;
- custom cache keys for future provider URLs;
- download service;
- WorkManager;
- background playback;
- MediaSession;
- adaptive-quality experiments;
- buffer tuning;
- HTTP/2/HTTP/3 performance claims;
- VPN/network binding;
- YouTube/provider logic.

## 14. Exit criteria

M0-C is complete when:

- [ ] `:playback:baseline` exists and builds;
- [ ] DIRECT and STANDARD_CACHE use the same explicit DASH source seam;
- [ ] RECOMMENDED_PLATFORM resolves to HttpEngine where supported and DefaultHttpDataSource otherwise;
- [ ] DEFAULT_HTTP is independently selectable;
- [ ] DIRECT has no persistent playback cache;
- [ ] STANDARD_CACHE uses one process-owned SimpleCache directory;
- [ ] COLD clears prior retained coverage;
- [ ] WARM preserves defined retained coverage;
- [ ] no custom ExoPlayer buffer tuning exists;
- [ ] play/pause/resume and forward/back seek are exercised;
- [ ] separate A/V F1 playback is exercised;
- [ ] explicit offline/network error behavior is exercised;
- [ ] dependency verification/configuration cache remain green;
- [ ] Android smoke evidence is committed;
- [ ] no Sponge/M1 behavior leaks into the baseline.
