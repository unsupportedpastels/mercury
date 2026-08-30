import unittest

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


if __name__ == "__main__":
    unittest.main()