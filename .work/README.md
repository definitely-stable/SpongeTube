# .work — SpongeTube project authority

This directory is the canonical project workspace.

## Authority order

1. `.work/PRODUCT.md` — product scope and non-goals.
2. `.work/ARCHITECTURE.md` — technical architecture and invariants.
3. `.work/GOVERNANCE.md` — repository, PR, commit, review and merge rules.
4. `.work/VERIFICATION.md` — evidence, benchmark and acceptance policy.
5. `.work/ROADMAP.md` — implementation sequencing.

Supporting records:

- `.work/adr/` — durable architecture decision records.
- `.work/evidence/` — reproducible evidence summaries.

If documents disagree, resolve the conflict explicitly and update the lower-authority document. Do not silently encode a conflicting decision in code.

An ADR may explain or propose a decision, but it does not silently override PRODUCT or ARCHITECTURE. When an accepted ADR changes canonical architecture, update the canonical document in the same PR.

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

## Repository workflow

Repository process is defined by `.work/GOVERNANCE.md`.

In short:

```text
short-lived branch
→ pull request
→ verification/evidence
→ review/checks
→ squash merge
→ main
```

No direct normal-development pushes to `main`.

## Repository state

At architecture/governance v0.1 the repository contains no production implementation. The next code milestone is M0: deterministic Android engine/benchmark bootstrap.
