#!/usr/bin/env python3
"""Minimal stand-in for the Hermes dashboard HTTP surface.

Serves only what the SSH lifecycle touches:
  GET /            HTML with `window.__HERMES_SESSION_TOKEN__ = "<token>"`
  GET /api/status  readiness + identity (auth_required, install_id, profile)
  GET /health      liveness

Stdlib only, no dependencies.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2])
    token = sys.argv[3] if len(sys.argv) > 3 else ""
    profile = sys.argv[4] if len(sys.argv) > 4 else "default"

    index = (
        "<!doctype html><html><head><meta charset=\"utf-8\">"
        f"<script>window.__HERMES_SESSION_TOKEN__ = {json.dumps(token)};</script>"
        "</head><body><div id=\"root\">hermes dashboard (fixture)</div></body></html>"
    )
    status = json.dumps(
        {
            "status": "ok",
            "auth_required": True,
            "install_id": "fixture-install-id",
            "profile": profile,
            "auth_providers": ["basic"],
            "auth_flows": [],
            "supports_password": True,
        }
    )

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _send(self, code: int, body: str, ctype: str) -> None:
            payload = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self) -> None:  # noqa: N802
            path = self.path.split("?", 1)[0]
            if path == "/":
                self._send(200, index, "text/html; charset=utf-8")
            elif path == "/api/status":
                self._send(200, status, "application/json")
            elif path == "/health":
                self._send(200, json.dumps({"status": "ok"}), "application/json")
            else:
                self._send(404, json.dumps({"error": "not found"}), "application/json")

        def log_message(self, format, *args) -> None:  # silence
            return

    with ThreadingHTTPServer((host, port), Handler) as httpd:
        httpd.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())