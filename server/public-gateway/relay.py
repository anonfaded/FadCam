"""Outbound Server Room relay.

The phone opens an authenticated outbound long-poll connection; the relay
never dials into the phone. Live media is kept only in memory while a viewer
request is being served.
"""
import base64
import hmac
import json
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

LISTEN_HOST = os.getenv("RELAY_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.getenv("RELAY_PORT", "18443"))
SESSION_TTL = int(os.getenv("RELAY_SESSION_TTL_SECONDS", "600"))
REQUEST_TTL = int(os.getenv("RELAY_REQUEST_TTL_SECONDS", "20"))
MAX_BODY = int(os.getenv("RELAY_MAX_RESPONSE_BYTES", str(8 * 1024 * 1024)))
PUBLIC_BASE = os.getenv("RELAY_PUBLIC_BASE", "").rstrip("/")
TOKENS = json.loads(os.getenv("RELAY_DEVICE_TOKENS", "{}"))
MEDIA_SUFFIXES = (".m3u8", ".m4s", ".mp4", ".ts", ".aac", ".webm")

class Session:
    def __init__(self, device_id):
        self.device_id = device_id
        self.session_id = secrets.token_urlsafe(32)
        self.viewer_key = secrets.token_urlsafe(32)
        self.created = time.time()
        self.last_seen = self.created
        self.pending = {}
        self.condition = threading.Condition()

    def alive(self):
        return time.time() - self.last_seen < SESSION_TTL

sessions = {}
viewer_index = {}
lock = threading.RLock()


def token_ok(device_id, supplied):
    expected = TOKENS.get(device_id)
    return bool(expected and supplied and hmac.compare_digest(str(expected), str(supplied)))


def auth_token(handler):
    value = handler.headers.get("Authorization", "")
    return value[7:].strip() if value.startswith("Bearer ") else None


def json_response(handler, status, payload):
    data = json.dumps(payload, separators=(",", ":")).encode()
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


def empty_response(handler, status):
    handler.send_response(status)
    handler.send_header("Content-Length", "0")
    handler.end_headers()


def read_json(handler):
    try:
        length = int(handler.headers.get("Content-Length", "0"))
    except ValueError:
        return None
    if length <= 0 or length > MAX_BODY:
        return None
    try:
        return json.loads(handler.rfile.read(length))
    except (ValueError, json.JSONDecodeError):
        return None


def media_path(path):
    clean = urlparse(path).path
    return clean.startswith("/") and clean != "/" and clean.lower().endswith(MEDIA_SUFFIXES)


def cleanup():
    now = time.time()
    with lock:
        for sid, session in list(sessions.items()):
            if now - session.last_seen >= SESSION_TTL:
                sessions.pop(sid, None)
                viewer_index.pop(session.viewer_key, None)


def public_url(session):
    return (PUBLIC_BASE + "/stream/" + session.viewer_key) if PUBLIC_BASE else ("/stream/" + session.viewer_key)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass

    def do_GET(self):
        cleanup()
        parsed = urlparse(self.path)
        if parsed.path == "/healthz":
            with lock:
                active = sum(1 for s in sessions.values() if s.alive())
            json_response(self, 200, {"status": "ok", "active_tunnels": active})
            return
        if parsed.path.startswith("/v1/tunnel/poll"):
            self.poll()
            return
        if parsed.path.startswith("/stream/"):
            self.viewer_request(parsed)
            return
        self.send_error(404)

    def do_POST(self):
        cleanup()
        parsed = urlparse(self.path)
        if parsed.path == "/v1/tunnel/register":
            self.register()
            return
        if parsed.path == "/v1/tunnel/respond":
            self.respond()
            return
        if parsed.path == "/v1/tunnel/heartbeat":
            self.heartbeat()
            return
        self.send_error(404)

    def register(self):
        payload = read_json(self)
        if not payload or not isinstance(payload.get("device_id"), str):
            self.send_error(400)
            return
        device_id = payload["device_id"]
        if len(device_id) > 128 or not token_ok(device_id, auth_token(self)):
            self.send_error(401)
            return
        session = Session(device_id)
        with lock:
            for sid, old in list(sessions.items()):
                if old.device_id == device_id:
                    sessions.pop(sid, None)
                    viewer_index.pop(old.viewer_key, None)
            sessions[session.session_id] = session
            viewer_index[session.viewer_key] = session.session_id
        json_response(self, 201, {"session": session.session_id, "viewer_url": public_url(session), "expires_in": SESSION_TTL})

    def _session(self):
        token = auth_token(self)
        with lock:
            session = sessions.get(token)
        if not session or not session.alive():
            return None
        session.last_seen = time.time()
        return session

    def poll(self):
        session = self._session()
        if not session:
            self.send_error(401)
            return
        deadline = time.time() + REQUEST_TTL
        with session.condition:
            while not session.pending and session.alive() and time.time() < deadline:
                session.condition.wait(timeout=min(2, deadline - time.time()))
            if not session.pending:
                empty_response(self, 204)
                return
            request_id, request = next(iter(session.pending.items()))
        json_response(self, 200, {"id": request_id, **request})

    def heartbeat(self):
        session = self._session()
        if not session:
            self.send_error(401)
            return
        remaining = max(0, int(SESSION_TTL - (time.time() - session.created)))
        json_response(self, 200, {"status": "ok", "expires_in": remaining})

    def respond(self):
        session = self._session()
        if not session:
            self.send_error(401)
            return
        payload = read_json(self)
        if not payload or not isinstance(payload.get("id"), str):
            self.send_error(400)
            return
        try:
            body = base64.b64decode(payload.get("body", ""), validate=True)
        except Exception:
            self.send_error(400)
            return
        if len(body) > MAX_BODY:
            self.send_error(413)
            return
        with session.condition:
            request = session.pending.get(payload["id"])
            if request is None:
                self.send_error(404)
                return
            request["response"] = {"status": int(payload.get("status", 502)), "headers": payload.get("headers", {}), "body": body}
            session.condition.notify_all()
        json_response(self, 202, {"status": "accepted"})

    def viewer_request(self, parsed):
        parts = parsed.path.split("/", 3)
        if len(parts) != 4 or not media_path("/" + parts[3]):
            self.send_error(404)
            return
        viewer_key = parts[2]
        with lock:
            sid = viewer_index.get(viewer_key)
            session = sessions.get(sid) if sid else None
        if not session or not session.alive():
            self.send_error(404)
            return
        request_id = secrets.token_urlsafe(18)
        request = {"method": "GET", "path": "/" + parts[3], "query": parsed.query, "headers": {"Accept": self.headers.get("Accept", "*/*")}}
        with session.condition:
            session.pending[request_id] = request
            session.condition.notify_all()
            deadline = time.time() + REQUEST_TTL
            while "response" not in request and session.alive() and time.time() < deadline:
                session.condition.wait(timeout=min(1, deadline - time.time()))
            response = request.get("response")
            session.pending.pop(request_id, None)
        if not response:
            self.send_error(504, "Server Room tunnel timeout")
            return
        self.send_response(response["status"])
        for key, value in response["headers"].items():
            if key.lower() in {"content-type", "cache-control", "etag", "last-modified"} and isinstance(value, str):
                self.send_header(key, value)
        body = response["body"]
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler).serve_forever()
