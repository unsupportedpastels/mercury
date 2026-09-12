#!/usr/bin/env python3
"""SYNTHETIC first-answer navigation lab; not evidence of a real host race.

Run on the disposable simulator's Mac: python3 first_response_navigation_fake.py 8793
Uses existing fake authentication/WS framing; never connects to real Hermes.
Loopback-only test controls hold the *post-resume* REST read until UI inspection.
Fresh and stale modes differ only in that held response's final assistant rows.
"""
import json
import sys
import threading
from urllib.parse import parse_qs, urlsplit

import fake_hermes as base

ANSWER = "Synthetic first answer alpha.\n\nSynthetic first answer omega."
PROMPT = "synthetic first navigation turn"
CONTROL = "/__test__/first-response-navigation"
LOCK = threading.RLock()
RELEASE = threading.Event()
STATE = {}


def reset(mode="fresh"):
    with LOCK:
        STATE.clear()
        STATE.update(mode=mode, armed=False, resumed=False, held=False,
                     released=False, completed=False, reads=[], rpc_counts={})
        RELEASE.clear()
        base.reset_synthetic_state()


def rows():
    if not STATE["completed"]:
        return []
    result = [
        {"id": 1, "role": "user", "content": PROMPT},
        {"id": 2, "role": "assistant", "content": "Synthetic intermediate commentary.",
         "tool_calls": [{"id": "synthetic-tool", "type": "function", "function": {
             "name": "terminal", "arguments": "{}"}}]},
        {"id": 3, "role": "tool", "tool_name": "terminal", "tool_call_id": "synthetic-tool",
         "content": "Synthetic tool result."},
        {"id": 4, "role": "assistant", "content": ANSWER},
    ]
    if STATE["mode"] == "duplicate":
        result.append(dict(result[-1], id=5))
    return result


def snapshot():
    with LOCK:
        return json.loads(json.dumps(dict(STATE, synthetic=True)))


class NavigationHandler(base.Handler):
    def do_GET(self):
        parts = urlsplit(self.path)
        if parts.path == CONTROL:
            self.send_json(snapshot())
            return
        if parts.path == f"/api/sessions/{base.DURABLE_SESSION_ID}/messages":
            if not self.require_auth():
                return
            query = parse_qs(parts.query)
            limit = min(500, max(1, int(query.get("limit", [100])[0])))
            offset = max(0, int(query.get("offset", [0])[0]))
            latest = query.get("order", ["latest"])[0] == "latest"
            with LOCK:
                hold = STATE["armed"] and STATE["resumed"] and not STATE["held"]
                if hold:
                    STATE["held"] = True
                source = rows()
                if hold and STATE["mode"] == "stale":
                    source = source[:3]  # deliberately old successful durable snapshot
                selected = list(reversed(source)) if latest else source
                selected = selected[offset:offset + limit]
                if latest:
                    selected.reverse()
                receipt = dict(phase="post-resume" if hold else "initial-or-other",
                               offset=offset, limit=limit, order="latest" if latest else "oldest",
                               ids=[r["id"] for r in selected], roles=[r["role"] for r in selected])
                STATE["reads"].append(receipt)
            if hold and not RELEASE.wait(45):
                self.send_json({"error": "synthetic gate timed out"}, status=504)
                return
            self.send_json({"session_id": base.DURABLE_SESSION_ID, "messages": selected,
                            "pagination": {"limit": limit, "offset": offset,
                                           "order": receipt["order"], "returned": len(selected)}})
            if hold:
                with LOCK:
                    STATE["released"] = True
            return
        super().do_GET()

    def do_POST(self):
        if urlsplit(self.path).path != CONTROL:
            return super().do_POST()
        body = self.read_body_json()
        action = body.get("action")
        if action == "reset" and body.get("mode") in {"fresh", "stale", "duplicate"}:
            reset(body["mode"])
        elif action == "arm":
            with LOCK:
                STATE["armed"] = True
                STATE["resumed"] = False
        elif action == "release":
            RELEASE.set()
        else:
            self.send_json({"error": "invalid synthetic control"}, status=400)
            return
        self.send_json(snapshot())


class NavigationSession(base.WsSession):
    def handle_rpc(self, text):
        message = json.loads(text)
        method = message.get("method")
        with LOCK:
            counts = STATE["rpc_counts"]
            counts[method] = counts.get(method, 0) + 1
        if method == "session.resume":
            with LOCK:
                history = rows()
                if STATE["armed"]:
                    STATE["resumed"] = True
            # Official resume projects assistant/user to text, tool to name/context.
            projected = [{"role": r["role"], **(
                {"name": r["tool_name"], "context": "synthetic"} if r["role"] == "tool"
                else {"text": r["content"]})} for r in history]
            self.respond(message["id"], {"session_id": base.RUNTIME_SESSION_ID,
                         "session_key": base.DURABLE_SESSION_ID, "messages": projected,
                         "running": False, "resumed": True,
                         "info": {"provider": "fake", "model": "fake-model"}})
            return
        if method == "prompt.submit":
            self.respond(message["id"], {"status": "streaming"})
            self.push_event("message.start", {})
            self.push_event("message.delta", {"text": ANSWER})
            with LOCK:
                STATE["completed"] = True
            self.push_event("message.complete", {"text": ANSWER, "status": "completed"})
            return
        super().handle_rpc(text)


def main():
    reset()
    base.WsSession = NavigationSession
    server = base.SafeThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8793), NavigationHandler)
    try:
        server.serve_forever()
    finally:
        RELEASE.set()
        server.server_close()


if __name__ == "__main__":
    main()
