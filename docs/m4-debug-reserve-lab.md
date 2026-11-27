# M4-A1c — opt-in debug F1 durable reserve indicator

This is a **debug-only laboratory**, not a production Smart Buffer feature.
Release and benchmark variants retain the M0 baseline and do not compile
`M4ReserveLabActivity`. The normal `MainActivity` is untouched.

## Entry point and ownership

The debug APK has a **second launcher activity**, “SpongeTube · M4 Reserve
Lab”. It starts in `Disabled`, with durable reserve **unknown**. Enabling
opens a real Room-backed `ExtentStore`, the canonical SHA-256-verified F1
PlaybackPlan, one `PlaybackBridgeRuntime` and its `CoverageIndex`.
Opening the lab **never** starts a network request.

`Fill reserve once` is an explicit user action. It invokes the same bounded,
serial `ReserveDemandExecutor` (max four immutable F1 fetch keys) through the
narrow `@SpongeBridgeApi` runtime entrypoint; it does not introduce a second
fetch owner, worker, policy or retry loop. A repeat click after reaching
target admits zero new keys. The `Refresh coverage` button and the
250-ms **view sampler only while a pass is active** call
`CoverageIndex.refresh()+snapshot()`; they cannot fetch media.

The progress value is the **required audio ∩ required video durable
intersection from the current playhead**. A zero appears only after a
successful authoritative read. There is no Media3 buffered position or
received-byte fallback; unknown is displayed explicitly. Target is eight
seconds, fixed playhead is zero, and the first valid F1 A/V intersection is
9,941,333 microseconds. The bar caps at the target, while the numeric value
retains the full measured microseconds.

On `Disable lab`, `onStop` or activity destruction, caller-owned work
is cancelled, followed by runtime recovery/broker shutdown and then
`ExtentStore.close()`. The stored published immutable extents are retained
and verified on the next opt-in session.

## Lifecycle hardening

Commands are serialized with a single-slot non-conflating channel and a
busy phase published before admission. Refresh and fill cannot replace
one another. A process-local mutex serializes debug lab store ownership
across Activity instances until non-cancellable teardown completes.

The separate N4R lifecycle test observes a real blocked HTTP body, sends
the foreground Activity home, waits for its disabled state while it is
still alive, reopens the store with zero committed extents and opens a
fresh opt-in Activity with the gate still closed. An independent host
check requires exactly one physical media request and its correlated
gate block before restoration. This is additional correctness evidence,
not VPN or performance acceptance.

## GitHub-hosted acceptance

`.github/workflows/m4-reserve-ui.yml` checks out the **exact PR head**
and runs:

- JUnit host tests for unknown, zero, exact microseconds, progress and
  non-success stop reasons;
- Python falsification tests for forged UI evidence, network refetch,
  missing audio extent, SHA-256 mismatch and incorrect coverage;
- GitHub API 36 emulator with Media Lab N0 HTTP 206 and the actual
  debug Compose UI. UIAutomator verifies no automatic fetch before
  enabling, 0 → 9,941,333 μs only after clicking Fill, zero admitted
  keys on a second click, disabling back to “not measured”, and restored
  durable coverage without network after re-enabling. It also checks
  the ordinary M0 screen remains the regular launcher;
- independent host re-verification against actual Room SQLite,
  SHA-256 of published extent files and the F1 MPD/manifest, requiring
  exactly four distinct physical origin requests.

Run the workflow via Actions or PR checks. In a locally instrumented
debug emulator, start Media Lab N0 on host port 18081 and execute
`adb reverse tcp:18080 tcp:18081`, then open the second launcher
“Reserve Lab”. There is no private device or local PC requirement for CI.

## Non-goals

No YouTube/M3 media delivery claim, VPN replacement claim, runtime
adaptation, autonomous prefetch, production launcher or playback control,
or benchmark performance claim. M4-A1c measures and displays durable
coverage at a fixed F1 lab playhead, **not** a moving Media3 player.
