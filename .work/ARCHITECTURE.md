# SpongeTube Architecture v0.1

Status: **Provisional**
Date: **2026-09-21**

## 1. Architectural objective

SpongeTube is a network-resilient Android VOD client. The engine is optimized for **survivable playback**, not maximum throughput.

The central metric is **Playable Reserve**: how much future playback time can continue without a successful network fetch.

## 2. Non-negotiable invariants

1. Sponge Core is the single owner of media network fetches.
2. Playback and prefetch must join the same in-flight fetch for the same media range.
3. Already persisted media bytes survive URL refresh, route change, process restart and temporary provider failure.
4. Provider-specific transport logic must not leak into the persistent cache model.
5. Smart Buffer targets a reserve horizon; it does not automatically mean "download the whole video".
6. A fully playable local coverage set is already offline-ready. Export/remux is optional.
7. The system honors Android's default network and configured VPN/proxy unless the user explicitly chooses otherwise.
8. Shorts and Live must not shape the first engine contracts.
9. Every performance-sensitive architecture claim requires a benchmark or is marked provisional.

## 3. High-level architecture

```text
┌────────────────────────────────────────────┐
│                 Android UI                  │
│ Home · Search · Player · Library · Settings│
└──────────────────────┬─────────────────────┘
                       │
                Application Domain
                       │
                 YouTube Adapter
                       │
                  PlaybackPlan
                       │
╔══════════════════════▼══════════════════════╗
║                 SPONGE CORE                 ║
║                                             ║
║ PlaybackSession                             ║
║ PlayableCoverage / CoverageIndex            ║
║ FetchBroker + SingleFlight                  ║
║ DeadlineScheduler                           ║
║ ReserveController                           ║
║ RouteHealthMonitor                          ║
║ FailureClassifier                           ║
║ DescriptorRefresher                         ║
║ RequestBudget                               ║
║ ResourceGovernor                            ║
║ RetentionManager                            ║
╚══════════════════════╤══════════════════════╝
                       │
               TransportSession
                 /            \
     Recommended platform     Portable fallback
       (HttpEngine when       (DefaultHttpDataSource)
         supported)             \
                 \             /
                  └────┬─────┘
                       ▼
                   ExtentStore
                  /           \
          Ephemeral          Durable
                \             /
                 └─────┬─────┘
                       ▼
                 PlaybackBridge
                       │
                    Media3
```

The transport winner is deliberately not frozen in v0.1. For M0, the recommended platform path uses Media3 HttpEngine where runtime support exists and DefaultHttpDataSource as the portable fallback. OkHttp, Google Play services Cronet and Embedded Cronet remain candidates for later evidence-driven evaluation when they solve a measured problem for the SpongeTube device/network population.

## 4. Core domain model

### 4.1 MediaAsset

Provider-independent identity of a VOD item.

```text
MediaAsset
  providerId
  contentId
  duration
  metadata
```

### 4.2 TrackVariant

A concrete audio or video representation.

```text
TrackVariant
  trackId
  kind: VIDEO | AUDIO | CAPTION
  codec
  container
  bitrate
  width/height/fps when applicable
  providerRepresentationKey
```

The core must not assume fMP4, WebM, fixed-duration chunks or a two-second segment cadence.

### 4.3 FetchUnit

A provider/transport-independent unit of retrievable media coverage.

A FetchUnit can map to:

- an HTTP byte range;
- a DASH fragment;
- an HLS object;
- a SABR/media transport chunk;
- another future transport unit.

```text
FetchUnit
  fetchKey
  trackId
  mediaStartUs
  mediaEndUs
  byteRange? 
  transportOpaqueKey
```

### 4.4 Coverage

Coverage is time/range metadata describing what is safely persisted.

For separate video and audio:

```text
VideoCoverage  00:00 ───────────────── 38:00
AudioCoverage  00:00 ─────────── 31:00

PlayableCoverage = intersection(required track coverage)
                 = 00:00 ─────────── 31:00
```

UI reserve, offline availability and correctness checks use **PlayableCoverage**, never video-only percent.

## 5. YouTube Adapter boundary

The adapter resolves a provider item into a `PlaybackPlan`.

```kotlin
interface MediaProvider {
    suspend fun resolve(
        mediaId: MediaId,
        context: ResolveContext
    ): PlaybackPlan
}
```

