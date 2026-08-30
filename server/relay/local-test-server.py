#!/usr/bin/env python3
"""Minimal local relay security-test endpoint.

This is CI/development infrastructure only. It intentionally has no production
credentials or external network dependency. Production relay implementation
must remain a separate service.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os

HOST = "0.0.0.0"
PORT = int(os.environ.get("RELAY_PORT", "8443"))

class Handler(BaseHTTPRequestHandler):
    server_version = "FadCamLocalRelayTest/1.0"

    def _deny(self, code=401):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"error":"unauthorized"}')

    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")
            return
        if self.path == "/internal/get-stream-token":
            self._deny()
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == "/internal/get-stream-token":
            self._deny()
            return
        self.send_response(404)
        self.end_headers()

    def do_PUT(self):
        if self.path.startswith("/upload/"):
            self._deny()
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, fmt, *args):
        pass

if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
