#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    server_version = "SpongeTubeM2E0/1"

    def do_GET(self) -> None:
        if self.path != "/probe":
            self.send_error(404)
            return
        body = json.dumps(
            {
                "schemaVersion": 1,
                "endpointId": "M2_E0_NAMESPACE_MEDIA",
                "payload": "spongetube-m2e0-direct",
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        # The E0 endpoint intentionally emits no address-bearing access log.
        return


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    if not (1 <= args.port <= 65535):
        parser.error("--port must be in 1..65535")

    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
