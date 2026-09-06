#!/usr/bin/env python3
"""End-to-end verification for fake_hermes.py (stdlib only).

Walks: probe -> password login -> sessions -> transcript -> ws-ticket
       -> WS session.resume -> prompt.submit -> start/delta/delta
       -> session.interrupt -> sentinel message.complete.

Usage: python3 verify.py [PORT]   (default 8787; server must be running)
"""

import base64
import hashlib
import json
import os
import socket
import struct
import sys
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
BASE = f"http://127.0.0.1:{PORT}"
SENTINEL = "Operation interrupted: waiting for model response (2s elapsed)."

failures = []


def check(name, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"[{status}] {name}" + (f" -- {detail}" if detail and not condition else ""))
    if not condition:
        failures.append(name)


def http_json(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req) as resp:
        return resp.status, dict(resp.headers), json.loads(resp.read().decode())


# --- 1. probe ---------------------------------------------------------------
status, _, body = http_json("GET", "/api/status")
check("probe /api/status", status == 200 and body.get("auth_required") is True, str(body))

status, _, body = http_json("GET", "/api/auth/providers")
providers = body.get("providers", [])
check("probe /api/auth/providers advertises password",
      status == 200 and any(p.get("supports_password") for p in providers), str(body))

# --- 2. password login ------------------------------------------------------
try:
    http_json("POST", "/auth/password-login",
              {"provider": "local", "username": "e2e", "password": "wrong", "next": "/"})
    check("wrong password rejected", False)
except urllib.error.HTTPError as e:
    check("wrong password rejected", e.code == 401, str(e.code))

status, headers, body = http_json(
    "POST", "/auth/password-login",
    {"provider": "local", "username": "e2e", "password": "e2epass", "next": "/"})
set_cookie = headers.get("Set-Cookie", "")
cookie = set_cookie.split(";")[0]
check("password login sets HttpOnly cookie",
      status == 200 and cookie.startswith("hermes_session=") and "HttpOnly" in set_cookie,
      set_cookie)
auth = {"Cookie": cookie}

# --- 3. authed endpoints ----------------------------------------------------
status, _, body = http_json("GET", "/api/auth/me", headers=auth)
check("/api/auth/me", status == 200 and body.get("user_id"), str(body))

try:
    http_json("GET", "/api/auth/me")
    check("/api/auth/me rejects unauthenticated", False)
except urllib.error.HTTPError as e:
    check("/api/auth/me rejects unauthenticated", e.code == 401, str(e.code))

status, _, body = http_json("GET", "/api/profiles/sessions?limit=20&profile=default", headers=auth)
sessions = body.get("sessions", [])
check("sessions list has e2e-session-1",
      status == 200 and len(sessions) == 1 and sessions[0]["id"] == "e2e-session-1"
      and sessions[0]["title"] == "E2E Session", str(body))

status, _, body = http_json(
    "GET", "/api/sessions/e2e-session-1/messages?limit=100&order=latest&profile=default",
    headers=auth)
check("transcript empty", status == 200 and body.get("messages") == [], str(body))

status, _, body = http_json("GET", "/api/profiles", headers=auth)
check("/api/profiles", status == 200 and body.get("profiles"), str(body))

status, _, body = http_json("GET", "/api/model/options?profile=default&explicit_only=1", headers=auth)
check("/api/model/options", status == 200 and body.get("providers"), str(body))

status, _, body = http_json("GET", "/api/config?profile=default", headers=auth)
check("/api/config", status == 200 and "agent" in body, str(body))

# --- 4. ws ticket -----------------------------------------------------------
status, _, body = http_json("POST", "/api/auth/ws-ticket", headers=auth)
ticket = body.get("ticket")
check("ws-ticket minted", status == 200 and ticket and body.get("ttl_seconds", 0) > 0, str(body))


