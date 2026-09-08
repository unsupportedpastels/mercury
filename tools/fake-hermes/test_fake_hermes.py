import base64
import json
from pathlib import Path
import os
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import fake_hermes


class SafeLoggingTest(unittest.TestCase):
    def test_http_summary_drops_query_values(self):
        summary = fake_hermes.http_log_summary("GET", "/api/ws?ticket=single-use-secret")
        self.assertEqual(summary, "HTTP GET /api/ws")
        self.assertNotIn("single-use-secret", summary)

    def test_rpc_summary_drops_params_and_payloads(self):
        prompt = '{"jsonrpc":"2.0","id":2,"method":"prompt.submit","params":{"text":"private prompt"}}'
        event = '{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"text":"private transcript"}}}'
        self.assertEqual(fake_hermes.rpc_log_summary("recv", prompt), "WS recv: request prompt.submit id=2")
        self.assertEqual(fake_hermes.rpc_log_summary("send", event), "WS send: event message.delta")
        self.assertNotIn("private prompt", fake_hermes.rpc_log_summary("recv", prompt))
        self.assertNotIn("private transcript", fake_hermes.rpc_log_summary("send", event))


class VideoFixtureTest(unittest.TestCase):
    def test_video_fixture_requires_auth_and_only_serves_fixed_path(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture.mp4"
            fixture.write_bytes(b"synthetic-video-fixture")
            with patch.object(fake_hermes, "VIDEO_FIXTURE_FILE", str(fixture), create=True):
                server = fake_hermes.ThreadingHTTPServer(("127.0.0.1", 0), fake_hermes.Handler)
                worker = threading.Thread(target=server.serve_forever, daemon=True)
                worker.start()
                origin = f"http://127.0.0.1:{server.server_port}"
                try:
                    with self.assertRaises(HTTPError) as failure:
                        urlopen(origin + "/api/files/download?path=/tmp/mercury-test-video.mp4", timeout=2)
                    self.assertEqual(failure.exception.code, 401)
                    token = fake_hermes.mint_session_token()
                    headers = {"Authorization": "Bearer " + token}
                    with urlopen(Request(origin + "/api/files/download?path=/tmp/mercury-test-video.mp4", headers=headers), timeout=2) as response:
                        self.assertEqual(response.headers["Content-Type"], "video/mp4")
                        self.assertEqual(response.read(), fixture.read_bytes())
                    with self.assertRaises(HTTPError) as failure:
                        urlopen(Request(origin + "/api/files/download?path=/etc/passwd", headers=headers), timeout=2)
                    self.assertEqual(failure.exception.code, 404)
                    with urlopen(Request(origin + "/api/sessions/e2e-session-1/messages", headers=headers), timeout=2) as response:
                        messages = json.load(response)["messages"]
                    self.assertIn("MEDIA: /tmp/mercury-test-video.mp4", messages[0]["content"])
                finally:
                    server.shutdown()
                    server.server_close()
                    worker.join(timeout=2)


class FakeHermesServer:
    def __init__(self):
        self.server = fake_hermes.ThreadingHTTPServer(("127.0.0.1", 0), fake_hermes.Handler)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def origin(self):
        return f"http://127.0.0.1:{self.server.server_port}"

    def __enter__(self):
        self.worker.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=2)