```text
PlaybackPlan
  metadata
  selected candidate tracks
  alternative tracks
  provider capabilities
  descriptor freshness policy
  TransportSessionFactory
```

Fetching is intentionally **not** exposed as `SourceProvider.fetchSegment()`. Modern YouTube behavior can require stateful transport/session handling, descriptor refresh, client profiles, PO-token state or SABR-specific behavior.

Provider implementation owns those details; Sponge Core only sees FetchUnits, coverage, errors and refresh capabilities.

### Provider rules

- no provider URL is a stable cache identity;
- cached media survives descriptor refresh;
- 403 does not imply deleting cache;
- retry/re-resolve is request-budgeted;
- adapter failure cannot corrupt Sponge Core state;
- account/auth support is separate from anonymous transport support;
- provider logic must be feature-flagged and diagnosable.

Direct use of GPL components such as NewPipeExtractor requires an explicit project licensing decision before production dependency adoption.

## 6. FetchBroker: one fetch, many consumers

The previous "player fetch" and "prefetch fetch" model is rejected.

```text
FetchKey
   │
   ▼
SharedFetch
  ├── Player consumer
  ├── Reserve consumer
  └── Durable writer
```

Conceptually:

```kotlin
interface FetchBroker {
    suspend fun acquire(
        request: FetchRequest,
        consumer: FetchConsumer
    ): FetchHandle
}
```

Properties:

- single-flight per FetchKey;
- cancellation is reference-counted, not "cancel and restart";
- critical playback can raise priority of an existing fetch;
- persisted completion is atomic;
- failed partial data follows an explicit resumability policy;
- duplicate network bytes are a correctness metric.

## 7. DeadlineScheduler

Static priorities are insufficient.

Each request has:

```text
priorityClass
playbackDeadline
estimatedFetchTime
distanceFromPlayhead
retentionIntent
```

Classes:

```text
URGENT_PLAYBACK
PLAYBACK_RESERVE
SMART_RESERVE
KEEP_OFFLINE
SPECULATIVE_NEXT
```

The scheduler should prefer the request with the highest stall risk, not simply the lowest segment index.

## 8. ReserveController

The controller outputs a target **playable reserve**, not a raw download speed.

Inputs:

- current playable reserve;
- observed goodput;
- selected bitrate;
- recent stalls;
- route stability;
- dwell/session confidence;
- remaining VOD duration;
- metered status;
- storage headroom;
- battery state;
- thermal state;
- user mode;
- provider request budget.

Conceptual objective:

```text
minimize P(stall within horizon H)

subject to
  data_budget
  storage_budget
  energy_budget
  thermal_budget
  provider_request_budget
```

Dwell time is a signal. There is no architecture rule such as "after 60 seconds download 100%".

## 9. Network resilience architecture

### 9.1 RouteHealthMonitor

Use Android connectivity callbacks to observe the app's current default network and capability changes continuously.

Observed facts can include:

- validated/not validated;
- metered/not metered;
- VPN transport present;
- Wi-Fi/cellular transport;
- default network replacement;
- captive portal indication when available.

Do not rely on a one-time snapshot because network capabilities change.

### 9.2 VPN policy

Default policy:

```text
session starts through VPN
        │
VPN disappears unexpectedly
        ▼
continue from local reserve
pause new external fetches
surface route-change state
        │
user policy allows direct continuation?
       / \
     yes  no
      │    │
resume   wait for VPN/default policy
```

Never silently bind to an underlying physical network to bypass an active user VPN.

### 9.3 FailureClassifier

SpongeTube must classify observable failure, not guess geopolitical/network cause.

```text
NO_NETWORK
UNVALIDATED_NETWORK
DNS_FAILURE
CONNECT_TIMEOUT
TLS_FAILURE
PATH_RESET
THROUGHPUT_COLLAPSE
HTTP_REJECTED_403
RATE_LIMITED_429
DESCRIPTOR_STALE
PROVIDER_RESOLVE_FAILED
STORAGE_BLOCKED
UNKNOWN_IO
```

This enables deterministic recovery and honest UI.

### 9.4 DescriptorRefresher

On provider-expiry or selected rejection:

```text
existing persistent coverage
        │
        ├── keep
        │
refresh PlaybackPlan / TransportSession
        │
map missing coverage to fresh FetchUnits
        │
resume
```

