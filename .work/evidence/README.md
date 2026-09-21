# Evidence

This directory contains concise, reviewable evidence summaries for architecture, performance and resource decisions.

Large raw traces, benchmark databases and videos belong in CI artifacts. Commit only small fixtures required for reproducibility.

Naming:

```text
YYYY-MM-DD-<topic>.md
```

Every result must identify the commit/build, device/API, fixture, network profile, baseline and raw artifact reference.

A benchmark result is evidence for the tested scenario, not universal proof. Record limitations.