def _json_request(origin, method, path, token=None, body=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode("utf-8")
    request = Request(origin + path, method=method, headers=headers, data=body)
    with urlopen(request, timeout=2) as response:
        return response.status, json.load(response)


def _read_exact(sock, count):
    chunks = bytearray()
    while len(chunks) < count:
        chunk = sock.recv(count - len(chunks))
        if not chunk:
            raise ConnectionError("websocket peer closed")
        chunks.extend(chunk)
    return bytes(chunks)


def _open_websocket(origin, ticket):
    url = origin.removeprefix("http://")
    host, port = url.rsplit(":", 1)
    sock = socket.create_connection((host, int(port)), timeout=2)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    request = (
        f"GET /api/ws?ticket={ticket} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    ).encode("ascii")
    sock.sendall(request)
    response = bytearray()
    while b"\r\n\r\n" not in response:
        response.extend(sock.recv(4096))
    if not response.startswith(b"HTTP/1.1 101"):
        raise AssertionError(response.decode("ascii", errors="replace"))
    return sock


def _send_websocket_json(sock, value):
    payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
    mask = os.urandom(4)
    length = len(payload)
    if length < 126:
        header = bytes((0x81, 0x80 | length))
    elif length < 65536:
        header = bytes((0x81, 0xFE)) + length.to_bytes(2, "big")
    else:
        header = bytes((0x81, 0xFF)) + length.to_bytes(8, "big")
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    sock.sendall(header + mask + masked)


def _receive_websocket_json(sock):
    first, second = _read_exact(sock, 2)
    opcode = first & 0x0F
    length = second & 0x7F
    if length == 126:
        length = int.from_bytes(_read_exact(sock, 2), "big")
    elif length == 127:
        length = int.from_bytes(_read_exact(sock, 8), "big")
    payload = _read_exact(sock, length) if length else b""
    if opcode == 0x8:
        raise ConnectionError("websocket closed")
    if opcode != 0x1:
        return _receive_websocket_json(sock)
    return json.loads(payload.decode("utf-8"))


def _rpc(sock, request_id, method, params=None):
    _send_websocket_json(sock, {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
        "params": params or {},
    })
    while True:
        message = _receive_websocket_json(sock)
        if message.get("id") == request_id:
            if "error" in message:
                raise AssertionError(message["error"])
            return message["result"]


def _prompt_completion(sock, request_id, text):
    _send_websocket_json(sock, {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": "prompt.submit",
        "params": {"session_id": fake_hermes.RUNTIME_SESSION_ID, "text": text},
    })
    response = None
    completion = None
    while response is None or completion is None:
        message = _receive_websocket_json(sock)
        if message.get("id") == request_id:
            response = message.get("result")
        params = message.get("params")
        if message.get("method") == "event" and isinstance(params, dict):
            if params.get("type") == "message.complete":
                completion = params.get("payload")
    return response, completion


def _startup_chat_socket(origin):
    token = fake_hermes.mint_session_token()
    _, ticket_payload = _json_request(origin, "POST", "/api/auth/ws-ticket", token=token)
    sock = _open_websocket(origin, ticket_payload["ticket"])
    _rpc(sock, 1, "session.resume", {
        "session_id": fake_hermes.DURABLE_SESSION_ID,
        "profile": "default",
        "close_on_disconnect": False,
    })
    return sock


def _event_type(message):
    params = message.get("params")
    return params.get("type") if isinstance(params, dict) else None


def _run_delayed_prompt_race(sock):
    _send_websocket_json(sock, {
        "jsonrpc": "2.0",
        "id": 5,
        "method": "prompt.submit",
        "params": {"session_id": fake_hermes.RUNTIME_SESSION_ID, "text": "first startup turn"},
    })
    before_first_ack = []
    while True:
        message = _receive_websocket_json(sock)
        before_first_ack.append(message)
        if message.get("id") == 5:
            raise AssertionError("first prompt ACK arrived before its completion event")
        if _event_type(message) == "message.complete":
            break

    # The second request is intentionally sent while request id 5 is still
    # outstanding. The server's read loop must accept it before the delayed
    # first response is released.
    _send_websocket_json(sock, {
        "jsonrpc": "2.0",
        "id": 6,
        "method": "prompt.submit",
        "params": {"session_id": fake_hermes.RUNTIME_SESSION_ID, "text": "second startup turn"},
    })
    after_second_prompt = []
    first_response = None
    second_response = None
    second_completion = None
    while first_response is None or second_completion is None:
        message = _receive_websocket_json(sock)
        after_second_prompt.append(message)
        if message.get("id") == 5:
            first_response = message
        elif message.get("id") == 6:
            second_response = message
        elif _event_type(message) == "message.complete":
            payload = message.get("params", {}).get("payload", {})
            if payload.get("text") == fake_hermes.STARTUP_RESPONSE_TEXTS[1] + ": second startup turn":
                second_completion = payload
    return before_first_ack, after_second_prompt, first_response, second_response, second_completion


class StartupScenarioTest(unittest.TestCase):
    def setUp(self):
        self.scenario = patch.object(fake_hermes, "FAKE_HERMES_SCENARIO", "ios-startup")
        self.scenario.start()
        fake_hermes.reset_synthetic_state()

    def tearDown(self):
        fake_hermes.reset_synthetic_state()
        self.scenario.stop()

    def test_profiles_and_synthetic_filesystem_are_bounded(self):
        with FakeHermesServer() as server:
            token = fake_hermes.mint_session_token()
            status, payload = _json_request(server.origin, "GET", "/api/profiles", token=token)
            self.assertEqual(status, 200)
            self.assertEqual([row["name"] for row in payload["profiles"]], ["default", "work"])

            status, listing = _json_request(server.origin, "GET", "/api/files", token=token)
            self.assertEqual(status, 200)
            self.assertEqual(listing["path"], fake_hermes.SYNTHETIC_FILESYSTEM_ROOT)
            workspace = next(row for row in listing["entries"] if row["name"] == "workspace")
            self.assertTrue(workspace["is_directory"])

            folder = fake_hermes.SYNTHETIC_WORKSPACE_PATH + "/ios-startup-folder"
            status, created = _json_request(
                server.origin,
                "POST",
                "/api/files/mkdir",
                token=token,
                body={"path": folder},
            )
            self.assertEqual(status, 200)
            self.assertEqual(created, {"ok": True, "path": folder})
            status, child = _json_request(
                server.origin,
                "GET",
                "/api/files?path=" + folder,
                token=token,
            )
            self.assertEqual(status, 200)
            self.assertEqual(child["path"], folder)
            self.assertEqual(child["parent"], fake_hermes.SYNTHETIC_WORKSPACE_PATH)
            self.assertEqual(child["entries"], [])

            for path in ("/etc", fake_hermes.SYNTHETIC_WORKSPACE_PATH + "/../escape"):
                with self.assertRaises(HTTPError) as failure:
                    urlopen(Request(
                        server.origin + "/api/files?path=" + path,
                        headers={"Authorization": "Bearer " + token},
                    ), timeout=2)
                self.assertEqual(failure.exception.code, 404)

    def test_rpc_profiles_projects_and_two_turn_completion(self):
        with FakeHermesServer() as server:
            token = fake_hermes.mint_session_token()
            _, ticket_payload = _json_request(
                server.origin,
                "POST",
                "/api/auth/ws-ticket",
                token=token,
            )
            sock = _open_websocket(server.origin, ticket_payload["ticket"])
            try:
                profiles = _rpc(sock, 1, "profiles.list", {"include_sessions": False})
                self.assertEqual([row["name"] for row in profiles["profiles"]], ["default", "work"])

                tree = _rpc(sock, 2, "projects.tree", {
                    "profile": "default",
                    "preview_limit": 3,
                    "session_limit": 500,
                })
                self.assertIn("projects", tree)
                self.assertEqual(tree["projects"][0]["label"], "Existing Project")

                folder = fake_hermes.SYNTHETIC_WORKSPACE_PATH + "/ios-startup-folder"
                _json_request(
                    server.origin,
                    "POST",
                    "/api/files/mkdir",
                    token=token,
                    body={"path": folder},
                )
                created = _rpc(sock, 3, "projects.create", {
                    "name": "UI Startup Project",
                    "folders": [folder],
                    "primary_path": folder,
                    "use": True,
                    "profile": "default",
                })
                self.assertEqual(created["project"]["label"], "UI Startup Project")
                self.assertEqual(created["project"]["path"], folder)

                resumed = _rpc(sock, 4, "session.resume", {
                    "session_id": fake_hermes.DURABLE_SESSION_ID,
                    "profile": "default",
                    "close_on_disconnect": False,
                })
                self.assertEqual(resumed["session_id"], fake_hermes.RUNTIME_SESSION_ID)

                first_response, first = _prompt_completion(sock, 5, "first startup turn")
                second_response, second = _prompt_completion(sock, 6, "second startup turn")
                self.assertEqual(first_response["status"], "streaming")
                self.assertEqual(second_response["status"], "streaming")
                self.assertEqual(first["status"], "completed")
                self.assertEqual(second["status"], "completed")
                self.assertEqual(first["text"], "First startup response: first startup turn")
                self.assertEqual(second["text"], "Second startup response: second startup turn")
                self.assertNotIn("Operation interrupted", second["text"])
            finally:
                sock.close()

    def test_opt_in_delayed_first_ack_accepts_second_prompt_before_ack(self):
        with patch.dict(os.environ, {
            fake_hermes.DELAY_FIRST_PROMPT_ACK_ENV: "1",
            fake_hermes.DELAY_FIRST_PROMPT_ERROR_ENV: "0",
        }, clear=False):
            with FakeHermesServer() as server:
                sock = _startup_chat_socket(server.origin)
                try:
                    before, after, first, second, second_completion = _run_delayed_prompt_race(sock)
                    self.assertFalse(any(message.get("id") == 5 for message in before))
                    self.assertEqual((first or {}).get("result", {}).get("status"), "streaming")
                    self.assertEqual((second or {}).get("result", {}).get("status"), "streaming")
                    self.assertEqual((second_completion or {}).get("status"), "completed")
                    self.assertTrue((second_completion or {}).get("text", "").startswith("Second startup response: "))
                    self.assertTrue(any(_event_type(message) == "message.complete" for message in before))
                    self.assertTrue(any(message.get("id") == 5 for message in after))
                finally:
                    sock.close()

    def test_opt_in_delayed_first_error_is_after_completion_and_second_prompt(self):
        with patch.dict(os.environ, {
            fake_hermes.DELAY_FIRST_PROMPT_ACK_ENV: "1",
            fake_hermes.DELAY_FIRST_PROMPT_ERROR_ENV: "1",
        }, clear=False):
            with FakeHermesServer() as server:
                sock = _startup_chat_socket(server.origin)
                try:
                    before, _, first, second, second_completion = _run_delayed_prompt_race(sock)
                    self.assertFalse(any(message.get("id") == 5 for message in before))
                    self.assertEqual((first or {}).get("error", {}).get("code"), -32001)
                    self.assertEqual((second or {}).get("result", {}).get("status"), "streaming")
                    self.assertTrue((second_completion or {}).get("text", "").startswith("Second startup response: "))
                finally:
                    sock.close()


class ProgressScenarioTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {
            "FAKE_HERMES_SCENARIO": "android-progress",
            "FAKE_HERMES_TEST_KEY": "synthetic-progress-control",
            "FAKE_HERMES_STARTUP": "0",
        })
        self.env.start()
        fake_hermes.reset_synthetic_state()
        self.addCleanup(self.env.stop)

    def test_history_uses_official_tool_result_rows_and_separate_evidence(self):
        rows = fake_hermes.transcript_messages()
        todo = next(row for row in rows if row.get("tool_name") == "todo_list")
        self.assertEqual(todo["role"], "tool")
        self.assertTrue(todo["tool_call_id"])
        self.assertIsInstance(todo["timestamp"], (int, float))
        result = json.loads(todo["content"])
        self.assertEqual(result["revision"], 1)
        self.assertEqual([(t["content"], t["status"]) for t in result["todos"]], [
            ("Inspect progress contract", "completed"),
            ("Verify progress recovery", "in_progress"),
            ("Install test build", "pending"),
        ])
        evidence = next(row for row in rows if row.get("tool_name") == "terminal")
        self.assertIn("Synthetic checks passed", evidence["content"])
        self.assertIn("no real checks executed", evidence["content"])
        self.assertLess(len(evidence["content"]), 2_000)
        self.assertEqual(fake_hermes.transcript_messages("work"), [])
        self.assertEqual(fake_hermes.transcript_messages(session_id="other"), [])

    def test_partial_start_is_not_the_complete_todo_snapshot(self):
        events = fake_hermes.progress_tool_events(2)
        start, complete = events
        self.assertEqual(start[0], "tool.start")
        self.assertEqual(complete[0], "tool.complete")
        self.assertEqual(start[1]["tool_id"], complete[1]["tool_id"])
        self.assertEqual(start[1]["args"]["merge"], True)
        self.assertEqual(len(start[1]["args"]["todos"]), 1)
        result = json.loads(complete[1]["result"])
        self.assertEqual(len(result["todos"]), 3)
        self.assertEqual(result["revision"], 2)

    def test_read_only_refresh_and_explicit_advance_with_counters(self):
        with FakeHermesServer() as server:
            token = fake_hermes.mint_session_token()
            control = {"X-Fake-Hermes-Test-Key": "synthetic-progress-control"}
            def state(method="GET"):
                with urlopen(Request(server.origin + "/__test__/progress", method=method,
                                     headers=control, data=b"{}" if method == "POST" else None), timeout=2) as response:
                    return json.load(response)
            before = state()
            path = "/api/sessions/e2e-session-1/messages"
            _, initial = _json_request(server.origin, "GET", path, token=token)
            _, same = _json_request(server.origin, "GET", path, token=token)
            self.assertEqual(initial, same)
            self.assertEqual(state()["revision"], 1)
            self.assertEqual(state()["history_reads"], before["history_reads"] + 2)
            self.assertEqual(state()["rpc_counts"], {})
            self.assertEqual(state("POST")["revision"], 2)
            self.assertEqual(state()["revision"], 2)
            _, updated = _json_request(server.origin, "GET", path, token=token)
            todo = [r for r in updated["messages"] if r.get("tool_name") == "todo_list"][-1]
            self.assertEqual(json.loads(todo["content"])["todos"][1]["status"], "completed")
            self.assertEqual(state()["rpc_counts"], {})
            sock = _startup_chat_socket(server.origin)
            sock.close()
            self.assertEqual(state()["rpc_counts"]["session.resume"], 1)

    def test_progress_events_cross_actual_websocket_and_count_prompt(self):
        with FakeHermesServer() as server, patch.object(fake_hermes, "PROMPT_WAIT_SECONDS", 0.01):
            sock = _startup_chat_socket(server.origin)
            try:
                _send_websocket_json(sock, {"jsonrpc": "2.0", "id": 2, "method": "prompt.submit",
                                            "params": {"text": "synthetic event contract"}})
                events = []
                while not events or _event_type(events[-1]) != "message.complete":
                    events.append(_receive_websocket_json(sock))
                tool_events = [e for e in events if _event_type(e) in ("tool.start", "tool.complete")]
                self.assertEqual([_event_type(e) for e in tool_events], ["tool.start", "tool.complete"])
                start, complete = [e["params"]["payload"] for e in tool_events]
                self.assertEqual(start["tool_id"], complete["tool_id"])
                self.assertEqual(len(json.loads(complete["result"])["todos"]), 3)
                self.assertEqual(fake_hermes.progress_test_state()["rpc_counts"]["prompt.submit"], 1)
            finally:
                sock.close()

    def test_default_prompt_preserves_interrupt_sentinel_without_todo_events(self):
        with patch.dict(os.environ, {"FAKE_HERMES_SCENARIO": "default"}), \
                patch.object(fake_hermes, "PROMPT_WAIT_SECONDS", 0.01), FakeHermesServer() as server:
            sock = _startup_chat_socket(server.origin)
            try:
                _send_websocket_json(sock, {"jsonrpc": "2.0", "id": 2, "method": "prompt.submit",
                                            "params": {"text": "synthetic default contract"}})
                events = []
                while not events or _event_type(events[-1]) != "message.complete":
                    events.append(_receive_websocket_json(sock))
                self.assertFalse(any(str(_event_type(e)).startswith("tool.") for e in events))
                self.assertEqual(events[-1]["params"]["payload"], {
                    "text": fake_hermes.SENTINEL_TEXT, "status": "interrupted"})
            finally:
                sock.close()

    def test_control_requires_explicit_test_key_not_app_auth(self):
        with FakeHermesServer() as server:
            for headers in ({}, {"X-Fake-Hermes-Test-Key": "wrong"},
                            {"Authorization": "Bearer " + fake_hermes.mint_session_token()}):
                with self.assertRaises(HTTPError) as failure:
                    urlopen(Request(server.origin + "/__test__/progress", headers=headers), timeout=2)
                self.assertEqual(failure.exception.code, 403)
            with patch.dict(os.environ, {"FAKE_HERMES_TEST_KEY": ""}):
                with self.assertRaises(HTTPError) as failure:
                    urlopen(server.origin + "/__test__/progress", timeout=2)
                self.assertEqual(failure.exception.code, 403)

    def test_default_scenario_has_no_progress_or_control_routes(self):
        with patch.dict(os.environ, {"FAKE_HERMES_SCENARIO": "default"}):
            self.assertEqual(fake_hermes.transcript_messages(), [])
            with FakeHermesServer() as server:
                for method in ("GET", "POST"):
                    with self.assertRaises(HTTPError) as failure:
                        urlopen(Request(server.origin + "/__test__/progress", method=method,
                                        data=b"{}" if method == "POST" else None), timeout=2)
                    self.assertEqual(failure.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
