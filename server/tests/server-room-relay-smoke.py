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

    bad = urllib.request.Request(BASE + "/v1/tunnel/register", data=b'{"device_id":"device-a"}', method="POST", headers={"Content-Type": "application/json"})
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

    def fetch_viewer(path):
        result = {}
        def viewer_call():
            try:
                result["response"] = urllib.request.urlopen(BASE + viewer + path, timeout=8).read()
            except Exception as exc:
                result["error"] = exc
        thread = threading.Thread(target=viewer_call)
        thread.start()
        poll_request = urllib.request.Request(
            BASE + "/v1/tunnel/poll",
            method="GET",
            headers={"Authorization": f"Bearer {session}"},
        )
        request = json.loads(urllib.request.urlopen(poll_request, timeout=7).read())
        return thread, result, request

    def respond(request, payload, content_type):
        response_payload = json.dumps({
            "id": request["id"],
            "status": 200,
            "headers": {"Content-Type": content_type},
            "body": base64.b64encode(payload).decode(),
        }).encode()
        respond_request = urllib.request.Request(
            BASE + "/v1/tunnel/respond",
            data=response_payload,
            method="POST",
            headers={"Authorization": f"Bearer {session}", "Content-Type": "application/json"},
        )
        urllib.request.urlopen(respond_request, timeout=2).read()

    # The phone's existing Server Room playlist uses local absolute/relative
    # resource URLs. The public gateway must rewrite those references into the
    # authenticated viewer namespace without changing external URLs.
    playlist = (
        b"#EXTM3U\n"
        b"#EXT-X-TARGETDURATION:2\n"
        b"#EXT-X-MAP:URI=\"/init.mp4\"\n"
        b"#EXTINF:2.0,\n"
        b"/seg-1.m4s\n"
        b"#EXTINF:2.0,\n"
        b"seg-2.m4s?token=abc\n"
        b"#EXT-X-I-FRAMES-ONLY\n"
        b"#EXT-X-MAP:URI=\"https://cdn.example.invalid/init.mp4\"\n"
        b"#EXT-X-ENDLIST\n"
    )
    playlist_thread, playlist_result, playlist_request = fetch_viewer("/live.m3u8")
    assert playlist_request["path"] == "/live.m3u8"
    respond(playlist_request, playlist, "application/vnd.apple.mpegurl")
    playlist_thread.join(timeout=3)
    assert not playlist_thread.is_alive()
    assert "response" in playlist_result or "error" in playlist_result, f"viewer thread produced no result: {playlist_result!r}"
    expected_prefix = viewer
    expected_playlist = (
        b"#EXTM3U\n"
        b"#EXT-X-TARGETDURATION:2\n"
        + f"#EXT-X-MAP:URI=\"{expected_prefix}/init.mp4\"\n".encode()
        + b"#EXTINF:2.0,\n"
        + f"{expected_prefix}/seg-1.m4s\n".encode()
        + b"#EXTINF:2.0,\n"
        + f"{expected_prefix}/seg-2.m4s?token=abc\n".encode()
        + b"#EXT-X-I-FRAMES-ONLY\n"
        + b"#EXT-X-MAP:URI=\"https://cdn.example.invalid/init.mp4\"\n"
        + b"#EXT-X-ENDLIST\n"
    )
    assert playlist_result.get("response") == expected_playlist, (
        f"playlist response mismatch: actual={playlist_result.get('response')!r}, "
        f"error={playlist_result.get('error')!r}, expected={expected_playlist!r}"
    )

    # Follow the rewritten playlist to the init segment and a media fragment.
    init_thread, init_result, init_request = fetch_viewer("/init.mp4")
    assert init_request["path"] == "/init.mp4"
    respond(init_request, b"INIT-SEGMENT", "video/mp4")
    init_thread.join(timeout=3)
    assert not init_thread.is_alive()
    assert init_result.get("response") == b"INIT-SEGMENT", init_result.get("error")

    segment_thread, segment_result, segment_request = fetch_viewer("/seg-1.m4s")
    assert segment_request["path"] == "/seg-1.m4s"
    respond(segment_request, b"MEDIA-SEGMENT-1", "video/iso.segment")
    segment_thread.join(timeout=3)
    assert not segment_thread.is_alive()
    assert segment_result.get("response") == b"MEDIA-SEGMENT-1", segment_result.get("error")

    unknown = urllib.request.Request(BASE + "/stream/not-a-real-key/live.m3u8")
    try:
        urllib.request.urlopen(unknown, timeout=2)
        raise AssertionError("unknown viewer key was accepted")
    except urllib.error.HTTPError as exc:
        assert exc.code == 404

    print("Server Room relay smoke test passed: auth, HLS URL rewriting, init/segment continuity, viewer isolation")
finally:
    proc.terminate()
    proc.wait(timeout=3)
