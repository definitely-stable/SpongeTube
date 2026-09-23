# M1 evidence kernel

`m1_oracle.py` is the independent host-side verifier for M1 coverage evidence.

It does **not** import or reuse runtime CoverageIndex code.

## Trust model

The kernel compares three evidence domains:

1. copied Room/SQLite metadata — what the app says it published;
2. pulled immutable extent files — what bytes independently exist on disk;
3. runtime coverage snapshot — v1 for historical pre-asset evidence, v2 for asset-scoped M1-C runs — what CoverageIndex claims is playable.

Database length/SHA-256 values are never accepted as file facts. The host verifier derives actual file existence, length and SHA-256 from the pulled storage root.

## Snapshot acquisition

Canonical runs must acquire a consistent database snapshot.

Do one of the following before host verification:

- stop/close the app process/database, then pull the database and storage root; or
- capture the SQLite database and any required `-wal`/sidecar files as one consistent snapshot.

The host kernel can validate SQLite integrity and evidence semantics, but it cannot prove that an externally copied live WAL database was captured atomically.

The storage root supplied to the verifier is the Sponge root that contains:

```text
extents/
metadata/
```

For ExtentStore database schemas v1 and v2, extent paths are independently derived as:

```text
extents/<first-2-of-sha256(extentId)>/<sha256(extentId)>.extent
```

A metadata `storagePath` that differs from this layout fails closed.

## Commands

Export committed metadata from a copied Room database:

```bash
python3 scripts/measurement/m1_oracle.py export-db \
  --database build/m1/input/extents.db \
  --snapshot-id run-123-committed \
  --session-id run-123 \
  --snapshot-kind POST_RECOVERY \
  --output build/m1/committed-extents.json
```

Independently inspect referenced extent files:

```bash
python3 scripts/measurement/m1_oracle.py verify-files \
  --committed build/m1/committed-extents.json \
  --storage-root build/m1/input/sponge \
  --snapshot-id run-123-files \
  --output build/m1/verified-extent-files.json
```

Reconstruct oracle coverage. Asset-scoped committed-extents-v2 requires an explicit media asset; historical v1 omits that option:

```bash
python3 scripts/measurement/m1_oracle.py reconstruct \
  --committed build/m1/committed-extents.json \
  --verified build/m1/verified-extent-files.json \
  --playhead-us 0 \
  --media-asset-id fixture:F1 \
  --required video-main=f1-video-0 \
  --required audio-main=f1-audio-1 \
  --output build/m1/oracle-coverage.json
```

Compare runtime and oracle semantics:

```bash
python3 scripts/measurement/m1_oracle.py compare \
  --runtime build/m1/runtime-coverage.json \
  --oracle build/m1/oracle-coverage.json
```

Or execute the host pipeline in one command:

```bash
python3 scripts/measurement/m1_oracle.py verify-run \
  --database build/m1/input/extents.db \
  --storage-root build/m1/input/sponge \
  --runtime build/m1/runtime-coverage.json \
  --snapshot-id run-123 \
  --session-id run-123 \
  --snapshot-kind POST_RECOVERY \
  --playhead-us 0 \
  --media-asset-id fixture:F1 \
  --required video-main=f1-video-0 \
  --required audio-main=f1-audio-1 \
  --committed-output build/m1/committed-extents.json \
  --verified-output build/m1/verified-extent-files.json \
  --oracle-output build/m1/oracle-coverage.json
```

Exit status:

- `0` — runtime and independent oracle semantics agree;
- `1` — semantic mismatch;
- `2` — invalid input/schema/database/CLI usage.

## Current boundary

#48 owns the independent host kernel.

#38 owns the asset-scoped runtime CoverageIndex, coverage-snapshot-v2 field producer and deterministic seed-manifest-v2 construction producer. The seed planner is intentionally not a coverage oracle; it only binds exact F1 timeline/resource identities and construction attempts. M1-F/M1-G own process-kill acquisition and canonical evidence collection; they should consume this kernel without reimplementing its interval logic.


## Deterministic M1-C seed construction

Produce a canonical construction manifest directly from the committed F1 fixture:

```bash
python3 scripts/measurement/m1_seed_planner.py \
  --seed-id S30_AUDIO_HOLE \
  --output build/m1/seed-manifest.json
```

The producer parses `F1/manifest.mpd`, cross-checks every selected resource against `test-fixtures/media/manifest.json`, and records exact length/SHA-256/dependency identity. It deliberately does not emit expected playable coverage or reserve. Those values come from runtime CoverageIndex and the independent host oracle.
