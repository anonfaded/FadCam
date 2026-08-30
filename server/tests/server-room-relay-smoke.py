#!/usr/bin/env python3
import base64
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

PORT = 18445
BASE = f"http://127.0.0.1:{PORT}"
TOKEN = "test-device-secret-" + os.urandom(12).hex()
ENV = os.environ.copy()
ENV.update({"RELAY_PORT": str(PORT), "RELAY_DEVICE_TOKENS": json.dumps({"device-a": TOKEN}), "RELAY_SESSION_TTL_SECONDS": "30", "RELAY_REQUEST_TTL_SECONDS": "5"})

proc = subprocess.Popen([sys.executable, "server/public-gateway/relay.py"], env=ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
try:
    for _ in range(50):
        try:
            urllib.request.urlopen(BASE + "/healthz", timeout=0.2).read()
            break
        except Exception:
            time.sleep(0.1)
    else:
        raise AssertionError("relay did not start")

    bad = urllib.request.Request(BASE + "/v1/tunnel/register", data=b'{"device_id":"device-a"}', method="POST", headers={"Content-Type":"application/json"})
    try:
        urllib.request.urlopen(bad, timeout=2)
        raise AssertionError("unauthenticated registration was accepted")
    except urllib.error.HTTPError as exc:
        assert exc.code == 401

    body = json.dumps({"device_id": "device-a"}).encode()
    req = urllib.request.Request(BASE + "/v1/tunnel/register", data=body, method="POST", headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    registration = json.loads(urllib.request.urlopen(req, timeout=2).read())
    session = registration["session"]
    viewer = registration["viewer_url"]
    assert viewer.startswith("/stream/")
    viewer_url = BASE + viewer + "/live/index.m3u8"

    result = {}
    def viewer_call():
        try:
            result["response"] = urllib.request.urlopen(viewer_url, timeout=8).read()
        except Exception as exc:
            result["error"] = exc

    viewer_thread = threading.Thread(target=viewer_call)
    viewer_thread.start()

    poll = urllib.request.Request(BASE + "/v1/tunnel/poll", method="GET", headers={"Authorization": f"Bearer {session}"})
    request = json.loads(urllib.request.urlopen(poll, timeout=7).read())
    assert request["path"] == "/live/index.m3u8"

    payload = json.dumps({"id": request["id"], "status": 200, "headers": {"Content-Type": "application/vnd.apple.mpegurl"}, "body": base64.b64encode(b"#EXTM3U\n#EXT-X-ENDLIST\n").decode()}).encode()
    respond = urllib.request.Request(BASE + "/v1/tunnel/respond", data=payload, method="POST", headers={"Authorization": f"Bearer {session}", "Content-Type": "application/json"})
    urllib.request.urlopen(respond, timeout=2).read()
    viewer_thread.join(timeout=3)
    assert result.get("response") == b"#EXTM3U\n#EXT-X-ENDLIST\n"

    unknown = urllib.request.Request(BASE + "/stream/not-a-real-key/live/index.m3u8")
    try:
        urllib.request.urlopen(unknown, timeout=2)
        raise AssertionError("unknown viewer key was accepted")
    except urllib.error.HTTPError as exc:
        assert exc.code == 404

    print("Server Room relay smoke test passed: auth, tunnel request/response, viewer isolation")
finally:
    proc.terminate()
    proc.wait(timeout=3)
