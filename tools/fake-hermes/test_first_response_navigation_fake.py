"""Verify the synthetic network lab itself, NOT Mercury behavior or real data."""
import concurrent.futures
import threading
import time
import unittest
from unittest.mock import patch

import first_response_navigation_fake as lab
from test_fake_hermes import _json_request, _open_websocket, _prompt_completion, _rpc


class FirstResponseNavigationFakeWireTests(unittest.TestCase):
    def setUp(self):
        lab.reset()
        self.ws_patch = patch.object(lab.base, "WsSession", lab.NavigationSession)
        self.ws_patch.start()
        self.server = lab.base.SafeThreadingHTTPServer(("127.0.0.1", 0), lab.NavigationHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.server.server_port}"
        self.token = lab.base.mint_session_token()
        self.sockets = []

    def tearDown(self):
        lab.RELEASE.set()
        for sock in self.sockets:
            sock.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.ws_patch.stop()

    def control(self, body=None):
        return _json_request(self.origin, "POST" if body else "GET", lab.CONTROL, body=body)[1]

    def history(self, limit=100, offset=0):
        return _json_request(self.origin, "GET",
                             f"/api/sessions/e2e-session-1/messages?order=latest&limit={limit}&offset={offset}",
                             token=self.token)[1]

    def resume(self):
        ticket = _json_request(self.origin, "POST", "/api/auth/ws-ticket", token=self.token)[1]["ticket"]
        sock = _open_websocket(self.origin, ticket)
        self.sockets.append(sock)
        result = _rpc(sock, 1, "session.resume", {"session_id": "e2e-session-1"})
        return sock, result

    def drive(self, mode):
        self.control({"action": "reset", "mode": mode})
        self.assertEqual(self.history()["messages"], [])
        first, _ = self.resume()
        self.assertEqual(self.history()["messages"], [])
        _, completion = _prompt_completion(first, 2, lab.PROMPT)
        self.assertEqual(completion["text"], lab.ANSWER)
        first.close()  # true socket leave; next socket is a new attachment
        self.control({"action": "arm"})
        initial = self.history()["messages"]
        self.assertEqual(initial[-1]["content"], lab.ANSWER)
        _, resumed = self.resume()
        self.assertEqual(resumed["messages"][-1]["text"], lab.ANSWER)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.history)
            deadline = time.monotonic() + 1
            while not self.control()["held"] and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(self.control()["held"])
            self.assertFalse(pending.done())
            self.control({"action": "release"})
            followup = pending.result(timeout=2)["messages"]
        state = self.control()
        self.assertEqual(state["rpc_counts"]["session.resume"], 2)
        self.assertEqual(state["rpc_counts"]["prompt.submit"], 1)
        self.assertEqual([r["phase"] for r in state["reads"]],
                         ["initial-or-other"] * 3 + ["post-resume"])
        self.assertEqual(state["reads"][-1]["offset"], 0)
        return initial, followup

    def test_fresh_network_order_keeps_both_assistant_segments(self):
        initial, followup = self.drive("fresh")
        self.assertEqual(initial, followup)
        self.assertEqual([r["role"] for r in followup], ["user", "assistant", "tool", "assistant"])

    def test_stale_network_order_is_explicitly_synthetic(self):
        initial, followup = self.drive("stale")
        self.assertEqual(followup, initial[:3])
        self.assertTrue(self.control()["synthetic"])

    def test_duplicate_rows_and_latest_offsets_are_not_deduped_by_fake(self):
        initial, followup = self.drive("duplicate")
        self.assertEqual(initial, followup)
        self.assertEqual(followup[-1]["content"], followup[-2]["content"])
        self.assertNotEqual(followup[-1]["id"], followup[-2]["id"])
        newest = self.history(limit=2)["messages"]
        older = self.history(limit=2, offset=2)["messages"]
        oldest = self.history(limit=2, offset=4)["messages"]
        self.assertEqual([r["id"] for r in oldest + older + newest], [1, 2, 3, 4, 5])


if __name__ == "__main__":
    unittest.main()
