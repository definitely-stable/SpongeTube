# SpongeTube Architecture v0.1

Status: **Normative — architecture and invariants**
Date: **2026-09-23**

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

### 3.1 Provider isolation firewall

Provider discovery and delivery evolve faster than Sponge Core. The boundary is therefore normative:

```text
YouTube / future provider
        -> ProviderAdapter
        -> PlaybackPlan
        -> Sponge Core
```

Sponge Core contracts MUST NOT depend on provider-specific protocol vocabulary such as SABR/UMP framing, Innertube client names, PO-token context names, visitor-data shape, signed-URL structure or provider segment naming.

Provider adapters normalize those details into stable media identity, playback requirements and provider/transport-independent fetch work. A new provider delivery protocol may require a new adapter/transport implementation, but it must not redefine already-published ExtentStore identity or CoverageIndex semantics.

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

### 4.2.1 PlaybackRequirementSet

Playable output is defined by the exact components required by the selected `PlaybackPlan`, not by a hard-coded assumption that every provider returns one video track plus one audio track.

```text
PlaybackRequirementSet
  requirements[]

PlaybackRequirement
  role
  trackId
  representationId
```

For the canonical M1 F1 fixture the requirement set is one VIDEO representation plus one AUDIO representation. A future muxed/interleaved provider representation may satisfy playback with a different requirement set. CoverageIndex intersects the coverage of every required component and does not interpret provider packaging.

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


### 4.5 Normative playable coverage and reserve semantics

M1 distinguishes persisted bytes from media that can actually sustain playback.

For each required selected track:

\`\`\`text
TrackCoverage(asset, track, representation)
  = union of media-time intervals represented by
    PUBLISHED + VALID extents
    for the exact MediaAssetId + track + active representation identity
\`\`\`

For a playback plan with multiple required tracks:

\`\`\`text
PlayableCoverage
  = intersection(TrackCoverage(requiredTrack_1), ... TrackCoverage(requiredTrack_n))
\`\`\`

For playhead \`P\`:

\`\`\`text
DurablePlayableReserve(P)
  = length of the largest contiguous interval [P, E)
    fully contained in PlayableCoverage
\`\`\`

A disconnected future interval does not increase reserve before its preceding hole is filled.

M1 must keep these concepts separate:

- \`networkBytesReceived\`: bytes delivered by transport;
- \`extentBytesSealed\`: bytes no longer writable by the active writer;
- \`extentBytesVerified\`: bytes whose immutable length and SHA-256 match expected identity;
- \`extentBytesDurable\`: bytes placed at the final immutable path after the storage barrier;
- \`publishedCoverage\`: media-time coverage visible from committed metadata;
- \`DurablePlayableReserve\`: contiguous playable coverage from the current playhead;
- \`PlayerBufferedAhead\`: data already held by Media3/decoder buffers.

\`PlayerBufferedAhead\` is diagnostic and must not be used to prove durable reserve. Product-level effective reserve may combine sources later, but M1 correctness is anchored to durable published coverage.

CoverageIndex publication rules:

1. only \`PUBLISHED + VALID\` extents contribute coverage;
2. an extent from a different representation identity never fills a hole in the active representation;
3. required initialization/index dependencies must be valid before dependent media coverage is exposed as playable;
4. partial/truncated tails do not contribute beyond independently verified usable boundaries;
5. coverage may contain islands, but reserve from a playhead ends at the first required-track hole;
6. every runtime coverage snapshot must be independently reconstructable from committed extent metadata and immutable files;
7. seek correctness uses the resolved media timeline, never byte-count/bitrate approximations.

These definitions supersede any interpretation of raw cache size as playable reserve.

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


### M1 scheduler scope

M1 does not implement the full adaptive deadline/reserve policy described above. The M1 implementation contract requires only:

- playback-critical versus reserve work classes;
- joining an existing in-flight fetch instead of restart;
- priority escalation of the existing shared fetch when playback becomes urgent;
- deterministic ordering sufficient for the M1 correctness suite.

Estimated stall risk, adaptive reserve targets, dwell confidence, battery/thermal/storage policy and user-mode optimization remain M4 work unless M1 evidence exposes a correctness blocker.

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
  representationId
  mediaStartUs
  mediaEndUs
  storageLocation
  offset
  length
  integrityState
```