The refresh path must never discard valid persisted media merely because its source URL expired.

### 9.5 RequestBudget

Retries and alternate transport attempts are bounded.

Inputs:

- error class;
- provider policy;
- recent request rate;
- session state;
- reserve remaining.

No request storm is allowed when the provider is already rejecting or throttling traffic.

## 10. Transport strategy

Transport remains behind a small interface and follows an evidence-first policy.

### M0 baseline

On supported runtimes, evaluate Media3's HttpEngine integration as the recommended platform transport. It can use the platform networking stack and HTTP/3/QUIC without bundling an embedded networking engine.

Use Media3 DefaultHttpDataSource as the portable baseline/fallback where HttpEngine is unavailable.

### Later candidates

OkHttp, Google Play services Cronet and Embedded Cronet are not rejected. They are deferred until M2 unless M0 demonstrates a blocker. Adding them must solve a measured compatibility, resilience, observability or performance problem that outweighs dependency/APK/runtime cost.

No transport is declared globally faster or more resilient without SpongeTube workload evidence.

## 11. ExtentStore and durability

The store persists media extents, not "two-second segment files".

```text
Extent
  mediaId
  trackId
  mediaStartUs
  mediaEndUs
  storageLocation
  offset
  length
  integrityState
  retentionClass
```

State dimensions are orthogonal:

```text
FetchState      MISSING | FETCHING | PRESENT
IntegrityState  UNKNOWN | VALID | CORRUPT
RetentionClass  EPHEMERAL | CACHED | PINNED
Freshness       CURRENT | STALE_DESCRIPTOR
```

### Storage backend — frozen M1 decision

M0 `SimpleCache/CacheDataSource` is a reference baseline only. Sponge Core must not use, wrap or promote that cache as its persistent source of truth.

The first M1 vertical slice uses a Sponge-owned extent store:

- media bytes are immutable extent files under app-private durable storage (`filesDir/sponge/extents/`), sharded by generated extent identity;
- every committed extent records an immutable byte length and SHA-256 digest; coverage is valid only when file length and digest match the published metadata;
- a fetch writes only to a uniquely named temporary file on the same filesystem as its final extent, closes and fsyncs the file, computes/verifies length + SHA-256, atomically renames it to the immutable extent path, and fsyncs the containing directory before metadata publication where the platform/filesystem exposes that durability primitive;
- Room/SQLite owns the durable metadata/index and journal: MediaAsset, TrackVariant, extent identity, media/range coverage, byte length, SHA-256, integrity state, retention class and commit/recovery state;
- one Room transaction may publish the extent as `PRESENT + VALID` only after the storage commit barrier above completes; until then CoverageIndex must behave as if the bytes do not exist;
- startup recovery deletes orphan temporary files, rejects/quarantines index rows whose immutable extent is missing, length-mismatched or digest-invalid, and never invents coverage;
- PlaybackBridge reads only coverage published by the Sponge index; it never falls back to a second remote Media3 fetch for coverage owned or in-flight by Sponge Core.

The M1 backend is intentionally immutable-file based rather than one file per provider segment. FetchBroker may coalesce adjacent coverage into one extent, so provider segmentation is not storage identity. Extent identity is independent of provider URL and descriptor lifetime; descriptor refresh must not invalidate already verified bytes.

Crash consistency uses an explicit publish barrier:

```text
TEMP
  -> bytes complete
  -> file fsync
  -> length + SHA-256 verified
  -> atomic same-filesystem rename
  -> parent-directory durability barrier where available
  -> Room transaction publishes PRESENT + VALID
  -> CoverageIndex may expose extent
```

A crash before the Room publish can leave at most an orphan immutable file, which recovery may adopt only after full identity/integrity validation or otherwise garbage-collect. A crash after the Room publish must not leave a row pointing at uncommitted bytes.

Packed append-only containers are a later storage optimization only if measured file-count/I/O cost justifies them; adopting them must not change the ExtentStore/CoverageIndex contract.

### Durable locations

- M0 reference-cache experimentation may use `cacheDir`;
- Sponge extents that contribute to Playable Reserve live under app-private `filesDir`, not system-evictable cache storage;
- pinned/offline retention is a metadata policy over the same validated extent store, not a second download cache;
- exported/remuxed user files are a separate optional product output and never the playback source of truth.

## 12. Offline semantics

