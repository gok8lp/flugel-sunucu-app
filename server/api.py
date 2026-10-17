#!/usr/bin/env python3
"""FLUGEL Sunucu uygulamasi icin yerel, kimlik dogrulamali dar kontrol API'si.

Yalniz stdlib kullanir. Public agda dogrudan dinlememeli: TLS ve erisim kisiti
Claude tarafindan reverse proxy/Tailscale ile kurulmadan telefona acilmaz.
"""

from __future__ import annotations

import hmac
import json
import os
import re
import stat
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


NAME = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
CONTAINER_ACTIONS = frozenset({"start", "stop", "restart"})
VM_ACTIONS = frozenset({"start", "shutdown"})
DESKTOP_UNITS = {"webtop": "flugel-webtop.service", "kasm": "flugel-kasm.service"}
MAX_JSON = 2_000_000


def read_json(path: Path, limit: int = MAX_JSON) -> dict:
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("JSON boyutu siniri asildi")
    value = json.loads(data)
    if not isinstance(value, dict):
        raise ValueError("JSON nesnesi bekleniyor")
    return value


def read_token(path: Path) -> str:
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        raise PermissionError("Token dosyasi yalniz sahibi tarafindan okunabilir olmali (0600)")
    token = path.read_text(encoding="utf-8").strip()
    if len(token) < 32 or len(token) > 256 or not token.isascii():
        raise ValueError("Token 32-256 ASCII karakter olmali")
    return token


def load_config(path: Path) -> dict:
    config = read_json(path, 64_000)
    allowed = {}
    for key in ("containers", "vms"):
        values = config.get(key, [])
        if not isinstance(values, list) or any(not isinstance(v, str) or not NAME.fullmatch(v) for v in values):
            raise ValueError(f"Gecersiz izin listesi: {key}")
        allowed[key] = frozenset(values)
    links = config.get("links", [])
    if not isinstance(links, list) or len(links) > 40:
        raise ValueError("Gecersiz link listesi")
    safe_links = []
    for item in links:
        if not isinstance(item, dict):
            raise ValueError("Link nesnesi bekleniyor")
        title, url = item.get("title"), item.get("url")
        parsed = urlsplit(url) if isinstance(url, str) else None
        if not isinstance(title, str) or len(title) > 80 or not parsed or parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Yalniz adli HTTPS linkleri kabul edilir")
        safe_links.append({"title": title, "url": url})
    allowed["links"] = safe_links
    return allowed


def command(args: list[str], timeout: int = 8) -> str:
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError("Komut basarisiz")
    return result.stdout[:100_000]


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], token: str, config: dict, state_file: Path):
        super().__init__(address, Handler)
        self.token = token
        self.config = config
        self.state_file = state_file
        self.action_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server: Server
    protocol_version = "HTTP/1.1"

    def log_message(self, _fmt: str, *_args: object) -> None:
        # Header, token, sorgu parametresi veya bildirim metni gunluge yazilmaz.
        return

    def respond(self, code: int, value: dict | list) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        supplied = self.headers.get("Authorization", "")
        valid = supplied.startswith("Bearer ") and hmac.compare_digest(supplied[7:], self.server.token)
        if not valid:
            self.respond(401, {"error": "Yetkisiz"})
        return valid

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        path = self.path.split("?", 1)[0]
        if path == "/healthz":
            self.respond(200, {"ok": True})
            return
        if not self.authorized():
            return
        try:
            if path == "/v1/status":
                self.respond(200, read_json(self.server.state_file))
            elif path == "/v1/notifications":
                state = read_json(self.server.state_file)
                self.respond(200, {"items": state.get("bildirimler", [])[:80]})
            elif path == "/v1/containers":
                lines = command(["docker", "ps", "-a", "--format", "{{json .}}"])
                items = []
                for line in lines.splitlines():
                    row = json.loads(line)
                    items.append({"name": row.get("Names", ""), "state": row.get("State", ""), "status": row.get("Status", "")})
                self.respond(200, {"items": items, "actionable": sorted(self.server.config["containers"])})
            elif path == "/v1/vms":
                names = command(["virsh", "-c", "qemu:///system", "list", "--all", "--name"]).splitlines()
                items = []
                for name in names[:30]:
                    if NAME.fullmatch(name):
                        items.append({"name": name, "state": command(["virsh", "-c", "qemu:///system", "domstate", name]).strip()})
                self.respond(200, {"items": items, "actionable": sorted(self.server.config["vms"])})
            elif path == "/v1/desktops":
                items = []
                for name, unit in DESKTOP_UNITS.items():
                    result = subprocess.run(["systemctl", "is-active", unit], text=True, capture_output=True, timeout=5, check=False)
                    items.append({"name": name, "active": result.stdout.strip() == "active"})
                self.respond(200, {"items": items})
            elif path == "/v1/links":
                self.respond(200, {"items": self.server.config["links"]})
            else:
                self.respond(404, {"error": "Bulunamadi"})
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError):
            self.respond(503, {"error": "Servis verisi alinamadi"})

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if not self.authorized():
            return
        if self.headers.get("Content-Length", "0") != "0":
            self.respond(400, {"error": "Istek govdesi kabul edilmiyor"})
            return
        parts = self.path.split("?", 1)[0].strip("/").split("/")
        if len(parts) != 4 or parts[0] != "v1" or not NAME.fullmatch(parts[2]):
            self.respond(404, {"error": "Bilinmeyen islem"})
            return
        area, name, action = parts[1:]
        if area == "containers" and name in self.server.config["containers"] and action in CONTAINER_ACTIONS:
            args = ["docker", action, name]
        elif area == "vms" and name in self.server.config["vms"] and action in VM_ACTIONS:
            args = ["virsh", "-c", "qemu:///system", action, name]
        elif area == "desktops" and name in DESKTOP_UNITS and action == "start":
            args = ["systemctl", "start", DESKTOP_UNITS[name]]
        else:
            self.respond(403, {"error": "Izin listesinde degil"})
            return
        if not self.server.action_lock.acquire(blocking=False):
            self.respond(409, {"error": "Baska bir islem suruyor"})
            return
        try:
            command(args, timeout=25)
            self.respond(202, {"ok": True, "action": action, "name": name})
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            self.respond(503, {"error": "Islem tamamlanamadi"})
        finally:
            self.server.action_lock.release()


def main() -> None:
    token_path = Path(os.environ.get("FLUGEL_APP_TOKEN_FILE", "/etc/flugel-sunucu-app/token"))
    config_path = Path(os.environ.get("FLUGEL_APP_CONFIG", "/etc/flugel-sunucu-app/config.json"))
    state_path = Path(os.environ.get("FLUGEL_APP_STATE_FILE", "/home/flugel/sistem-bakim/durum/island.json"))
    host = os.environ.get("FLUGEL_APP_HOST", "127.0.0.1")
    if host not in {"127.0.0.1", "::1"}:
        raise ValueError("API yalniz loopback adresinde dinleyebilir")
    port = int(os.environ.get("FLUGEL_APP_PORT", "8818"))
    if not 1024 <= port <= 65535:
        raise ValueError("Port aralik disi")
    server = Server((host, port), read_token(token_path), load_config(config_path), state_path)
    server.serve_forever(poll_interval=0.2)


if __name__ == "__main__":
    main()
