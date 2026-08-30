#!/usr/bin/env python3
"""Minimal fake `hermes serve` backend for end-to-end app testing.

Stdlib only. Speaks just enough of the Hermes dashboard HTTP + WebSocket
JSON-RPC contract to satisfy both the Android client
(HermesConnectionClient / HermesChatGateway) and the iOS client
(HermesHTTPClient / StatusProbe / PasswordLoginClient / WsTicketClient /
ChatConnection).

Wire shapes were mined from:
  app/src/test/java/.../connection/HermesConnectionClientTest.kt
  app/src/test/java/.../gateway/HermesChatGatewayTest.kt
  app/src/main/java/.../connection/HermesConnectionClient.kt
  app/src/main/java/.../connection/NativeOAuth.kt  (password login)
  app/src/main/java/.../gateway/HermesChatGateway.kt
  ios/Mercury/MercuryKit/{Networking,Auth,Chat}/*.swift

Usage:  python3 fake_hermes.py [PORT]     (default 8787, cleartext HTTP)

Login:  any username, password "e2epass"  -> HttpOnly session cookie.
"""

import base64
import hashlib
import json
import secrets
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

PASSWORD = "e2epass"
DURABLE_SESSION_ID = "e2e-session-1"
DURABLE_SESSION_TITLE = "E2E Session"
RUNTIME_SESSION_ID = "rt-e2e-1"
SENTINEL_TEXT = "Operation interrupted: waiting for model response (2s elapsed)."
PROMPT_WAIT_SECONDS = 8.0
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

_lock = threading.Lock()
SESSION_TOKENS = set()      # cookie/bearer values minted by password-login
WS_TICKETS = {}             # ticket -> expiry epoch seconds (single-use)

log_lock = threading.Lock()


def log(line):
    with log_lock:
        print(line, flush=True)


def mint_session_token():
    token = secrets.token_urlsafe(24)
    with _lock:
        SESSION_TOKENS.add(token)
    return token


def mint_ws_ticket():
    ticket = secrets.token_urlsafe(24)
    with _lock:
        WS_TICKETS[ticket] = time.time() + 60
    return ticket