`OFFLINE_READY` means required tracks have complete valid playable coverage.

It does **not** require MP4 finalization.

```text
Complete PlayableCoverage
        ↓
OFFLINE_READY
        ↓
optional user action
        ↓
Export / remux
```

Export/remux is outside the critical playback path and must be crash-consistent if added later.

## 13. PlaybackBridge

Media3 remains responsible for playback, decoding and normal internal sample/decoder buffers.

The architecture invariant is narrower:

> Media3 must not independently perform a second remote fetch for media coverage already owned or in-flight in Sponge Core.

PlaybackBridge maps Media3 reads/seeks to CoverageIndex + FetchBroker.

Seek must use time/range mapping from the resolved track timeline. It must never derive media time from a hardcoded segment index.

## 14. Background execution

Separate user intent:

- active playback → `mediaPlayback` foreground service semantics;
- explicit Keep Offline → persistent download path compatible with Android background rules;
- opportunistic Smart Buffer after the user leaves playback → bounded grace period or constrained scheduled work, otherwise pause.

Do not keep an indefinite foreground data-sync service just to fill speculative cache.

## 15. ResourceGovernor

Inputs:

- battery level and charging;
- battery saver;
- thermal status;
- storage quota/headroom;
- memory pressure;
- metered status.

Outputs constrain:

- reserve target;
- concurrency;
- selected representation;
- background eligibility;
- retention.

Resource policy must be visible to benchmarks.

## 16. Android technology baseline

As of 2026-09-21:

- target/compile API: 36 for the Play-distributed phone/tablet baseline;
- minimum API: 23 for M0, explicitly Provisional and subject to compatibility evidence;
- build: AGP 9.4.0 + Gradle 9.6.0 + JDK 17;
- Kotlin: AGP built-in Kotlin for Android modules; do not override it merely to chase a newer compiler version;
- UI: Jetpack Compose + Material 3, Compose BOM 2026.06.00 baseline;
- media: AndroidX Media3 1.11.1 baseline;
- persistence: no Room requirement in M0; introduce Room when M1 has a real persistent index;
- settings: add DataStore when persistent user settings exist;
- concurrency: Kotlin coroutines when asynchronous engine code begins;
- benchmark: AndroidX Benchmark 1.5.0 / Macrobenchmark + Perfetto;
- network: HttpEngine where supported, DefaultHttpDataSource fallback; additional transports are evidence-driven.

Android 17/API 37 is compatibility input while its SDK remains preview; it is not the production target baseline for M0.

## 17. UI architecture

UI is state-driven and simple:

```text
PlayerState
  playback
  playableReserve
  networkState
  retentionState
  quality
  recoveryAction?
```

Primary player affordances:

- progress;
- playable reserve overlay/range;
- quality;
- Keep offline;
- subtitles;
- settings.

Diagnostics are a separate surface.

No Shorts navigation or vertical swipe feed exists in v0.x.

## 18. Observability

Local structured events are required from M1:

```text
resolve_started/completed
fetch_started/completed/failed
singleflight_joined
coverage_committed
reserve_target_changed
network_changed
vpn_state_changed
descriptor_refreshed
playback_stall
recovery_started/completed
eviction
storage_error
```

User-facing builds should default to privacy-preserving local diagnostics. Remote telemetry, if ever added, requires an explicit product/privacy decision.

## 19. Deferred concerns

The following must not influence v0.1 implementation complexity:

- iOS implementation details;
- Android TV;
- casting/LAN server;
- Shorts;
- Live/DVR;
- generic media-provider plugin runtime;
- torrent support;
- automatic media export.

The architecture preserves clean boundaries so these can be revisited later without making them current requirements.

## 20. Primary technical references

- Android Media3 network/caching: https://developer.android.com/media/media3/exoplayer/network-stacks
- Media3 downloading: https://developer.android.com/media/media3/exoplayer/downloading-media
- Android ConnectivityManager: https://developer.android.com/reference/android/net/ConnectivityManager
- Android NetworkCapabilities: https://developer.android.com/reference/android/net/NetworkCapabilities
- Android Cronet: https://developer.android.com/develop/connectivity/cronet
- Cronet integration: https://developer.android.com/develop/connectivity/cronet/integration
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- yt-dlp PO Token guide: https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- NewPipeExtractor releases: https://github.com/TeamNewPipe/NewPipeExtractor/releases
