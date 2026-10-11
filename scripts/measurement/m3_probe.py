"""Bounded host-side producer for SpongeTube M3-A feasibility evidence.

This is a laboratory tool. It does not implement a production YouTube adapter.
The live path imports a separately pinned yt-dlp reference oracle at runtime,
keeps provider URLs/diagnostics in memory only, performs bounded media reads,
then emits only m3-a-probe-v1 portable evidence.

Normative contract: .work/milestones/M3.md
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence

from m3_contracts import (
    MAX_MEDIA_PROBE_BYTES,
    MAX_MEDIA_PROBES,
    MAX_METADATA_REQUESTS,
    M3ContractError,
    validate_probe_document,
)

PRODUCER_ID = "spongetube.m3-bounded-probe"
PRODUCER_VERSION = "1"
EXPECTED_YTDLP_VERSION = "2026.08.19"

LANE_TO_YTDLP_CLIENT = {
    "WEB_EMBEDDED": "web_embedded",
    "TV": "tv",
    "ANDROID_VR": "android_vr",
    "MWEB": "mweb",
    "WEB": "web",
    "WEB_SAFARI": "web_safari",
    "ANDROID": "android",
    "IOS": "ios",
}

_MEDIA_PROBE_LENGTH = 64 * 1024
_ALLOWED_FORWARD_HEADERS = {
    "accept",
    "accept-language",
    "origin",
    "referer",
    "user-agent",
}
_FORBIDDEN_HEADER_NAMES = {
    "authorization",
    "cookie",
    "proxy-authorization",
    "set-cookie",
}
_MISSING_POT = re.compile(r"\bMISSING\s+POT\b", re.IGNORECASE)


class ProbeRuntimeError(RuntimeError):
    pass


class MetadataBudgetExceeded(ProbeRuntimeError):
    pass


@dataclass
class OracleSignals:
    saw_sabr: bool = False
    saw_drm: bool = False
    saw_js_runtime_requirement: bool = False
    saw_rejection: bool = False
    saw_throttling: bool = False


class _SignalLogger:
    """yt-dlp logger that retains booleans only, never provider diagnostic text."""

    def __init__(self, signals: OracleSignals):
        self.signals = signals

    def _observe(self, message: Any) -> None:
        text = str(message).lower()
        if "sabr" in text:
            self.signals.saw_sabr = True
        if "drm protected" in text or " drm" in text:
            self.signals.saw_drm = True
        if (
            "no supported javascript runtime" in text
            or "javascript challenge" in text
            or "js challenge" in text
        ):
            self.signals.saw_js_runtime_requirement = True
        if "http error 429" in text or "rate limit" in text or "too many requests" in text:
            self.signals.saw_throttling = True
        if (
            "http error 403" in text
            or "forbidden" in text
            or "video unavailable" in text
            or "not available" in text
        ):
            self.signals.saw_rejection = True

    def debug(self, message: Any) -> None:
        self._observe(message)

    def info(self, message: Any) -> None:
        self._observe(message)

    def warning(self, message: Any) -> None:
        self._observe(message)

    def error(self, message: Any) -> None:
        self._observe(message)


@dataclass
class ExtractionResult:
    info: Mapping[str, Any] | None
    metadata_requests_used: int
    metadata_budget_exhausted: bool
    signals: OracleSignals
    oracle_version: str
    oracle_revision: str
    extraction_completed: bool


@dataclass
class MediaProbeResult:
    purpose: str
    probe_id: str
    byte_limit: int
    bytes_read: int = 0
    status: int | None = None
    range_honored: bool | None = None
    retry_after_present: bool = False
    rejection: bool = False
    throttling: bool = False


@dataclass
class ProbeSummary:
    capabilities: dict[str, str]
    requirements: dict[str, str]
    provider_signals: dict[str, str]
    delivery: dict[str, Any]
    outcome: dict[str, Any]
    media_probes: list[MediaProbeResult] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _observation(present: bool) -> str:
    return "PRESENT" if present else "UNKNOWN"


def _is_missing_pot(fmt: Mapping[str, Any]) -> bool:
    return bool(_MISSING_POT.search(str(fmt.get("format_note") or "")))


def _format_protocol(fmt: Mapping[str, Any]) -> str:
    return str(fmt.get("protocol") or "").lower()


def _format_url(fmt: Mapping[str, Any]) -> str | None:
    value = fmt.get("url")
    if isinstance(value, str) and value.startswith(("https://", "http://")):
        return value
    return None


def _is_video_only(fmt: Mapping[str, Any]) -> bool:
    return fmt.get("vcodec") not in (None, "none") and fmt.get("acodec") == "none"


def _is_audio_only(fmt: Mapping[str, Any]) -> bool:
    return fmt.get("acodec") not in (None, "none") and fmt.get("vcodec") == "none"


def _is_muxed(fmt: Mapping[str, Any]) -> bool:
    return (
        fmt.get("vcodec") not in (None, "none")
        and fmt.get("acodec") not in (None, "none")
    )


def _media_layout(formats: Sequence[Mapping[str, Any]]) -> str:
    has_video = any(_is_video_only(fmt) for fmt in formats)
    has_audio = any(_is_audio_only(fmt) for fmt in formats)
    has_muxed = any(_is_muxed(fmt) for fmt in formats)
    if has_muxed and has_video and has_audio:
        return "MULTIPLE"
    if has_video and has_audio:
        return "SPLIT_AV"
    if has_muxed:
        return "MUXED"
    return "UNKNOWN"


def _usable_direct_formats(formats: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    result = []
    for fmt in formats:
        if _format_url(fmt) is None:
            continue
        if fmt.get("has_drm"):
            continue
        if _is_missing_pot(fmt):
            continue
        result.append(fmt)
    return result


def _select_probe_format(
    formats: Sequence[Mapping[str, Any]],
    *,
    purpose: str,
) -> Mapping[str, Any] | None:
    direct = _usable_direct_formats(formats)
    if purpose == "VIDEO":
        candidates = [fmt for fmt in direct if _is_video_only(fmt)]
    elif purpose == "AUDIO":
        candidates = [fmt for fmt in direct if _is_audio_only(fmt)]
    elif purpose in {"STARTUP", "RANGE", "CONTINUATION", "SEEK"}:
        candidates = [fmt for fmt in direct if _is_muxed(fmt)] or direct
    else:
        candidates = direct
    return candidates[0] if candidates else None


def _forward_headers(fmt: Mapping[str, Any]) -> dict[str, str]:
    headers: dict[str, str] = {}
    raw = fmt.get("http_headers")
    if not isinstance(raw, Mapping):
        return headers
    for key, value in raw.items():
        name = str(key).lower()
        if name in _FORBIDDEN_HEADER_NAMES:
            continue
        if name in _ALLOWED_FORWARD_HEADERS and isinstance(value, str):
            headers[str(key)] = value
    return headers


def _bounded_http_probe(
    fmt: Mapping[str, Any],
    *,
    purpose: str,
    probe_id: str,
    start: int,
    byte_limit: int,
    timeout_seconds: float = 20.0,
) -> MediaProbeResult:
    if not (0 <= byte_limit <= MAX_MEDIA_PROBE_BYTES):
        raise ProbeRuntimeError("media probe byte limit outside frozen M3-A0 bound")
    if start < 0:
        raise ProbeRuntimeError("media probe start must be non-negative")

    url = _format_url(fmt)
    if url is None:
        raise ProbeRuntimeError("selected format has no direct HTTP URL")

    headers = _forward_headers(fmt)
    end = start + max(0, byte_limit - 1)
    headers["Range"] = f"bytes={start}-{end}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    result = MediaProbeResult(
        purpose=purpose,
        probe_id=probe_id,
        byte_limit=byte_limit,
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            result.status = int(getattr(response, "status", 200))
            content_range = response.headers.get("Content-Range")
            result.range_honored = result.status == 206 and bool(content_range)
            payload = response.read(byte_limit)
            result.bytes_read = len(payload)
            result.retry_after_present = bool(response.headers.get("Retry-After"))
    except urllib.error.HTTPError as exc:
        result.status = int(exc.code)
        result.rejection = exc.code == 403
        result.throttling = exc.code == 429
        result.retry_after_present = bool(exc.headers and exc.headers.get("Retry-After"))
    except urllib.error.URLError:
        # Network failures are intentionally represented as an unsuccessful
        # bounded observation rather than retaining provider/egress details.
        pass
    return result


def _load_yt_dlp():
    try:
        import yt_dlp  # type: ignore
        from yt_dlp import version as yt_dlp_version  # type: ignore
    except ImportError as exc:
        raise ProbeRuntimeError(
            "yt-dlp is not installed; install the pinned lab reference version"
        ) from exc

    version = str(getattr(yt_dlp_version, "__version__", ""))
    revision = str(getattr(yt_dlp_version, "RELEASE_GIT_HEAD", ""))
    if version != EXPECTED_YTDLP_VERSION:
        raise ProbeRuntimeError(
            f"expected yt-dlp {EXPECTED_YTDLP_VERSION}, got {version or 'unknown'}"
        )
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ProbeRuntimeError("yt-dlp release revision is missing or malformed")
    return yt_dlp, version, revision


def _extract_with_yt_dlp(video_id: str, lane: str) -> ExtractionResult:
    if lane not in LANE_TO_YTDLP_CLIENT:
        raise ProbeRuntimeError(f"unsupported lane {lane!r}")
    yt_dlp, oracle_version, oracle_revision = _load_yt_dlp()
    signals = OracleSignals()
    logger = _SignalLogger(signals)
    state = {"count": 0, "exhausted": False}

    base_cls = yt_dlp.YoutubeDL

    class BudgetedYoutubeDL(base_cls):
        def urlopen(self, req):
            if state["count"] >= MAX_METADATA_REQUESTS:
                state["exhausted"] = True
                raise MetadataBudgetExceeded(
                    f"metadata request budget {MAX_METADATA_REQUESTS} exhausted"
                )
            state["count"] += 1
            return super().urlopen(req)

    params = {
        "logger": logger,
        "quiet": True,
        "no_warnings": False,
        "skip_download": True,
        "noplaylist": True,
        "cachedir": False,
        # The no-runtime/no-remote-component baseline is deliberate. A success
        # can therefore prove that the observed path did not require one.
        "js_runtimes": {},
        "remote_components": set(),
        "extractor_args": {
            "youtube": {
                "player_client": [LANE_TO_YTDLP_CLIENT[lane]],
                # Keep token-gated formats in the in-memory result so the
                # producer can observe MISSING POT without providing a token.
                "formats": ["missing_pot"],
            },
        },
    }
    url = "https://www.youtube.com/watch?v=" + urllib.parse.quote(video_id, safe="")
    info = None
    completed = False
    try:
        with BudgetedYoutubeDL(params) as ydl:
            extracted = ydl.extract_info(url, download=False)
            if isinstance(extracted, Mapping):
                info = extracted
                completed = True
    except Exception:
        # Never persist or print the exception: provider IDs, signed URLs or
        # request details may be embedded in diagnostic strings.
        pass

    return ExtractionResult(
        info=info,
        metadata_requests_used=int(state["count"]),
        metadata_budget_exhausted=bool(state["exhausted"]),
        signals=signals,
        oracle_version=oracle_version,
        oracle_revision=oracle_revision,
        extraction_completed=completed,
    )


def _run_media_probes(
    formats: Sequence[Mapping[str, Any]],
    *,
    mode: str,
) -> list[MediaProbeResult]:
    if mode == "none":
        return []

    specs: list[tuple[str, int]] = [("STARTUP", 0)]
    if mode == "range-set":
        specs.extend([
            ("VIDEO", 0),
            ("AUDIO", 0),
            ("CONTINUATION", _MEDIA_PROBE_LENGTH),
            ("SEEK", 1024 * 1024),
        ])

    results: list[MediaProbeResult] = []
    for index, (purpose, start) in enumerate(specs):
        if len(results) >= MAX_MEDIA_PROBES:
            break
        fmt = _select_probe_format(formats, purpose=purpose)
        if fmt is None:
            continue
        filesize = fmt.get("filesize") or fmt.get("filesize_approx")
        if purpose == "SEEK" and isinstance(filesize, int) and filesize > 0:
            start = min(max(_MEDIA_PROBE_LENGTH, filesize // 2), max(0, filesize - _MEDIA_PROBE_LENGTH))
        result = _bounded_http_probe(
            fmt,
            purpose=purpose,
            probe_id=f"m3-{index}-{purpose.lower()}",
            start=start,
            byte_limit=_MEDIA_PROBE_LENGTH,
        )
        results.append(result)
    return results


def _summary_from_extraction(
    extraction: ExtractionResult,
    media_probes: Sequence[MediaProbeResult],
) -> ProbeSummary:
    info = extraction.info or {}
    raw_formats = info.get("formats")
    formats = [
        item for item in raw_formats
        if isinstance(item, Mapping)
    ] if isinstance(raw_formats, list) else []

    protocols = {_format_protocol(fmt) for fmt in formats}
    has_https = any(
        protocol == "https" or (_format_url(fmt) or "").startswith("https://")
        for fmt, protocol in ((fmt, _format_protocol(fmt)) for fmt in formats)
    )
    has_dash = any("dash" in protocol for protocol in protocols)
    has_hls = any("m3u8" in protocol or "hls" in protocol for protocol in protocols)
    has_sabr = extraction.signals.saw_sabr or any("sabr" in protocol for protocol in protocols)
    has_drm = extraction.signals.saw_drm or any(bool(fmt.get("has_drm")) for fmt in formats)
    has_missing_pot = any(_is_missing_pot(fmt) for fmt in formats)

    any_success = any(item.bytes_read > 0 and item.status in (200, 206) for item in media_probes)
    any_range = any(item.range_honored is True for item in media_probes)
    attempted_range = bool(media_probes)
    continuation = next((item for item in media_probes if item.purpose == "CONTINUATION"), None)
    seek = next((item for item in media_probes if item.purpose == "SEEK"), None)

    rejection = extraction.signals.saw_rejection or any(item.rejection for item in media_probes)
    throttling = extraction.signals.saw_throttling or any(item.throttling for item in media_probes)
    retry_after = any(item.retry_after_present for item in media_probes)

    # Requirement fields describe the actually selected bounded path. Other
    # formats may still be token-gated without making the successful path so.
    if any_success:
        gvs_requirement = "NOT_REQUIRED"
    elif has_missing_pot:
        gvs_requirement = "REQUIRED"
    else:
        gvs_requirement = "UNKNOWN"

    # No Player PO token is supplied. Successful extraction + a successful
    # media read is direct evidence that this exact path did not require one.
    player_requirement = "NOT_REQUIRED" if extraction.extraction_completed and any_success else "UNKNOWN"

    if extraction.extraction_completed and any_success:
        challenge_requirement = "NOT_REQUIRED"
    elif extraction.signals.saw_js_runtime_requirement:
        challenge_requirement = "REQUIRED"
    else:
        challenge_requirement = "UNKNOWN"

    capabilities = {
        "https": _observation(has_https),
        "dash": _observation(has_dash),
        "hls": _observation(has_hls),
        "sabr": _observation(has_sabr),
        "drm": _observation(has_drm),
    }
    requirements = {
        "gvsPoToken": gvs_requirement,
        "playerPoToken": player_requirement,
        "challengeRuntime": challenge_requirement,
        "sessionContext": "UNKNOWN",
    }
    provider_signals = {
        "rejection": "PRESENT" if rejection else "UNKNOWN",
        "throttling": "PRESENT" if throttling else "UNKNOWN",
        "retryAfter": "PRESENT" if retry_after else "UNKNOWN",
    }
    range_value = "PRESENT" if any_range else ("ABSENT" if attempted_range and any(item.status == 200 for item in media_probes) else "UNKNOWN")
    delivery = {
        "mediaLayout": _media_layout(formats),
        "range": range_value,
        "continuation": "PRESENT" if continuation and continuation.range_honored and continuation.bytes_read > 0 else "UNKNOWN",
        "descriptorExpiry": "UNKNOWN",
        "descriptorLifetimeSeconds": None,
        "descriptorRefresh": "UNKNOWN",
        "startup": "PRESENT" if any_success else "UNKNOWN",
        "seek": "PRESENT" if seek and seek.range_honored and seek.bytes_read > 0 else "UNKNOWN",
        "sustainedPlayback": "UNKNOWN",
    }

    if throttling:
        classification = "PROVIDER_RATE_LIMITED"
    elif rejection and has_missing_pot:
        classification = "TOKEN_GATED"
    elif rejection:
        classification = "PROVIDER_REJECTED"
    elif challenge_requirement == "REQUIRED":
        classification = "CHALLENGE_GATED"
    elif (
        any_success
        and capabilities["https"] == "PRESENT"
        and gvs_requirement == "NOT_REQUIRED"
        and player_requirement == "NOT_REQUIRED"
        and challenge_requirement == "NOT_REQUIRED"
    ):
        classification = "VIABLE_ANONYMOUS"
    elif has_missing_pot:
        classification = "TOKEN_GATED"
    else:
        classification = "INCONCLUSIVE"

    statuses = [item.status for item in media_probes if isinstance(item.status, int)]
    http_status_class = statuses[-1] // 100 if statuses else None
    limitations = [
        "Live-provider compatibility observation; not a deterministic acceptance or performance result.",
        "No account credentials, PO-token provider, remote component or JavaScript runtime was configured.",
        "Capability absence remains UNKNOWN unless the bounded probe directly established absence.",
        "Descriptor expiry/refresh and sustained playback require later dedicated M3-A2 observations.",
    ]
    if extraction.metadata_budget_exhausted:
        limitations.append("Metadata extraction stopped at the frozen 32-request budget.")
    if not extraction.extraction_completed:
        limitations.append("Reference-oracle metadata extraction did not complete.")
    if not media_probes:
        limitations.append("No direct media probe was executed for this lane/case.")

    return ProbeSummary(
        capabilities=capabilities,
        requirements=requirements,
        provider_signals=provider_signals,
        delivery=delivery,
        outcome={
            "classification": classification,
            "httpStatusClass": http_status_class,
        },
        media_probes=list(media_probes),
        limitations=limitations,
    )


def _build_artifact(
    *,
    video_id: str,
    case_class: str,
    case_revision: str,
    lane: str,
    git_commit: str,
    runner_image: str,
    extraction: ExtractionResult,
    summary: ProbeSummary,
) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-f]{40}", git_commit):
        raise ProbeRuntimeError("git_commit must be an exact 40-character lowercase SHA")
    if case_class not in {"V0", "V1", "V2"}:
        raise ProbeRuntimeError("case_class must be V0, V1 or V2")
    if lane not in LANE_TO_YTDLP_CLIENT:
        raise ProbeRuntimeError(f"unsupported lane {lane!r}")

    artifact = {
        "schemaVersion": 1,
        "probeId": f"m3-a1:{case_class.lower()}:{lane.lower()}:{git_commit[:12]}",
        "createdAtUtc": _utc_now(),
        "gitCommit": git_commit,
        "producer": {
            "toolId": PRODUCER_ID,
            "version": PRODUCER_VERSION,
            "revision": git_commit,
        },
        "client": {
            "lane": lane,
            "profileId": f"yt-dlp:{LANE_TO_YTDLP_CLIENT[lane]}",
            "profileVersion": extraction.oracle_version,
        },
        "videoCase": {
            "caseClass": case_class,
            "caseRevision": case_revision,
            "providerContentIdSha256": _sha256_text(video_id),
        },
        "environment": {
            "runnerKind": "GITHUB_HOSTED",
            "runnerImage": runner_image,
            "os": "linux",
            "arch": os.uname().machine if hasattr(os, "uname") else "unknown",
            "networkClass": "PUBLIC_EGRESS_REDACTED",
        },
        "references": [{
            "id": "YT_DLP",
            "version": extraction.oracle_version,
            "revision": extraction.oracle_revision,
            "role": "REFERENCE_ORACLE",
            "productionDependency": False,
        }],
        "budget": {
            "metadataRequestLimit": MAX_METADATA_REQUESTS,
            "metadataRequestsUsed": extraction.metadata_requests_used,
            "mediaProbeByteLimit": MAX_MEDIA_PROBE_BYTES,
            "mediaProbes": [
                {
                    "probeId": item.probe_id,
                    "purpose": item.purpose,
                    "byteLimit": item.byte_limit,
                    "bytesRead": item.bytes_read,
                }
                for item in summary.media_probes
            ],
        },
        "capabilities": summary.capabilities,
        "requirements": summary.requirements,
        "providerSignals": summary.provider_signals,
        "delivery": summary.delivery,
        "outcome": summary.outcome,
        "guardrails": {
            "accountCredentialsUsed": False,
            "ipRotationOrProxyEvasionUsed": False,
            "antiBotBypassImplemented": False,
            "requestRateCamouflageUsed": False,
            "fullDownloadAttempted": False,
        },
        "limitations": summary.limitations,
    }
    validate_probe_document(artifact)
    return artifact


def run_live_probe(
    *,
    video_id: str,
    case_class: str,
    case_revision: str,
    lane: str,
    git_commit: str,
    runner_image: str,
    media_probe_mode: str,
) -> dict[str, Any]:
    if not video_id or any(ch.isspace() for ch in video_id):
        raise ProbeRuntimeError("video_id must be a non-empty provider identifier without whitespace")
    extraction = _extract_with_yt_dlp(video_id, lane)
    formats = []
    if extraction.info and isinstance(extraction.info.get("formats"), list):
        formats = [
            item for item in extraction.info["formats"]
            if isinstance(item, Mapping)
        ]
    media = _run_media_probes(formats, mode=media_probe_mode)
    summary = _summary_from_extraction(extraction, media)
    return _build_artifact(
        video_id=video_id,
        case_class=case_class,
        case_revision=case_revision,
        lane=lane,
        git_commit=git_commit,
        runner_image=runner_image,
        extraction=extraction,
        summary=summary,
    )


def _write_artifact(path: pathlib.Path, artifact: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Validation happens again immediately before persistence.
    validate_probe_document(artifact)
    path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    live = sub.add_parser("live", help="run one bounded anonymous live-provider probe")
    live.add_argument("--video-id", required=True)
    live.add_argument("--case-class", required=True, choices=("V0", "V1", "V2"))
    live.add_argument("--case-revision", required=True)
    live.add_argument("--lane", required=True, choices=tuple(LANE_TO_YTDLP_CLIENT))
    live.add_argument("--git-commit", required=True)
    live.add_argument("--runner-image", default=os.environ.get("ImageOS", "ubuntu-24.04"))
    live.add_argument("--media-probe-mode", choices=("none", "startup", "range-set"), default="range-set")
    live.add_argument("--output", required=True)
    return parser


def main() -> int:
    args = _build_parser().parse_args()
    try:
        if args.command == "live":
            artifact = run_live_probe(
                video_id=args.video_id,
                case_class=args.case_class,
                case_revision=args.case_revision,
                lane=args.lane,
                git_commit=args.git_commit,
                runner_image=args.runner_image,
                media_probe_mode=args.media_probe_mode,
            )
            _write_artifact(pathlib.Path(args.output), artifact)
            print(
                "M3-A1 bounded probe emitted: "
                f"lane={args.lane} case={args.case_class} "
                f"outcome={artifact['outcome']['classification']}"
            )
            return 0
    except (ProbeRuntimeError, M3ContractError) as exc:
        print(f"M3-A1 probe failed closed: {exc}", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
