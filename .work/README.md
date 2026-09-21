# .work — SpongeTube project authority

This directory is the canonical project workspace.

## Authority order

1. `.work/PRODUCT.md` — product scope and non-goals.
2. `.work/ARCHITECTURE.md` — technical architecture and invariants.
3. `.work/VERIFICATION.md` — evidence, benchmark and acceptance policy.
4. `.work/ROADMAP.md` — implementation sequencing.

If documents disagree, resolve the conflict explicitly and update the lower-authority document. Do not silently encode a conflicting decision in code.

## Current scope

SpongeTube is Android-first and YouTube-oriented. The first product target is long-form VOD playback under slow, intermittent, blocked or route-unstable connectivity.

The initial implementation explicitly excludes Shorts, Live, Android TV and iOS. These exclusions are product scope decisions, not invitations to add generic abstractions prematurely.

## Evidence rule

Architecture claims are classified as:

- **Invariant** — required by the product.
- **Validated** — supported by a repeatable test or benchmark.
- **Provisional** — reasonable hypothesis awaiting measurement.
- **Rejected** — measured or reviewed and intentionally not used.

Any numeric threshold is provisional until a reproducible benchmark establishes it.

Benchmark summaries and decision evidence belong under `.work/evidence/`. Raw large benchmark artifacts should remain CI artifacts unless a small fixture is required for reproducibility.

## Engineering rule

Do not optimize for maximum download speed. Optimize for uninterrupted playback probability subject to user data, storage, battery, thermal and provider-request budgets.

The network fetch path must have one owner: Sponge Core. Playback, prefetch and offline retention must not independently fetch the same media range.

## Repository state

At architecture v0.1 the repository contains no production implementation. The next code milestone is a deterministic Android engine PoC plus benchmark harness.