def consume_ws_ticket(ticket):
    with _lock:
        expiry = WS_TICKETS.pop(ticket, None)
    return expiry is not None and expiry >= time.time()


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "fake-hermes/1.0"

    # -- plumbing -----------------------------------------------------------

    def log_message(self, fmt, *args):  # route default logging through ours
        pass

    def send_json(self, obj, status=200, extra_headers=None):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or []):
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def read_body_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}

    def authed(self):
        auth = self.headers.get("Authorization") or ""
        if auth.startswith("Bearer "):
            token = auth[len("Bearer "):].strip()
            with _lock:
                if token in SESSION_TOKENS:
                    return True
        cookie = self.headers.get("Cookie") or ""
        for part in cookie.split(";"):
            name, _, value = part.strip().partition("=")
            if name == "hermes_session":
                with _lock:
                    if value in SESSION_TOKENS:
                        return True
        return False

    def require_auth(self):
        if self.authed():
            return True
        self.send_json({"error": "invalid session"}, status=401)
        return False

    # -- routing ------------------------------------------------------------

    def do_GET(self):
        parts = urlsplit(self.path)
        path, query = parts.path, parse_qs(parts.query)
        log(f"HTTP GET {self.path}")

        if path == "/api/status":
            self.send_json({
                "version": "0.20.0",
                "auth_required": True,
                "active_sessions": 0,
            })
        elif path == "/api/auth/providers":
            self.send_json({"providers": [{
                "name": "basic",
                "display_name": "Password",
                "supports_password": True,
            }]})
        elif path == "/api/auth/me":
            if self.require_auth():
                self.send_json({"user_id": "e2e-user", "provider": "basic"})
        elif path == "/api/profiles":
            if self.require_auth():
                self.send_json({"profiles": [{"name": "default"}]})
        elif path == "/api/profiles/sessions":
            if self.require_auth():
                self.send_json({
                    "sessions": [{
                        "id": DURABLE_SESSION_ID,
                        "session_key": DURABLE_SESSION_ID,
                        "title": DURABLE_SESSION_TITLE,
                        "preview": "",
                        "last_active": time.time(),
                        "message_count": 0,
                        "model": "fake-model",
                        "billing_provider": "fake",
                        "profile": "default",
                        "cwd": "/tmp/e2e",
                        "pinned": False,
                        "archived": False,
                    }],
                    "total": 1,
                    "limit": int(query.get("limit", ["20"])[0]),
                    "offset": int(query.get("offset", ["0"])[0]),
                })
        elif path.startswith("/api/sessions/") and path.endswith("/messages"):
            if self.require_auth():
                self.send_json({
                    "session_id": path.split("/")[3],
                    "messages": [],
                    "pagination": {"limit": 100, "offset": 0, "returned": 0},
                })
        elif path == "/api/model/options":
            if self.require_auth():
                self.send_json({
                    "provider": "fake",
                    "model": "fake-model",
                    "profile": query.get("profile", ["default"])[0],
                    "providers": [{
                        "slug": "fake",
                        "name": "Fake Provider",
                        "authenticated": True,
                        "models": ["fake-model"],
                        "capabilities": {
                            "fake-model": {"fast": False, "reasoning": False},
                        },
                    }],
                })
        elif path == "/api/model/info":
            if self.require_auth():
                self.send_json({
                    "profile": query.get("profile", ["default"])[0],
                    "provider": "fake",
                    "model": "fake-model",
                    "effective_context_length": 131072,
                    "capabilities": {"fast": False, "reasoning": False},
                })
        elif path == "/api/config":
            if self.require_auth():
                self.send_json({
                    "agent": {"reasoning_effort": "medium", "reasoning_overrides": {}},
                })
        elif path == "/api/ws":
            self.handle_websocket(query)
        else:
            self.send_json({"error": "not found"}, status=404)

    def do_POST(self):
        parts = urlsplit(self.path)
        path = parts.path
        log(f"HTTP POST {self.path}")

        if path == "/auth/password-login":
            body = self.read_body_json()
            if body.get("password") == PASSWORD and str(body.get("username", "")).strip():
                token = mint_session_token()
                self.send_json(
                    {"ok": True},
                    extra_headers=[(
                        "Set-Cookie",
                        f"hermes_session={token}; Path=/; HttpOnly; SameSite=Lax",
                    )],
                )
            else:
                self.send_json({"error": "invalid credentials"}, status=401)
        elif path == "/api/auth/ws-ticket":
            if self.require_auth():
                self.send_json({"ticket": mint_ws_ticket(), "ttl_seconds": 60})
        else:
            self.send_json({"error": "not found"}, status=404)

    def do_PUT(self):
        path = urlsplit(self.path).path
        log(f"HTTP PUT {self.path}")
        if path == "/api/config":
            if self.require_auth():
                self.read_body_json()
                self.send_json({})
        else:
            self.send_json({"error": "not found"}, status=404)

    # ------------------------------------------------------------------
    # WebSocket (RFC 6455, server side, text frames only, no extensions)
    # ------------------------------------------------------------------

    def handle_websocket(self, query):
        ticket = (query.get("ticket") or [""])[0]
        if not consume_ws_ticket(ticket):
            log("WS reject: bad/expired/reused ticket")
            self.send_json({"error": "invalid ticket"}, status=403)
            return
        key = self.headers.get("Sec-WebSocket-Key")
        upgrade = (self.headers.get("Upgrade") or "").lower()
        if upgrade != "websocket" or not key:
            self.send_json({"error": "expected websocket upgrade"}, status=400)
            return
        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
        ).decode("ascii")
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        log("WS open")
        try:
            WsSession(self.connection, self.rfile).run()
        except (ConnectionError, OSError) as error:
            log(f"WS transport error: {error}")
        log("WS closed")

    def do_DELETE(self):
        log(f"HTTP DELETE {self.path}")
        if self.require_auth():
            self.send_json({"ok": True})

    def do_PATCH(self):
        log(f"HTTP PATCH {self.path}")
        if self.require_auth():
            body = self.read_body_json()
            self.send_json({"ok": True, "title": body.get("title"),
                            "pinned": bool(body.get("pinned"))})


