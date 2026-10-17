import json
import stat
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import MagicMock, patch

from api import Server, load_config, read_token, start_desktop


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.state = root / "island.json"
        self.state.write_text(json.dumps({"cpu": 42}), encoding="utf-8")
        self.notifications = root / "bildirimler.log"
        self.notifications.write_text("2026-10-17 21:00:00|BILGI|İlk\nbozuk satır\n"
                                      "2026-10-17 21:01:00|UYARI|İkinci|ayrıntı\n", encoding="utf-8")
        self.server = Server(("127.0.0.1", 0), "x" * 32,
                             {"containers": frozenset({"safe"}), "vms": frozenset({"W11"}), "links": []},
                             self.state, self.notifications)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.tmp.cleanup()

    def request(self, path, method="GET", token="x" * 32):
        req = Request(self.base + path, method=method, headers={"Authorization": "Bearer " + token})
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as exc:
            with exc:
                return exc.code, json.load(exc)

    def test_status_requires_token(self):
        code, body = self.request("/v1/status", token="wrong")
        self.assertEqual(code, 401)
        self.assertEqual(body["error"], "Yetkisiz")
        code, body = self.request("/v1/status")
        self.assertEqual((code, body["cpu"]), (200, 42))

    def test_notifications_from_log_not_island_json(self):
        code, body = self.request("/v1/notifications")
        self.assertEqual(code, 200)
        self.assertEqual(body["items"], [
            {"zaman": "2026-10-17 21:01:00", "seviye": "UYARI", "metin": "İkinci|ayrıntı"},
            {"zaman": "2026-10-17 21:00:00", "seviye": "BILGI", "metin": "İlk"},
        ])

    def test_notifications_only_last_80_lines(self):
        older = "2026-10-17 20:00:00|BILGI|Eski\n" * 5
        recent = "2026-10-17 21:00:00|BILGI|Yeni\n" * 80
        self.notifications.write_text(older + recent, encoding="utf-8")
        code, body = self.request("/v1/notifications")
        self.assertEqual(code, 200)
        self.assertEqual(len(body["items"]), 80)
        self.assertTrue(all(item["metin"] == "Yeni" for item in body["items"]))

    def test_no_arbitrary_container_action(self):
        with patch("api.command") as execute:
            code, _ = self.request("/v1/containers/other/stop", method="POST")
            self.assertEqual(code, 403)
            code, _ = self.request("/v1/containers/safe/destroy", method="POST")
            self.assertEqual(code, 403)
            execute.assert_not_called()

    def test_allowed_action_uses_argument_list(self):
        with patch("api.command", return_value="") as execute:
            code, body = self.request("/v1/vms/W11/shutdown", method="POST")
            self.assertEqual(code, 202)
            self.assertTrue(body["ok"])
            execute.assert_called_once_with(["virsh", "-c", "qemu:///system", "shutdown", "W11"], timeout=25)

    def test_post_requires_token_and_forbids_force_shutdown(self):
        with patch("api.command") as execute:
            code, _ = self.request("/v1/vms/W11/shutdown", method="POST", token="wrong")
            self.assertEqual(code, 401)
            code, _ = self.request("/v1/vms/W11/destroy", method="POST")
            self.assertEqual(code, 403)
            execute.assert_not_called()

    def test_desktop_action_only_starts_fixed_unit(self):
        with patch("api.start_desktop") as start:
            code, _ = self.request("/v1/desktops/webtop/start", method="POST")
            self.assertEqual(code, 202)
            start.assert_called_once_with("webtop")
        with patch("api.start_desktop") as start:
            code, _ = self.request("/v1/desktops/webtop/stop", method="POST")
            self.assertEqual(code, 403)
            start.assert_not_called()

    def test_desktop_switch_calls_fixed_internal_endpoint(self):
        response = MagicMock()
        response.status = 202
        response.read.return_value = b'{"baslatiliyor":"kasm"}'
        response.__enter__.return_value = response
        with patch("api.urlopen", return_value=response) as open_url:
            start_desktop("kasm")
            request = open_url.call_args.args[0]
            self.assertEqual(request.full_url, "http://172.17.0.1:8104/kasm")
            self.assertEqual(request.get_method(), "POST")
        with patch("api.urlopen") as open_url:
            with self.assertRaises(ValueError):
                start_desktop("other")
            open_url.assert_not_called()

    def test_token_file_permissions(self):
        path = Path(self.tmp.name) / "token"
        path.write_text("a" * 40, encoding="utf-8")
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)
        self.assertEqual(read_token(path), "a" * 40)
        path.chmod(0o644)
        with self.assertRaises(PermissionError):
            read_token(path)

    def test_config_rejects_non_https_link_and_bad_name(self):
        path = Path(self.tmp.name) / "config.json"
        path.write_text(json.dumps({"containers": ["../../bad"], "vms": []}), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_config(path)
        path.write_text(json.dumps({"containers": [], "vms": [], "links": [{"title": "x", "url": "http://example.invalid"}]}), encoding="utf-8")
        with self.assertRaises(ValueError):
            load_config(path)


if __name__ == "__main__":
    unittest.main()
