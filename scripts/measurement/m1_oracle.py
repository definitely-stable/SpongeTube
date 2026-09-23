"""Independent host-side evidence kernel and coverage oracle for SpongeTube M1.

Runtime CoverageIndex output and lifecycle events are deliberately not authority inputs.

Canonical verification can start from a copied Room/SQLite database plus the pulled
Sponge storage root:

    SQLite snapshot
        + immutable extent files
        -> committed-extents-v1
        -> verified-extent-files-v1
        -> independent coverage reconstruction
        -> coverage-snapshot-v1 oracle
        -> exact semantic comparison with runtime coverage

The filesystem verifier derives existence, byte length and SHA-256 itself. Metadata
length/digest values are comparison inputs only; they are never treated as proof that
the file has those properties.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sqlite3
import sys
import tempfile
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Sequence

from schema_subset import SchemaContractError, validate_instance


SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
SUPPORTED_DATABASE_SCHEMA_VERSIONS = frozenset({1})

SEMANTIC_COVERAGE_FIELDS = (
    "sessionId",
    "playheadUs",
    "requiredRepresentations",
    "perTrackPublishedIntervals",
    "playableIntervals",
    "durablePlayableEndUs",
    "durableReserveUs",
)


@dataclass(frozen=True, order=True)
class Interval:
    start_us: int
    end_us: int

    def __post_init__(self) -> None:
        if self.start_us < 0:
            raise ValueError("interval start must be >= 0")
        if self.end_us <= self.start_us:
            raise ValueError("interval end must be greater than start")


def _as_interval(value: Interval | Sequence[int]) -> Interval:
    if isinstance(value, Interval):
        return value
    if len(value) != 2:
        raise ValueError("interval sequence must contain exactly two values")
    return Interval(int(value[0]), int(value[1]))


def normalize_intervals(
    values: Iterable[Interval | Sequence[int]],
) -> tuple[Interval, ...]:
    ordered = sorted(_as_interval(value) for value in values)
    if not ordered:
        return ()

    merged: list[Interval] = [ordered[0]]
    for current in ordered[1:]:
        previous = merged[-1]
        if current.start_us <= previous.end_us:
            merged[-1] = Interval(
                previous.start_us,
                max(previous.end_us, current.end_us),
            )
        else:
            merged.append(current)
    return tuple(merged)


def intersect_two(
    left: Iterable[Interval | Sequence[int]],
    right: Iterable[Interval | Sequence[int]],
) -> tuple[Interval, ...]:
    a = normalize_intervals(left)
    b = normalize_intervals(right)
    out: list[Interval] = []
    i = 0
    j = 0

    while i < len(a) and j < len(b):
        start = max(a[i].start_us, b[j].start_us)
        end = min(a[i].end_us, b[j].end_us)
        if start < end:
            out.append(Interval(start, end))

        if a[i].end_us <= b[j].end_us:
            i += 1
        else:
            j += 1

    return tuple(out)


def intersect_required(
    per_track: Mapping[str, Iterable[Interval | Sequence[int]]],
    required_track_ids: Sequence[str],
) -> tuple[Interval, ...]:
    if not required_track_ids:
        raise ValueError("at least one required track is required")
    if len(set(required_track_ids)) != len(required_track_ids):
        raise ValueError("required track ids must be unique")

    result = normalize_intervals(per_track.get(required_track_ids[0], ()))
    for track_id in required_track_ids[1:]:
        result = intersect_two(result, per_track.get(track_id, ()))
        if not result:
            break
    return result


def durable_reserve_us(
    playhead_us: int,
    playable_intervals: Iterable[Interval | Sequence[int]],
) -> int:
    if playhead_us < 0:
        raise ValueError("playhead must be >= 0")

    for interval in normalize_intervals(playable_intervals):
        if interval.start_us <= playhead_us < interval.end_us:
            return interval.end_us - playhead_us
        if playhead_us < interval.start_us:
            return 0
    return 0


def _load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: top-level JSON value must be an object")
    return value


def _load_schema(name: str) -> dict[str, Any]:
    return _load_json(SCHEMAS / name)


def _validate_artifact(
    artifact: Mapping[str, object],
    schema_name: str,
) -> None:
    validate_instance(_load_schema(schema_name), dict(artifact))


def _write_json(path: pathlib.Path, payload: Mapping[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as handle:
        temporary = pathlib.Path(handle.name)
        handle.write(text)
        handle.flush()

    try:
        temporary.replace(path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _file_matches_row(
    row: Mapping[str, object],
    fact: Mapping[str, object] | None,
) -> bool:
    if fact is None or fact.get("exists") is not True:
        return False

    return (
        fact.get("length") == row.get("length")
        and fact.get("sha256") == row.get("sha256")
        and fact.get("storagePath") == row.get("storagePath")
    )


def reconstruct_committed_coverage(
    committed_rows: Iterable[Mapping[str, object]],
    required_representations: Mapping[str, str],
    verified_files: Mapping[str, Mapping[str, object]],
) -> dict[str, tuple[Interval, ...]]:
    """Reconstruct conservative coverage from committed metadata + file facts."""

    if not required_representations:
        raise ValueError("at least one required representation is required")

    rows: dict[str, Mapping[str, object]] = {}
    for row in committed_rows:
        extent_id = str(row["extentId"])
        if extent_id in rows:
            raise ValueError(f"duplicate committed extent row: {extent_id}")
        rows[extent_id] = row

    eligible_candidates = {
        extent_id
        for extent_id, row in rows.items()
        if row.get("state") == "PUBLISHED"
        and row.get("integrityState") == "VALID"
        and _file_matches_row(row, verified_files.get(extent_id))
    }

    ready: set[str] = set()
    changed = True
    while changed:
        changed = False
        for extent_id in eligible_candidates - ready:
            row = rows[extent_id]
            raw_dependencies = row.get("dependencyExtentIds", [])
            if not isinstance(raw_dependencies, list):
                raise ValueError(
                    f"extent {extent_id}: dependencyExtentIds must be an array"
                )
            dependencies = {str(item) for item in raw_dependencies}
            same_identity = all(
                dep in rows
                and rows[dep].get("trackId") == row.get("trackId")
                and rows[dep].get("representationId")
                == row.get("representationId")
                for dep in dependencies
            )
            if same_identity and dependencies <= ready:
                ready.add(extent_id)
                changed = True

    per_track: dict[str, list[Interval]] = {
        track_id: [] for track_id in required_representations
    }

    for extent_id in ready:
        row = rows[extent_id]
        track_id = str(row["trackId"])
        required_representation = required_representations.get(track_id)
        if required_representation is None:
            continue
        if row.get("representationId") != required_representation:
            continue

        start = row.get("mediaStartUs")
        end = row.get("mediaEndUs")
        if start is None or end is None:
            # Initialization/index/dependency-only extent.
            continue

        per_track[track_id].append(Interval(int(start), int(end)))

    return {
        track_id: normalize_intervals(intervals)
        for track_id, intervals in per_track.items()
    }


def oracle_snapshot(
    committed_rows: Iterable[Mapping[str, object]],
    required_representations: Mapping[str, str],
    verified_files: Mapping[str, Mapping[str, object]],
    playhead_us: int,
) -> dict[str, object]:
    """Return the semantic subset used by unit tests and canonical snapshots."""

    per_track = reconstruct_committed_coverage(
        committed_rows,
        required_representations,
        verified_files,
    )
    required_track_ids = sorted(required_representations)
    playable = intersect_required(per_track, required_track_ids)
    reserve = durable_reserve_us(playhead_us, playable)

    playable_end = None
    for interval in playable:
        if interval.start_us <= playhead_us < interval.end_us:
            playable_end = interval.end_us
            break

    return {
        "perTrackPublishedIntervals": {
            track_id: [
                {"startUs": interval.start_us, "endUs": interval.end_us}
                for interval in per_track[track_id]
            ]
            for track_id in required_track_ids
        },
        "playableIntervals": [
            {"startUs": interval.start_us, "endUs": interval.end_us}
            for interval in playable
        ],
        "durablePlayableEndUs": playable_end,
        "durableReserveUs": reserve,
    }


def export_committed_snapshot(
    database: pathlib.Path,
    *,
    snapshot_id: str,
    session_id: str,
    snapshot_kind: str,
) -> dict[str, object]:
    """Read a copied Room SQLite database in read-only mode."""

    if not database.is_file():
        raise ValueError(f"SQLite database does not exist: {database}")
    if not snapshot_id:
        raise ValueError("snapshot id must not be empty")
    if not session_id:
        raise ValueError("session id must not be empty")
    if snapshot_kind not in {"LIVE_COMMITTED", "POST_RECOVERY"}:
        raise ValueError(f"unsupported snapshot kind: {snapshot_kind}")

    uri = database.resolve().as_uri() + "?mode=ro"
    with sqlite3.connect(uri, uri=True) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")

        quick_check = [
            str(row[0])
            for row in connection.execute("PRAGMA quick_check")
        ]
        if quick_check != ["ok"]:
            raise ValueError(
                "SQLite snapshot failed PRAGMA quick_check: "
                + "; ".join(quick_check)
            )

        database_schema_version = int(
            connection.execute("PRAGMA user_version").fetchone()[0]
        )
        if database_schema_version not in SUPPORTED_DATABASE_SCHEMA_VERSIONS:
            raise ValueError(
                "unsupported ExtentStore database schema version: "
                f"{database_schema_version}; supported="
                f"{sorted(SUPPORTED_DATABASE_SCHEMA_VERSIONS)}"
            )

        dependencies: dict[str, list[str]] = {}
        for row in connection.execute(
            """
            SELECT extent_id, dependency_extent_id
            FROM extent_dependencies
            ORDER BY extent_id, dependency_extent_id
            """
        ):
            dependencies.setdefault(str(row["extent_id"]), []).append(
                str(row["dependency_extent_id"])
            )

        extents: list[dict[str, object]] = []
        rows = connection.execute(
            """
            SELECT
                extent_id,
                publication_state,
                integrity_state,
                track_id,
                representation_id,
                media_start_us,
                media_end_us,
                length,
                sha256,
                storage_path
            FROM extents
            ORDER BY extent_id
            """
        )
        for row in rows:
            extents.append(
                {
                    "extentId": str(row["extent_id"]),
                    "state": str(row["publication_state"]),
                    "integrityState": str(row["integrity_state"]),
                    "trackId": str(row["track_id"]),
                    "representationId": str(row["representation_id"]),
                    "mediaStartUs": row["media_start_us"],
                    "mediaEndUs": row["media_end_us"],
                    "dependencyExtentIds": dependencies.get(
                        str(row["extent_id"]),
                        [],
                    ),
                    "length": int(row["length"]),
                    "sha256": str(row["sha256"]),
                    "storagePath": str(row["storage_path"]),
                }
            )

    snapshot: dict[str, object] = {
        "schemaVersion": 1,
        "snapshotId": snapshot_id,
        "sessionId": session_id,
        "snapshotKind": snapshot_kind,
        "databaseSchemaVersion": database_schema_version,
        "extents": extents,
    }
    _validate_artifact(snapshot, "committed-extents-v1.schema.json")
    return snapshot


def _safe_storage_path(
    storage_root: pathlib.Path,
    storage_path: str,
) -> pathlib.Path:
    """Resolve a metadata path only inside the copied Sponge storage root."""

    if not storage_path or "\\" in storage_path or ":" in storage_path:
        raise ValueError(f"unsafe storagePath: {storage_path!r}")

    relative = pathlib.PurePosixPath(storage_path)
    if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
        raise ValueError(f"unsafe storagePath: {storage_path!r}")

    root = storage_root.resolve()
    candidate = root.joinpath(*relative.parts)

    current = root
    for part in relative.parts:
        current = current / part
        if current.exists() and current.is_symlink():
            raise ValueError(
                f"storagePath traverses a symlink: {storage_path!r}"
            )

    resolved = candidate.resolve(strict=False)
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError(
            f"storagePath escapes storage root: {storage_path!r}"
        ) from error
    return resolved


def _sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _expected_storage_path(
    extent_id: str,
    database_schema_version: int,
) -> str:
    if database_schema_version not in SUPPORTED_DATABASE_SCHEMA_VERSIONS:
        raise ValueError(
            "unsupported ExtentStore database schema version for path layout: "
            f"{database_schema_version}"
        )
    key = hashlib.sha256(extent_id.encode("utf-8")).hexdigest()
    return f"extents/{key[:2]}/{key}.extent"


def verify_extent_files(
    committed_snapshot: Mapping[str, object],
    storage_root: pathlib.Path,
    *,
    snapshot_id: str,
) -> dict[str, object]:
    """Independently stat/hash every committed metadata path."""

    _validate_artifact(
        committed_snapshot,
        "committed-extents-v1.schema.json",
    )
    if not storage_root.is_dir():
        raise ValueError(f"storage root is not a directory: {storage_root}")

    raw_extents = committed_snapshot["extents"]
    assert isinstance(raw_extents, list)

    seen: set[str] = set()
    files: list[dict[str, object]] = []

    for value in raw_extents:
        assert isinstance(value, dict)
        extent_id = str(value["extentId"])
        if extent_id in seen:
            raise ValueError(f"duplicate committed extent row: {extent_id}")
        seen.add(extent_id)

        storage_path = str(value["storagePath"])
        expected_storage_path = _expected_storage_path(
            extent_id,
            int(committed_snapshot["databaseSchemaVersion"]),
        )
        if storage_path != expected_storage_path:
            raise ValueError(
                f"extent {extent_id}: storagePath does not match canonical "
                f"layout: metadata={storage_path!r} "
                f"expected={expected_storage_path!r}"
            )

        file_path = _safe_storage_path(storage_root, storage_path)
        if not file_path.exists():
            files.append(
                {
                    "extentId": extent_id,
                    "exists": False,
                    "length": None,
                    "sha256": None,
                    "storagePath": storage_path,
                }
            )
            continue

        if not file_path.is_file():
            raise ValueError(
                f"extent path is not a regular file: {storage_path!r}"
            )

        files.append(
            {
                "extentId": extent_id,
                "exists": True,
                "length": file_path.stat().st_size,
                "sha256": _sha256_file(file_path),
                "storagePath": storage_path,
            }
        )

    snapshot: dict[str, object] = {
        "schemaVersion": 1,
        "snapshotId": snapshot_id,
        "sessionId": committed_snapshot["sessionId"],
        "files": files,
    }
    _validate_artifact(snapshot, "verified-extent-files-v1.schema.json")
    return snapshot


def _verified_file_map(
    verified_snapshot: Mapping[str, object],
) -> dict[str, Mapping[str, object]]:
    _validate_artifact(
        verified_snapshot,
        "verified-extent-files-v1.schema.json",
    )
    raw_files = verified_snapshot["files"]
    assert isinstance(raw_files, list)

    result: dict[str, Mapping[str, object]] = {}
    for value in raw_files:
        assert isinstance(value, dict)
        extent_id = str(value["extentId"])
        if extent_id in result:
            raise ValueError(f"duplicate verified file fact: {extent_id}")
        result[extent_id] = value
    return result


def build_canonical_oracle_snapshot(
    committed_snapshot: Mapping[str, object],
    verified_snapshot: Mapping[str, object],
    required_representations: Mapping[str, str],
    playhead_us: int,
) -> dict[str, object]:
    """Build a schema-valid coverage-snapshot-v1 from independent inputs."""

    _validate_artifact(
        committed_snapshot,
        "committed-extents-v1.schema.json",
    )
    _validate_artifact(
        verified_snapshot,
        "verified-extent-files-v1.schema.json",
    )

    if committed_snapshot["sessionId"] != verified_snapshot["sessionId"]:
        raise ValueError(
            "committed/verified snapshot sessionId mismatch"
        )
    if not required_representations:
        raise ValueError("at least one required representation is required")

    for track_id, representation_id in required_representations.items():
        if not track_id or not representation_id:
            raise ValueError(
                "required track and representation ids must be non-empty"
            )

    raw_extents = committed_snapshot["extents"]
    assert isinstance(raw_extents, list)

    committed_ids = [str(value["extentId"]) for value in raw_extents]
    if len(set(committed_ids)) != len(committed_ids):
        raise ValueError("committed snapshot contains duplicate extent ids")

    verified_file_map = _verified_file_map(verified_snapshot)
    committed_id_set = set(committed_ids)
    verified_id_set = set(verified_file_map)
    if committed_id_set != verified_id_set:
        missing = sorted(committed_id_set - verified_id_set)
        extra = sorted(verified_id_set - committed_id_set)
        raise ValueError(
            "committed/verified extent-id set mismatch: "
            f"missing={missing!r} extra={extra!r}"
        )

    semantic = oracle_snapshot(
        raw_extents,
        required_representations,
        verified_file_map,
        playhead_us,
    )

    required_track_ids = sorted(required_representations)
    snapshot: dict[str, object] = {
        "schemaVersion": 1,
        # Oracle evidence has no Android runtime event clock. These fields make
        # the artifact schema-compatible but are intentionally non-semantic.
        "eventSequence": 0,
        "eventElapsedRealtimeNs": 0,
        "sessionId": committed_snapshot["sessionId"],
        "playheadUs": playhead_us,
        "requiredTrackIds": required_track_ids,
        "requiredRepresentations": {
            key: required_representations[key]
            for key in required_track_ids
        },
        **semantic,
        "playerBufferedAheadUs": None,
    }
    _validate_artifact(snapshot, "coverage-snapshot-v1.schema.json")
    return snapshot


def compare_coverage_semantics(
    runtime_snapshot: Mapping[str, object],
    oracle: Mapping[str, object],
) -> list[str]:
    """Return deterministic semantic mismatch descriptions.

    Event sequence/timestamps and playerBufferedAheadUs are deliberately excluded:
    the host oracle has no Android runtime clock and PlayerBufferedAhead is not
    DurablePlayableReserve.
    """

    _validate_artifact(runtime_snapshot, "coverage-snapshot-v1.schema.json")
    _validate_artifact(oracle, "coverage-snapshot-v1.schema.json")

    errors: list[str] = []

    runtime_track_ids = runtime_snapshot["requiredTrackIds"]
    oracle_track_ids = oracle["requiredTrackIds"]
    assert isinstance(runtime_track_ids, list)
    assert isinstance(oracle_track_ids, list)

    if len(set(runtime_track_ids)) != len(runtime_track_ids):
        errors.append("runtime requiredTrackIds contains duplicates")
    if len(set(oracle_track_ids)) != len(oracle_track_ids):
        errors.append("oracle requiredTrackIds contains duplicates")
    if sorted(runtime_track_ids) != sorted(oracle_track_ids):
        errors.append(
            "requiredTrackIds mismatch: "
            f"runtime={runtime_track_ids!r} oracle={oracle_track_ids!r}"
        )

    for field in SEMANTIC_COVERAGE_FIELDS:
        runtime_value = runtime_snapshot.get(field)
        oracle_value = oracle.get(field)
        if runtime_value != oracle_value:
            errors.append(
                f"{field} mismatch: "
                f"runtime={runtime_value!r} oracle={oracle_value!r}"
            )

    return errors


def _parse_required(values: Sequence[str]) -> dict[str, str]:
    required: dict[str, str] = {}
    for value in values:
        if "=" not in value:
            raise ValueError(
                f"required representation must use TRACK=REP: {value!r}"
            )
        track_id, representation_id = value.split("=", 1)
        if not track_id or not representation_id:
            raise ValueError(
                f"required representation must use TRACK=REP: {value!r}"
            )
        if track_id in required:
            raise ValueError(f"duplicate required track: {track_id}")
        required[track_id] = representation_id

    if not required:
        raise ValueError("at least one --required TRACK=REP is required")
    return required


def _command_export_db(args: argparse.Namespace) -> int:
    payload = export_committed_snapshot(
        args.database,
        snapshot_id=args.snapshot_id,
        session_id=args.session_id,
        snapshot_kind=args.snapshot_kind,
    )
    _write_json(args.output, payload)
    return 0


def _command_verify_files(args: argparse.Namespace) -> int:
    committed = _load_json(args.committed)
    payload = verify_extent_files(
        committed,
        args.storage_root,
        snapshot_id=args.snapshot_id,
    )
    _write_json(args.output, payload)
    return 0


def _command_reconstruct(args: argparse.Namespace) -> int:
    committed = _load_json(args.committed)
    verified = _load_json(args.verified)
    payload = build_canonical_oracle_snapshot(
        committed,
        verified,
        _parse_required(args.required),
        args.playhead_us,
    )
    _write_json(args.output, payload)
    return 0


def _command_compare(args: argparse.Namespace) -> int:
    runtime = _load_json(args.runtime)
    oracle = _load_json(args.oracle)
    errors = compare_coverage_semantics(runtime, oracle)
    if errors:
        for error in errors:
            print(f"MISMATCH: {error}", file=sys.stderr)
        return 1
    print("M1 coverage semantics match")
    return 0


def _command_verify_run(args: argparse.Namespace) -> int:
    required = _parse_required(args.required)
    committed = export_committed_snapshot(
        args.database,
        snapshot_id=f"{args.snapshot_id}-committed",
        session_id=args.session_id,
        snapshot_kind=args.snapshot_kind,
    )
    verified = verify_extent_files(
        committed,
        args.storage_root,
        snapshot_id=f"{args.snapshot_id}-files",
    )
    oracle = build_canonical_oracle_snapshot(
        committed,
        verified,
        required,
        args.playhead_us,
    )

    _write_json(args.committed_output, committed)
    _write_json(args.verified_output, verified)
    _write_json(args.oracle_output, oracle)

    runtime = _load_json(args.runtime)
    errors = compare_coverage_semantics(runtime, oracle)
    if errors:
        for error in errors:
            print(f"MISMATCH: {error}", file=sys.stderr)
        return 1

    print("M1 evidence verified: filesystem, oracle and runtime agree")
    return 0


def _add_required_representations(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--required",
        action="append",
        default=[],
        metavar="TRACK=REP",
        help="required playback representation; repeat once per required track",
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Independent SpongeTube M1 evidence verifier/oracle."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    export = subparsers.add_parser(
        "export-db",
        help="export committed-extents-v1 from a copied Room SQLite database",
    )
    export.add_argument("--database", required=True, type=pathlib.Path)
    export.add_argument("--snapshot-id", required=True)
    export.add_argument("--session-id", required=True)
    export.add_argument(
        "--snapshot-kind",
        choices=("LIVE_COMMITTED", "POST_RECOVERY"),
        required=True,
    )
    export.add_argument("--output", required=True, type=pathlib.Path)
    export.set_defaults(handler=_command_export_db)

    verify_files = subparsers.add_parser(
        "verify-files",
        help="independently stat/hash extent files referenced by committed metadata",
    )
    verify_files.add_argument("--committed", required=True, type=pathlib.Path)
    verify_files.add_argument("--storage-root", required=True, type=pathlib.Path)
    verify_files.add_argument("--snapshot-id", required=True)
    verify_files.add_argument("--output", required=True, type=pathlib.Path)
    verify_files.set_defaults(handler=_command_verify_files)

    reconstruct = subparsers.add_parser(
        "reconstruct",
        help="reconstruct canonical coverage from committed metadata + verified files",
    )
    reconstruct.add_argument("--committed", required=True, type=pathlib.Path)
    reconstruct.add_argument("--verified", required=True, type=pathlib.Path)
    reconstruct.add_argument("--playhead-us", required=True, type=int)
    _add_required_representations(reconstruct)
    reconstruct.add_argument("--output", required=True, type=pathlib.Path)
    reconstruct.set_defaults(handler=_command_reconstruct)

    compare = subparsers.add_parser(
        "compare",
        help="compare runtime and oracle coverage semantic fields exactly",
    )
    compare.add_argument("--runtime", required=True, type=pathlib.Path)
    compare.add_argument("--oracle", required=True, type=pathlib.Path)
    compare.set_defaults(handler=_command_compare)

    verify_run = subparsers.add_parser(
        "verify-run",
        help="export DB, verify files, reconstruct coverage and compare runtime",
    )
    verify_run.add_argument("--database", required=True, type=pathlib.Path)
    verify_run.add_argument("--storage-root", required=True, type=pathlib.Path)
    verify_run.add_argument("--runtime", required=True, type=pathlib.Path)
    verify_run.add_argument("--snapshot-id", required=True)
    verify_run.add_argument("--session-id", required=True)
    verify_run.add_argument(
        "--snapshot-kind",
        choices=("LIVE_COMMITTED", "POST_RECOVERY"),
        required=True,
    )
    verify_run.add_argument("--playhead-us", required=True, type=int)
    _add_required_representations(verify_run)
    verify_run.add_argument(
        "--committed-output",
        required=True,
        type=pathlib.Path,
    )
    verify_run.add_argument(
        "--verified-output",
        required=True,
        type=pathlib.Path,
    )
    verify_run.add_argument(
        "--oracle-output",
        required=True,
        type=pathlib.Path,
    )
    verify_run.set_defaults(handler=_command_verify_run)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        return int(args.handler(args))
    except (
        OSError,
        ValueError,
        sqlite3.DatabaseError,
        SchemaContractError,
        json.JSONDecodeError,
    ) as error:
        parser.error(str(error))

    return 2


if __name__ == "__main__":
    sys.exit(main())
