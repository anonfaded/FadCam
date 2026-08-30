#!/usr/bin/env python3
"""Isolated local HTTPS relay endpoint for CI authorization tests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os, ssl

HOST = "0.0.0.0"
PORT = int(os.environ.get("RELAY_PORT", "8443"))
CERT = os.environ.get("RELAY_CERT_FILE")
KEY = os.environ.get("RELAY_KEY_FILE")

class Handler(BaseHTTPRequestHandler):
    server_version = "FadCamLocalRelayTest/1.0"
    def _deny(self, code=401):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"error":"unauthorized"}')
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200); self.end_headers(); self.wfile.write(b"ok"); return
        if self.path == "/internal/get-stream-token": self._deny(); return
        self.send_response(404); self.end_headers()
    def do_POST(self):
        if self.path == "/internal/get-stream-token": self._deny(); return
        self.send_response(404); self.end_headers()
    def do_PUT(self):
        if self.path.startswith("/upload/"): self._deny(); return
        self.send_response(404); self.end_headers()
    def log_message(self, fmt, *args): pass

if __name__ == "__main__":
    if not CERT or not KEY: raise SystemExit("RELAY_CERT_FILE and RELAY_KEY_FILE are required")
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT, KEY)
    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    httpd.serve_forever()
