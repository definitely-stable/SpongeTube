# Architecture Decision Records

ADRs record durable technical decisions with meaningful alternatives or reversal cost.

Canonical product/architecture truth remains in `.work/PRODUCT.md` and `.work/ARCHITECTURE.md`. An ADR explains a decision; it does not replace updating those documents when the canonical architecture changes.

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
