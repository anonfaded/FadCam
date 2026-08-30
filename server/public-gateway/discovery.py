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

PORT = int(os.getenv('DISCOVERY_PORT', '18080'))
SCAN_PORTS = range(int(os.getenv('SERVER_ROOM_PORT_START', '8080')), int(os.getenv('SERVER_ROOM_PORT_END', '8089')) + 1)
RESCAN = int(os.getenv('DISCOVERY_INTERVAL_SECONDS', '10'))
TIMEOUT = float(os.getenv('DISCOVERY_TIMEOUT_SECONDS', '0.5'))
TARGET = None
LOCK = threading.Lock()
MEDIA_RE = re.compile(r'^/(?:live|stream)\\.m3u8$|^/init\\.mp4$|^/seg-[^/]+\\.m4s$|^/(?:css|js|assets|fadex)/')


def subnets():
    configured = os.getenv('DISCOVERY_SUBNETS', '').strip()
    if configured:
        return [ipaddress.ip_network(x.strip(), strict=False) for x in configured.split(',') if x.strip()]
    nets = []
    try:
        out = subprocess.check_output(['ip', '-4', 'route'], text=True, stderr=subprocess.DEVNULL, timeout=2)
        for line in out.splitlines():
            parts = line.split()
            if not parts or '/' not in parts[0] or parts[0] == 'default':
                continue
            try:
                n = ipaddress.ip_network(parts[0], strict=False)
                if not n.is_loopback and n.prefixlen >= 16:
                    nets.append(n)
            except ValueError:
                pass
    except Exception:
        pass
    if not nets:
        nets = [ipaddress.ip_network('192.168.0.0/16'), ipaddress.ip_network('10.0.0.0/8'), ipaddress.ip_network('172.16.0.0/12')]
    return nets


def probe(host, port):
    base = f'http://{host}:{port}'
    try:
        req = Request(base + '/auth/check', headers={'User-Agent': 'FadCam-ServerRoom-Discovery/1'})
        with urlopen(req, timeout=TIMEOUT) as response:
            body = response.read(4096).decode('utf-8', 'replace')
            content_type = response.headers.get('Content-Type', '')
            if 'json' in content_type.lower() and ('authenticated' in body.lower() or 'auth' in body.lower()):
                return base
    except Exception:
        pass
    return None


def discover():
    hosts = set()
    for net in subnets():
        # Never scan an unexpectedly large network automatically.
        if net.num_addresses > 4096:
            continue
        hosts.update(str(host) for host in net.hosts())
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
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_args):
        pass

    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path == '/healthz':
            with LOCK:
                target = TARGET
            status = 200 if target else 503
            payload = json.dumps({'status': 'ok' if target else 'discovering', 'server_room': target}).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if not MEDIA_RE.match(path):
            self.send_error(404)
            return

        with LOCK:
            target = TARGET
        if not target:
            self.send_error(503, 'Server Room not discovered')
            return

        try:
            request = Request(target + self.path, headers={'User-Agent': 'FadCam-Public-Gateway/1'})
            with urlopen(request, timeout=5) as upstream:
                self.send_response(upstream.status)
                for key, value in upstream.headers.items():
                    if key.lower() in {'content-length', 'content-type', 'cache-control', 'etag', 'last-modified'}:
                        self.send_header(key, value)
                self.send_header('X-FadCam-Server-Room', 'discovered')
                self.end_headers()
                while True:
                    chunk = upstream.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except Exception:
            self.send_error(502, 'Server Room unavailable')


if __name__ == '__main__':
    threading.Thread(target=discovery_loop, daemon=True).start()
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
