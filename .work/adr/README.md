# Architecture Decision Records

ADRs record durable technical decisions with meaningful alternatives or reversal cost.

Canonical product/architecture truth remains in `.work/PRODUCT.md` and `.work/ARCHITECTURE.md`. An ADR explains a decision; it does not replace updating those documents when the canonical architecture changes.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-select-m1-metadata-persistence.md) | Select Room 3 for M1 metadata persistence | Accepted |
| [0002](0002-playback-bridge-media3-seam.md) | PlaybackBridge Media3 seam | Accepted (superseded in part by ADR-0003) |
| [0003](0003-centralize-recovery-ownership.md) | Centralize recovery ownership in RecoveryCoordinator | Accepted |
| [0004](0004-separate-immutable-work-from-delivery-binding.md) | Separate immutable media work from mutable delivery binding | Accepted |

## Naming

```text
NNNN-short-kebab-title.md
```

Example:

```text
0001-select-initial-media-cache-backend.md
```

## Status

Use one of:

- Proposed
- Accepted
- Superseded
- Rejected

## When an ADR is required

Use an ADR when a decision:

- has multiple credible alternatives;
- is costly to reverse;
- changes an architectural invariant;
- selects a major dependency/protocol/persistent format;
- crosses subsystem boundaries;
- intentionally rejects benchmark evidence or a previously accepted direction.

Do not create ADRs for routine implementation detail.