M1 separates the publication lifecycle from persisted validity:

```text
LifecycleState    RECEIVING | SEALED | VERIFIED | DURABLE | PUBLISHED
PublicationState  PUBLISHED | QUARANTINED
IntegrityState    VALID | CORRUPT
```

`RetentionClass = EPHEMERAL | CACHED | PINNED` is owned by later retention/offline work (M4/M6) and is not required in the M1 schema merely to avoid a future migration.

Descriptor freshness is provider/session state, not a property of immutable stored media bytes. It must not be persisted as an Extent state dimension unless a later ADR demonstrates a concrete need.

### Storage backend — frozen M1 decision

M0 `SimpleCache/CacheDataSource` is a reference baseline only. Sponge Core must not use, wrap or promote that cache as its persistent source of truth.

The first M1 vertical slice uses a Sponge-owned extent store:

- media bytes are immutable extent files under app-private durable storage (`filesDir/sponge/extents/`), sharded by generated extent identity;
- every committed extent records an immutable byte length and SHA-256 digest; coverage is valid only when file length and digest match the published metadata;
- a fetch writes only to a uniquely named temporary file on the same filesystem as its final extent, closes and fsyncs the file, computes/verifies length + SHA-256, atomically renames it to the immutable extent path, and fsyncs the containing directory before metadata publication where the platform/filesystem exposes that durability primitive;
- Room/SQLite owns the durable M1 metadata/index and journal: MediaAsset identity, track/representation identity, extent identity, media/range coverage, byte length, SHA-256, integrity/publication state and commit/recovery facts. Retention policy is not an M1 persistence requirement;
- one Room transaction may publish the extent as `PRESENT + VALID` only after the storage commit barrier above completes; until then CoverageIndex must behave as if the bytes do not exist;
- startup recovery deletes orphan temporary files, rejects/quarantines index rows whose immutable extent is missing, length-mismatched or digest-invalid, and never invents coverage;
- PlaybackBridge reads only coverage published by the Sponge index; it never falls back to a second remote Media3 fetch for coverage owned or in-flight by Sponge Core.

The M1 backend is intentionally immutable-file based rather than one file per provider segment. FetchBroker may coalesce adjacent coverage into one extent, so provider segmentation is not storage identity. Extent identity is independent of provider URL and descriptor lifetime; descriptor refresh must not invalidate already verified bytes.

`ExtentId` is an opaque **globally unique store identity**. Storage does not parse it, but producers must derive it from immutable media/resource identity in a domain that includes `MediaAssetId`; the same `ExtentId` may not be rebound to another asset. Room and the filesystem therefore keep one primary/path identity without silently relying on track-local IDs.

Room v1 did not persist `MediaAssetId`, so those cache rows have no trustworthy provenance. The v1 -> v2 migration deletes v1 extent/dependency metadata transactionally; normal startup recovery then removes the resulting orphan extent files. M1 deliberately prefers a rebuildable cache miss over inventing asset identity or permanently reserving a global `ExtentId`.

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

### Read surface and runtime index

ExtentStore exposes media bytes through an opaque read handle rather than leaking filesystem paths as public API:

```text
ExtentStore.openRead(extentId) -> ExtentReadHandle
```

The handle owns validated access to the immutable extent and participates in store reader lifetime/close coordination. This keeps the public read contract stable if the backend later changes from file-per-extent to another measured storage layout.

