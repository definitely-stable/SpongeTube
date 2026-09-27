#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BODIES = {
    "media": b"SPONGETUBE_M2_E0_MEDIA_V1\n",
    "control": b"SPONGETUBE_M2_E0_CONTROL_V1\n",
}


class Recorder:
    def __init__(self, path: Path, role: str) -> None:
        self._path = path
        self._role = role
        self._lock = threading.Lock()
        self._sequence = 0
        path.parent.mkdir(parents=True, exist_ok=True)

    def append(self, method: str, path: str) -> None:
        with self._lock:
            self._sequence += 1
            event = {
                "schemaVersion": 1,
                "sequence": self._sequence,
                "role": self._role,
                "method": method,
                "path": path,
                "hostMonotonicNs": time.monotonic_ns(),
            }
            with self._path.open("a", encoding="utf-8", newline="\n") as stream:
                stream.write(json.dumps(event, sort_keys=True, separators=(",", ":")))
                stream.write("\n")


def handler_for(role: str, recorder: Recorder):
    body = BODIES[role]

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:
            recorder.append("GET", self.path)
            if self.path != "/probe":
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("X-Sponge-E0-Role", role)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:
            return

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--role", required=True, choices=sorted(BODIES))
    parser.add_argument("--log", required=True, type=Path)
    args = parser.parse_args()

    recorder = Recorder(args.log, args.role)
    server = ThreadingHTTPServer((args.bind, args.port), handler_for(args.role, recorder))
    server.daemon_threads = True
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
