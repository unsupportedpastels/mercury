import unittest
import json
from pathlib import Path
import tempfile
import threading
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


if __name__ == "__main__":
    unittest.main()