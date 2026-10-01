#!/usr/bin/env python3
"""Deterministic raw HTTP/1.1 response-contract origin for M2-G1.

This is deliberately separate from Media Lab. It serves malformed protocol
responses that a standards-compliant fixture server should never emit and
records only normalized request-contract fields needed by the G1 oracle.
It is correctness tooling only; never use it for G2 timing measurements.
"""
from __future__ import annotations

import argparse
import json
import socket
import socketserver
import threading
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

RESOURCE_LENGTH = 81_811
MAX_HEADER_BYTES = 64 * 1024


@dataclass(frozen=True)
class ResponsePlan:
    status: int
    reason: str
    headers: tuple[tuple[str, str], ...]
    body_length: int
    chunked: bool = False


def response_plan(path: str) -> ResponsePlan:
    if path == "/redirect":
        return ResponsePlan(
            302, "Found",
            (("Location", "/binding"), ("Content-Length", "0")),
            0,
        )
    if path == "/wrong-content-range":
        return ResponsePlan(
            206, "Partial Content",
            (
                ("Content-Range", "bytes 1-81810/81811"),
                ("Content-Length", "0"),
            ),
            0,
        )
    if path == "/overlong-body":
        return ResponsePlan(
            206, "Partial Content",
            (
                ("Content-Range", "bytes 0-81810/81811"),
                ("Transfer-Encoding", "chunked"),
            ),
            RESOURCE_LENGTH + 1,
            chunked=True,
        )
    if path == "/binding":
        return ResponsePlan(
            206, "Partial Content",
            (
                ("Content-Range", "bytes 0-81810/81811"),
                ("Content-Length", str(RESOURCE_LENGTH)),
            ),
            RESOURCE_LENGTH,
        )
    raise KeyError(path)


def _read_request(sock: socket.socket) -> tuple[str, dict[str, str]]:
    data = bytearray()
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("peer closed before request headers")
        data.extend(chunk)
        if len(data) > MAX_HEADER_BYTES:
            raise ValueError("request headers too large")
    head = bytes(data).split(b"\r\n\r\n", 1)[0].decode("iso-8859-1")
    lines = head.split("\r\n")
    request = lines[0].split(" ")
    if len(request) != 3 or request[0] != "GET":
        raise ValueError("only GET HTTP/1.x is accepted")
    path = urlsplit(request[1]).path
    headers: dict[str, str] = {}
    for line in lines[1:]:
        name, sep, value = line.partition(":")
        if not sep:
            raise ValueError("malformed request header")
        headers[name.strip().lower()] = value.strip()
    return path, headers


def _send_all(sock: socket.socket, data: bytes) -> int:
    written = 0
    view = memoryview(data)
    while written < len(view):
        count = sock.send(view[written:])
        if count <= 0:
            raise ConnectionError("socket write returned zero")
        written += count
    return written


class FaultServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address: tuple[str, int], trace: Path):
        super().__init__(address, FaultHandler)
        self.trace = trace
        self._lock = threading.Lock()
        self._request_id = 0
        trace.parent.mkdir(parents=True, exist_ok=True)
        trace.write_text("", encoding="utf-8")

    def next_request_id(self) -> int:
        with self._lock:
            self._request_id += 1
            return self._request_id

    def append_trace(self, row: dict[str, object]) -> None:
        line = json.dumps(row, sort_keys=True, separators=(",", ":"))
        with self._lock:
            with self.trace.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")


class FaultHandler(socketserver.BaseRequestHandler):
    server: FaultServer

    def handle(self) -> None:
        self.request.settimeout(15)
        try:
            path, headers = _read_request(self.request)
        except (ConnectionError, OSError, ValueError):
            return

        if path == "/__health":
            _send_all(
                self.request,
                b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nok\n",
            )
            return

        request_id = self.server.next_request_id()
        try:
            plan = response_plan(path)
        except KeyError:
            plan = ResponsePlan(404, "Not Found", (("Content-Length", "0"),), 0)

        response_headers = [
            f"HTTP/1.1 {plan.status} {plan.reason}",
            f"X-Sponge-Lab-Request: {request_id}",
            "Cache-Control: no-store",
            "Connection: close",
            *[f"{name}: {value}" for name, value in plan.headers],
            "",
            "",
        ]
        body_written = 0
        outcome = "COMPLETE"
        try:
            _send_all(self.request, "\r\n".join(response_headers).encode("ascii"))
            if plan.body_length:
                body = b"G" * plan.body_length
                if plan.chunked:
                    prefix = f"{len(body):X}\r\n".encode("ascii")
                    _send_all(self.request, prefix)
                    body_written = _send_all(self.request, body)
                    _send_all(self.request, b"\r\n0\r\n\r\n")
                else:
                    body_written = _send_all(self.request, body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            outcome = "CLIENT_CLOSED"
        finally:
            self.server.append_trace({
                "schemaVersion": 1,
                "requestId": request_id,
                "method": "GET",
                "path": path,
                "rangeHeader": headers.get("range"),
                "acceptEncoding": headers.get("accept-encoding"),
                "bindingRevision": headers.get("x-sponge-binding-revision"),
                "fetchKey": headers.get("x-sponge-fetch-key"),
                "attemptHeader": headers.get("x-sponge-attempt"),
                "status": plan.status,
                "plannedResponseBytes": plan.body_length,
                "bodyBytesWritten": body_written,
                "responseVariant": path.removeprefix("/"),
                "outcome": outcome,
            })


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind-address", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--trace", type=Path, required=True)
    args = parser.parse_args()
    with FaultServer((args.bind_address, args.port), args.trace) as server:
        server.serve_forever(poll_interval=0.1)


if __name__ == "__main__":
    main()