Room is the durable metadata authority. CoverageIndex is the read-optimized runtime view. M1-C rebuilds its immutable projection explicitly from committed extents on refresh; dependency closure and interval normalization happen before the new projection is atomically installed. The same atomic projection retains ordered immutable backing extent references for READY media coverage, so later PlaybackBridge lookup can resolve semantic coverage to `ExtentId` without creating a second Room-backed index or exposing filesystem paths. Snapshot/reserve queries use normalized intervals; bridge lookup uses backing refs; neither re-walks the dependency graph nor queries Room. Steady-state PlaybackBridge byte serving MUST NOT require a Room/SQLite query per Media3 read operation.

### Metadata durability scope

M1 distinguishes process death from device power loss/kernel reset. M1-B2 pins Room 3 metadata to `JournalMode.TRUNCATE`; Room 3 configures non-WAL connections with `PRAGMA synchronous=FULL` and a non-zero busy timeout. The store reads the effective writer-connection settings at open and refuses a configuration that does not meet the M1 `TRUNCATE + FULL` policy.

The observed configuration is evidence of the selected SQLite policy, not a claim that the filesystem, kernel, VFS or physical storage device cannot violate durability. Acceptance records the effective journal/synchronous settings. Filesystem/media corruption outside the guarantees of the underlying storage stack is not claimed to be recoverable.


### Partial extent lifecycle and publication

The normative M1 lifecycle is:

\`\`\`text
RECEIVING
   -> SEALED
   -> VERIFIED
   -> DURABLE
   -> PUBLISHED
\`\`\`

Semantics:

- \`RECEIVING\`: unique same-filesystem temporary file; writer may still append;
- \`SEALED\`: writer closed; expected resource/range identity and final byte count are immutable for this attempt;
- \`VERIFIED\`: actual length and SHA-256 satisfy the expected immutable extent identity;
- \`DURABLE\`: verified file has been atomically moved to the final immutable location and the available filesystem durability barrier completed;
- \`PUBLISHED\`: Room/SQLite transaction committed metadata that makes the extent visible to CoverageIndex.

Only \`PUBLISHED\` may increase PlayableCoverage.

Crash/recovery rules:

| Failure boundary | Required restart result |
| --- | --- |
| during \`RECEIVING\` | no published coverage; incomplete temp is deleted or retained only by an explicit future resume policy |
| after \`SEALED\`, before verification | no published coverage |
| after \`VERIFIED\`, before final rename | no published coverage |
| after final rename, before metadata publish | immutable orphan; no published coverage; M1 may garbage-collect rather than auto-adopt |
| during metadata transaction | transaction atomically rolls back or commits |
| after \`PUBLISHED\` | file must exist and pass immutable length + SHA-256 validation |

A metadata row whose file is missing, truncated or digest-invalid is never usable coverage. A file with no published metadata is never implicitly coverage.

M1 intentionally prefers deletion of orphan immutable files over automatic orphan adoption. Adoption is a later optimization and requires separate evidence.

### HTTP partial-response and resume safety

M1 deterministic origin tests must exercise resumable-range correctness even though provider-specific descriptor refresh belongs to later milestones.

A partial attempt may append/resume only when the resource/representation identity is unchanged and the response proves that the returned byte range is the exact requested continuation.

Rules:

- a requested range continuation expects a compatible partial response and validated \`Content-Range\`;
- a full-body response must never be blindly appended to existing partial bytes;
- validator/resource identity changes invalidate the previous partial attempt;
- inconsistent total length, unexpected start offset, impossible range or overlapping incompatible response is rejected;
- partial bytes are never published merely because transport completed;
- retry/resume accounting is explicit in evidence.

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

Local structured events are required when their owning subsystem exists. An event MUST NOT be emitted as a placeholder before its owner lands.

| Event family | Owning milestone |
| --- | --- |
| `fetch_started/completed/failed` | M1 |
| `singleflight_joined` | M1 |
| `coverage_committed` | M1 |
| `playback_stall` | M1 |
| `recovery_started/completed` | M1 |
| `storage_error` | M1 |
| `network_changed` / `vpn_state_changed` | M2 |
| `resolve_started/completed` / `descriptor_refreshed` | M2/M3 |
| `reserve_target_changed` | M4 |
| `eviction` | M6 |

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