# --- 5. tiny WebSocket client ----------------------------------------------
class WsClient:
    def __init__(self, host, port, path):
        self.sock = socket.create_connection((host, port), timeout=15)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("handshake failed")
            response += chunk
        head = response.split(b"\r\n\r\n", 1)[0].decode()
        if "101" not in head.split("\r\n")[0]:
            raise ConnectionError("no 101: " + head)
        expect = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        if f"Sec-WebSocket-Accept: {expect}" not in head:
            raise ConnectionError("bad Sec-WebSocket-Accept")
        self.buffer = response.split(b"\r\n\r\n", 1)[1]

    def _read_exact(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("closed")
            self.buffer += chunk
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def send_text(self, text):
        payload = text.encode()
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        n = len(payload)
        if n < 126:
            header = bytes([0x81, 0x80 | n])
        elif n < 1 << 16:
            header = bytes([0x81, 0x80 | 126]) + struct.pack(">H", n)
        else:
            header = bytes([0x81, 0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(header + mask + masked)

    def recv_text(self):
        while True:
            head = self._read_exact(2)
            opcode, length = head[0] & 0x0F, head[1] & 0x7F
            if length == 126:
                (length,) = struct.unpack(">H", self._read_exact(2))
            elif length == 127:
                (length,) = struct.unpack(">Q", self._read_exact(8))
            payload = self._read_exact(length) if length else b""
            if opcode == 0x1:
                return json.loads(payload.decode())
            if opcode == 0x8:
                raise ConnectionError("server closed")
            # ignore ping/pong

    def close(self):
        mask = os.urandom(4)
        self.sock.sendall(bytes([0x88, 0x80]) + mask)
        self.sock.close()


ws = WsClient("127.0.0.1", PORT, f"/api/ws?ticket={ticket}")
check("WS handshake with ticket", True)

# ticket must be single-use
try:
    WsClient("127.0.0.1", PORT, f"/api/ws?ticket={ticket}")
    check("ticket is single-use", False)
except ConnectionError:
    check("ticket is single-use", True)

# --- 6. session.resume ------------------------------------------------------
ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.resume",
                         "params": {"session_id": "e2e-session-1",
                                    "profile": "default",
                                    "close_on_disconnect": False}}))
msg = ws.recv_text()
result = msg.get("result", {})
check("session.resume result",
      msg.get("id") == 1 and result.get("session_id") == "rt-e2e-1"
      and result.get("session_key") == "e2e-session-1" and result.get("resumed") is True,
      json.dumps(msg))

# --- 7. prompt.submit + stream + interrupt ---------------------------------
ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "prompt.submit",
                         "params": {"session_id": "rt-e2e-1", "text": "What is the answer?"}}))

events = []
ack = None
while ack is None or len([e for e in events if e[0] == "message.delta"]) < 2:
    msg = ws.recv_text()
    if msg.get("id") == 2:
        ack = msg
    elif msg.get("method") == "event":
        p = msg["params"]
        events.append((p["type"], p.get("payload", {})))

check("prompt.submit ack", ack and ack["result"].get("status") == "streaming", json.dumps(ack))
check("message.start received", events and events[0][0] == "message.start", str(events))
deltas = [p.get("text") for t, p in events if t == "message.delta"]
check("two deltas 'The answer ' + 'is 42'", deltas == ["The answer ", "is 42"], str(deltas))

# --- 8. interrupt -> sentinel complete -------------------------------------
ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "session.interrupt",
                         "params": {"session_id": "rt-e2e-1"}}))
interrupt_ack = None
complete = None
import time as _time
deadline = _time.time() + 5
while (interrupt_ack is None or complete is None) and _time.time() < deadline:
    msg = ws.recv_text()
    if msg.get("id") == 3:
        interrupt_ack = msg
    elif msg.get("method") == "event" and msg["params"]["type"] == "message.complete":
        complete = msg["params"]["payload"]

check("session.interrupt ack",
      interrupt_ack and interrupt_ack["result"].get("status") == "ok", json.dumps(interrupt_ack))
check("sentinel message.complete",
      complete and complete.get("text") == SENTINEL and complete.get("status") == "interrupted",
      json.dumps(complete))

# --- 9. unknown method -> -32601 -------------------------------------------
ws.send_text(json.dumps({"jsonrpc": "2.0", "id": 4, "method": "does.not.exist", "params": {}}))
msg = ws.recv_text()
check("unknown method returns -32601",
      msg.get("id") == 4 and msg.get("error", {}).get("code") == -32601, json.dumps(msg))

ws.close()

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {failures}")
    sys.exit(1)
print("ALL CHECKS PASSED")
