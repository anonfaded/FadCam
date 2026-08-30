import concurrent.futures
import ipaddress
import json
import os
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

PORT = int(os.getenv("DISCOVERY_PORT", "18080"))
SCAN_PORTS = range(int(os.getenv("SERVER_ROOM_PORT_START", "8080")), int(os.getenv("SERVER_ROOM_PORT_END", "8089")) + 1)
RESCAN = int(os.getenv("DISCOVERY_INTERVAL_SECONDS", "10"))
TIMEOUT = float(os.getenv("DISCOVERY_TIMEOUT_SECONDS", "0.5"))
RELAY = os.getenv("RELAY_FALLBACK_URL", "http://127.0.0.1:18443").rstrip("/")
TARGET = None
LOCK = threading.Lock()

MEDIA_RE = re.compile(r"^/stream/[A-Za-z0-9_-]+/.+\.(?:m3u8|m4s|mp4|ts|aac|webm)(?:\?.*)?$", re.IGNORECASE)
STREAM_PREFIX_RE = re.compile(r"^/stream/[A-Za-z0-9_-]+(?P<media>/.*)$")


def _local_interface_networks():
    networks = set()
    try:
        output = subprocess.check_output(["ip", "-4", "-o", "addr", "show", "scope", "global"], text=True, stderr=subprocess.DEVNULL, timeout=2)
        for line in output.splitlines():
            fields = line.split()
            for field in fields:
                if "/" not in field:
                    continue
                try:
                    interface_network = ipaddress.ip_interface(field).network
                except ValueError:
                    continue
                if interface_network.is_private:
                    if interface_network.prefixlen < 24:
                        address = ipaddress.ip_interface(field).ip
                        interface_network = ipaddress.ip_network(f"{address}/24", strict=False)
                    networks.add(interface_network)
                break
    except Exception:
        pass
    return networks


def subnets():
    configured = os.getenv("DISCOVERY_SUBNETS", "").strip()
    if configured:
        return [ipaddress.ip_network(value.strip(), strict=False) for value in configured.split(",") if value.strip()]
    networks = _local_interface_networks()
    if networks:
        return sorted(networks, key=lambda network: (network.version, int(network.network_address)))
    return [ipaddress.ip_network("192.168.1.0/24"), ipaddress.ip_network("192.168.0.0/24"), ipaddress.ip_network("10.0.0.0/24"), ipaddress.ip_network("172.16.0.0/24")]


def probe(host, port):
    base = f"http://{host}:{port}"
    try:
        request = Request(base + "/auth/check", headers={"User-Agent": "FadCam-ServerRoom-Discovery/1"})
        with urlopen(request, timeout=TIMEOUT) as response:
            if response.status != 200:
                return None
            body = response.read(4096).decode("utf-8", "replace").lower()
            content_type = response.headers.get("Content-Type", "")
            if "json" in content_type.lower() and "auth" in body:
                return base
    except Exception:
        pass
    return None


def discover():
    hosts = set()
    for network in subnets():
        if network.num_addresses > 4096:
            continue
        hosts.update(str(host) for host in network.hosts())
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as executor:
        futures = [executor.submit(probe, host, port) for host in hosts for port in SCAN_PORTS]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            if result:
                return result
    return None


def discovery_loop():
    global TARGET
    while True:
        found = discover()
        with LOCK:
            TARGET = found
        time.sleep(RESCAN)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/healthz":
            with LOCK:
                target = TARGET
            status = 200 if target else 503
            payload = json.dumps({"status": "ok" if target else "discovering", "server_room": target, "route": "direct" if target else "relay"}).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if not MEDIA_RE.match(path):
            self.send_error(404)
            return

        with LOCK:
            target = TARGET

        # The opaque stream key is a public-routing identifier, not a phone
        # endpoint. In direct mode it must be stripped before touching the
        # existing Server Room HTTP service.
        direct_path = self.path
        match = STREAM_PREFIX_RE.match(self.path)
        if target and match:
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            direct_path = match.group("media") + (("?" + query) if query else "")

        upstream_base = target or RELAY
        try:
            request = Request(upstream_base + (direct_path if target else self.path), headers={"User-Agent": "FadCam-Public-Gateway/1"})
            with urlopen(request, timeout=20 if not target else 5) as upstream:
                self.send_response(upstream.status)
                for key, value in upstream.headers.items():
                    if key.lower() in {"content-length", "content-type", "cache-control", "etag", "last-modified"}:
                        self.send_header(key, value)
                self.send_header("X-FadCam-Route", "direct" if target else "relay")
                self.end_headers()
                while True:
                    chunk = upstream.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except Exception:
            self.send_error(502, "Server Room unavailable")


if __name__ == "__main__":
    threading.Thread(target=discovery_loop, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
