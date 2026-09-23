# M1 evidence kernel

`m1_oracle.py` is the independent host-side verifier for M1 coverage evidence.

It does **not** import or reuse runtime CoverageIndex code.

## Trust model

The kernel compares three evidence domains:

1. copied Room/SQLite metadata — what the app says it published;
2. pulled immutable extent files — what bytes independently exist on disk;
3. runtime `coverage-snapshot-v1` — what CoverageIndex claims is playable.

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

For database schema v1, extent paths are independently derived as:

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

Reconstruct oracle coverage:

```bash
python3 scripts/measurement/m1_oracle.py reconstruct \
  --committed build/m1/committed-extents.json \
  --verified build/m1/verified-extent-files.json \
  --playhead-us 0 \
  --required video=v1 \
  --required audio=a1 \
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
  --required video=v1 \
  --required audio=a1 \
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

#38 owns the runtime CoverageIndex snapshot and deterministic seed-manifest producers, including the forthcoming canonical `mediaAssetId` identity. M1-F/M1-G own process-kill acquisition and canonical evidence collection; they should consume this kernel without reimplementing its interval logic.