class WsSession:
    """One RFC 6455 connection speaking Hermes gateway JSON-RPC."""

    def __init__(self, sock, rfile):
        self.sock = sock
        self.rfile = rfile
        self.send_lock = threading.Lock()
        self.interrupt_event = threading.Event()
        self.prompt_thread = None
        self.closed = False

    # -- framing ------------------------------------------------------------

    def _read_exact(self, n):
        data = self.rfile.read(n)
        if data is None or len(data) < n:
            raise ConnectionError("peer closed")
        return data

    def read_frame(self):
        head = self._read_exact(2)
        opcode = head[0] & 0x0F
        masked = bool(head[1] & 0x80)
        length = head[1] & 0x7F
        if length == 126:
            (length,) = struct.unpack(">H", self._read_exact(2))
        elif length == 127:
            (length,) = struct.unpack(">Q", self._read_exact(8))
        mask = self._read_exact(4) if masked else b""
        payload = self._read_exact(length) if length else b""
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return opcode, payload

    def send_frame(self, opcode, payload=b""):
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([n])
        elif n < 1 << 16:
            header += bytes([126]) + struct.pack(">H", n)
        else:
            header += bytes([127]) + struct.pack(">Q", n)
        with self.send_lock:
            if self.closed:
                return
            self.sock.sendall(header + payload)

    def send_json(self, obj):
        text = json.dumps(obj)
        log(f"WS send: {text}")
        self.send_frame(0x1, text.encode("utf-8"))

    # -- main loop ----------------------------------------------------------

    def run(self):
        try:
            while True:
                opcode, payload = self.read_frame()
                if opcode == 0x8:  # close
                    log("WS recv: close")
                    try:
                        self.send_frame(0x8, payload[:2])
                    except (ConnectionError, OSError):
                        pass
                    break
                if opcode == 0x9:  # ping
                    log("WS recv: ping")
                    self.send_frame(0xA, payload)
                    continue
                if opcode == 0xA:  # pong
                    continue
                if opcode != 0x1:
                    continue
                text = payload.decode("utf-8", errors="replace")
                log(f"WS recv: {text}")
                self.handle_rpc(text)
        finally:
            self.closed = True
            self.interrupt_event.set()

    # -- JSON-RPC -----------------------------------------------------------

    def respond(self, request_id, result):
        self.send_json({"jsonrpc": "2.0", "id": request_id, "result": result})

    def respond_error(self, request_id, code, message):
        self.send_json({
            "jsonrpc": "2.0", "id": request_id,
            "error": {"code": code, "message": message},
        })

    def push_event(self, event_type, payload):
        self.send_json({
            "jsonrpc": "2.0",
            "method": "event",
            "params": {
                "type": event_type,
                "session_id": RUNTIME_SESSION_ID,
                "payload": payload,
            },
        })

    def handle_rpc(self, text):
        try:
            message = json.loads(text)
        except ValueError:
            return
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            return
        request_id = message.get("id")
        method = message.get("method")
        params = message.get("params") or {}
        if request_id is None or not isinstance(method, str):
            return

        if method == "session.create":
            self.respond(request_id, {
                "session_id": RUNTIME_SESSION_ID,
                "stored_session_id": DURABLE_SESSION_ID,
            })
        elif method == "session.resume":
            requested = params.get("session_id") or DURABLE_SESSION_ID
            self.respond(request_id, {
                "session_id": RUNTIME_SESSION_ID,
                "session_key": requested,
                "resumed": True,
                "messages": [],
                "running": False,
                "info": {
                    "model": "fake-model",
                    "provider": "fake",
                    "reasoning_effort": "medium",
                },
            })
        elif method == "prompt.submit":
            self.respond(request_id, {"status": "streaming"})
            self.interrupt_event.clear()
            self.prompt_thread = threading.Thread(
                target=self.stream_prompt_events, daemon=True,
            )
            self.prompt_thread.start()
        elif method == "session.interrupt":
            self.respond(request_id, {"status": "ok"})
            self.interrupt_event.set()
        elif method == "config.set":
            self.respond(request_id, {
                "key": params.get("key"),
                "value": params.get("value"),
                "scope": "session",
                "deferred": False,
                "confirm_required": False,
            })
        elif method == "model.options":
            self.respond(request_id, {
                "provider": "fake",
                "model": "fake-model",
                "providers": [{
                    "slug": "fake",
                    "name": "Fake Provider",
                    "authenticated": True,
                    "models": ["fake-model"],
                    "capabilities": {
                        "fake-model": {"fast": False, "reasoning": False},
                    },
                }],
            })
        elif method == "complete.slash":
            self.respond(request_id, {"items": [], "replace_from": 0})
        elif method == "delegation.status":
            self.respond(request_id, {"active": [], "paused": False})
        elif method == "process.list":
            self.respond(request_id, {"processes": []})
        else:
            self.respond_error(request_id, -32601, f"Method not found: {method}")

    def stream_prompt_events(self):
        try:
            self.push_event("message.start", {})
            time.sleep(0.05)
            self.push_event("message.delta", {"text": "The answer "})
            time.sleep(0.05)
            self.push_event("message.delta", {"text": "is 42"})
            interrupted = self.interrupt_event.wait(PROMPT_WAIT_SECONDS)
            if self.closed:
                return
            log("WS prompt: " + ("interrupt received" if interrupted
                                 else "wait elapsed, sending sentinel anyway"))
            self.push_event("message.complete", {
                "text": SENTINEL_TEXT,
                "status": "interrupted",
            })
        except (ConnectionError, OSError) as error:
            log(f"WS prompt stream aborted: {error}")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    server.daemon_threads = True
    log(f"fake-hermes listening on http://0.0.0.0:{port} "
        f"(password: {PASSWORD!r}, session: {DURABLE_SESSION_ID!r})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
