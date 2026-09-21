# SpongeTube Product Contract v0.1

Status: **Provisional architecture baseline**
Date: **2026-09-21**

## Mission

SpongeTube is an Android YouTube client designed first for users whose network is slow, unstable, periodically unavailable, changes route through a VPN, or can reach metadata while media delivery degrades or becomes unreachable.

The product promise is not "download faster". It is:

> Keep the video playing for as long as possible by turning good network moments into a persistent, user-controlled playback reserve.

The current product basis is consistent with the original SpongeTube specification: one pipeline serves playback, prefetch and durable local data instead of separate player/download paths.

## Product scope

Version 0.x targets:

- Android phones and tablets only.
- YouTube-oriented long-form VOD.
- Search, video details, playback, history/library primitives required for VOD.
- Persistent smart buffering.
- Offline continuation from already fetched data.
- Explicit "Keep offline" retention.
- Network/VPN route-change resilience.
- Clear storage/data/battery controls.
- Russian and English UI foundations.

Not in initial scope:

- Shorts.
- Live/DVR/live chat.
- Android TV.
- iOS.
- Arbitrary plugin execution.
- Generic torrent support.
- Automatic bypass of the user's configured VPN/proxy.
- Mandatory export/remux to MP4.

## UX invariants

### Play first

A user taps a video and playback starts as soon as critical media is available. SpongeTube must not require a separate download step.

### Buffering is visible only when useful

The main player may expose a compact "reserve" indicator such as:

- "Downloaded ahead: 12 min"
- "Available offline: 34 min"
- "Network unstable — playing from reserve"

Raw throughput, segment indexes and transport details belong in diagnostics, not the normal player UI.

### One offline concept

"Keep offline" means pin/retain the same data already used for playback and continue filling missing playable coverage. It must not trigger a second downloader for bytes already present.

### No silent VPN bypass

If a playback session starts while Android's default network is a VPN and that VPN disappears, the safe default is:

1. continue playback from local reserve;
2. pause external media fetch;
3. tell the user that the route changed;
4. allow an explicit preference to continue on the new system-default network.

SpongeTube must not bind around a VPN to a physical network without explicit user intent.

### Honest failures

The app should describe observable failures, not guess the cause. Examples:

- "No validated network"
- "Media route unavailable"
- "Source rejected the request"
- "Connection changed"
- "Video link needs refresh"

Do not claim a specific regulator, ISP or VPN provider caused a failure unless independently known.

## Smart Buffer objective

Smart Buffer is not "full prefetch after N seconds".

The control objective is:

```text
minimize probability of playback stall in horizon H
subject to:
  user data budget
  storage budget
  battery budget
  thermal budget
  provider/request budget
```

The controller manages a target playable reserve in seconds/bytes. Dwell time is one signal, not a hard switch.

## User modes

The UI should stay simple:

- **Smart** — default adaptive reserve.
- **Data saver** — small reserve and strict metered limits.
- **Resilient** — larger reserve when resources permit.
- **Keep offline** — explicit intent to finish and retain the selected VOD.

Advanced numeric controls can exist behind an expert page; they are not the primary UX.

## Account stance

The initial transport work should be anonymous-first. Account integration is a separate boundary because provider enforcement, credentials and request-rate risks are different from core playback.

Local subscriptions/history/import can be developed independently of account login.

## Future iOS

Do not introduce Kotlin Multiplatform solely for a possible future iOS client. Keep core domain contracts free of Android UI types where practical, but implement and optimize the first engine natively for Android. Revisit code sharing only after the engine is measured and stable.
