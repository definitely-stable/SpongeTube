#!/usr/bin/env python3
"""Small stdlib-only Toxiproxy HTTP client for the M2-E transport lab.

Raw listen/upstream addresses are orchestration inputs only. The portable
normalized state deliberately excludes them.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

TIMEOUT_SECONDS = 5.0
SUPPORTED_TOXICS = {
    "timeout": {"timeout"},
    "reset_peer": {"timeout"},
    "limit_data": {"bytes"},
    "slow_close": {"delay"},
}


class ToxiproxyError(RuntimeError):
    pass


def request(api: str, method: str, path: str, body: Any | None = None) -> Any:
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(
        api.rstrip("/") + path,
        data=payload,
        method=method,
        headers={"Content-Type": "application/json"} if payload is not None else {},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise ToxiproxyError(f"{method} {path}: HTTP {error.code}: {detail}") from error
    except OSError as error:
        raise ToxiproxyError(f"{method} {path}: {error}") from error
    if not raw:
        return None
    return json.loads(raw)


def list_proxies(api: str) -> Any:
    return request(api, "GET", "/proxies")


def create_proxy(api: str, name: str, listen: str, upstream: str) -> Any:
    return request(
        api,
        "POST",
        "/proxies",
        {"name": name, "listen": listen, "upstream": upstream, "enabled": True},
    )


def delete_proxy(api: str, name: str) -> None:
    try:
        request(api, "DELETE", f"/proxies/{urllib.parse.quote(name, safe='')}")
    except ToxiproxyError as error:
        if "HTTP 404" not in str(error):
            raise


def add_toxic(
    api: str,
    proxy: str,
    name: str,
    toxic_type: str,
    stream: str,
    attributes: dict[str, int],
) -> Any:
    if toxic_type not in SUPPORTED_TOXICS:
        raise ToxiproxyError(f"unsupported M2-E toxic {toxic_type!r}")
    if stream not in {"upstream", "downstream"}:
        raise ToxiproxyError(f"invalid stream {stream!r}")
    if set(attributes) != SUPPORTED_TOXICS[toxic_type]:
        raise ToxiproxyError(
            f"{toxic_type} attributes {sorted(attributes)} != "
            f"{sorted(SUPPORTED_TOXICS[toxic_type])}"
        )
    if any(isinstance(v, bool) or not isinstance(v, int) or v < 0 for v in attributes.values()):
        raise ToxiproxyError("toxic attributes must be non-negative integers")
    return request(
        api,
        "POST",
        f"/proxies/{urllib.parse.quote(proxy, safe='')}/toxics",
        {
            "name": name,
            "type": toxic_type,
            "stream": stream,
            "toxicity": 1.0,
            "attributes": attributes,
        },
    )


def remove_toxic(api: str, proxy: str, name: str) -> None:
    try:
        request(
            api,
            "DELETE",
            f"/proxies/{urllib.parse.quote(proxy, safe='')}/toxics/"
            f"{urllib.parse.quote(name, safe='')}",
        )
    except ToxiproxyError as error:
        if "HTTP 404" not in str(error):
            raise


def get_proxy(api: str, name: str) -> dict[str, Any]:
    value = request(api, "GET", f"/proxies/{urllib.parse.quote(name, safe='')}")
    if not isinstance(value, dict):
        raise ToxiproxyError("proxy response is not an object")
    return value


def normalized_proxy_state(value: dict[str, Any]) -> dict[str, Any]:
    toxics = value.get("toxics") or []
    if not isinstance(toxics, list):
        raise ToxiproxyError("proxy toxics is not an array")
    normalized = []
    for toxic in toxics:
        if not isinstance(toxic, dict):
            raise ToxiproxyError("toxic is not an object")
        toxic_type = toxic.get("type")
        if toxic_type not in SUPPORTED_TOXICS:
            raise ToxiproxyError(f"unexpected toxic type in readback: {toxic_type!r}")
        attributes = toxic.get("attributes") or {}
        if set(attributes) != SUPPORTED_TOXICS[toxic_type]:
            raise ToxiproxyError("unexpected toxic attributes in readback")
        normalized.append(
            {
                "name": str(toxic.get("name")),
                "type": toxic_type,
                "stream": str(toxic.get("stream")),
                "toxicityPpm": round(float(toxic.get("toxicity", 1.0)) * 1_000_000),
                "attributes": {key: int(attributes[key]) for key in sorted(attributes)},
            }
        )
    normalized.sort(key=lambda item: item["name"])
    return {
        "name": str(value.get("name")),
        "enabled": bool(value.get("enabled")),
        "toxics": normalized,
    }


def write_json(path: str, value: Any) -> None:
    encoded = json.dumps(value, indent=2, sort_keys=True) + "\n"
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(path).write_text(encoded, encoding="utf-8")


def parse_attributes(values: list[str]) -> dict[str, int]:
    result: dict[str, int] = {}
    for item in values:
        key, sep, raw = item.partition("=")
        if not sep or not key or not raw.isdigit():
            raise ToxiproxyError(f"invalid --attribute {item!r}")
        result[key] = int(raw)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create")
    create.add_argument("--name", required=True)
    create.add_argument("--listen", required=True)
    create.add_argument("--upstream", required=True)

    delete = sub.add_parser("delete")
    delete.add_argument("--name", required=True)

    toxic = sub.add_parser("add-toxic")
    toxic.add_argument("--proxy", required=True)
    toxic.add_argument("--name", required=True)
    toxic.add_argument("--type", required=True, choices=sorted(SUPPORTED_TOXICS))
    toxic.add_argument("--stream", required=True, choices=["upstream", "downstream"])
    toxic.add_argument("--attribute", action="append", default=[])

    remove = sub.add_parser("remove-toxic")
    remove.add_argument("--proxy", required=True)
    remove.add_argument("--name", required=True)

    inspect = sub.add_parser("inspect")
    inspect.add_argument("--proxy", required=True)
    inspect.add_argument("--output", required=True)

    clean = sub.add_parser("assert-clean")
    clean.add_argument("--proxy", required=False)

    args = parser.parse_args()
    try:
        if args.command == "create":
            delete_proxy(args.api, args.name)
            create_proxy(args.api, args.name, args.listen, args.upstream)
        elif args.command == "delete":
            delete_proxy(args.api, args.name)
        elif args.command == "add-toxic":
            remove_toxic(args.api, args.proxy, args.name)
            add_toxic(
                args.api,
                args.proxy,
                args.name,
                args.type,
                args.stream,
                parse_attributes(args.attribute),
            )
        elif args.command == "remove-toxic":
            remove_toxic(args.api, args.proxy, args.name)
        elif args.command == "inspect":
            write_json(args.output, normalized_proxy_state(get_proxy(args.api, args.proxy)))
        elif args.command == "assert-clean":
            proxies = list_proxies(args.api)
            if args.proxy:
                if isinstance(proxies, dict) and args.proxy in proxies:
                    state = normalized_proxy_state(proxies[args.proxy])
                    if state["toxics"]:
                        raise ToxiproxyError(f"proxy still has toxics: {state['toxics']}")
            else:
                if proxies not in ({}, []):
                    raise ToxiproxyError(f"unexpected proxies remain: {proxies!r}")
    except ToxiproxyError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
