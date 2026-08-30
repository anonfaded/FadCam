#!/usr/bin/env python3
"""Forensic source-level contract for FadCam's production Android -> Server Room path.

This is deliberately a verification gate, not a runtime abstraction. It proves that the
same production classes form one continuous source-level path from the Remote tab through
the foreground streaming service, recording/muxer callbacks, the existing HLS buffer and
its HTTP viewer endpoints.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

FILES = {
    "remote_fragment": ROOT / "app/src/main/java/com/fadcam/ui/RemoteFragment.java",
    "stream_service": ROOT / "app/src/main/java/com/fadcam/streaming/RemoteStreamService.java",
    "recording_service": ROOT / "app/src/main/java/com/fadcam/services/RecordingService.java",
    "muxer": ROOT / "app/src/main/java/com/fadcam/media/FragmentedMp4MuxerWrapper.java",
    "manager": ROOT / "app/src/main/java/com/fadcam/streaming/RemoteStreamManager.java",
    "http": ROOT / "app/src/main/java/com/fadcam/streaming/LiveM3U8Server.java",
    "hardened_http": ROOT / "app/src/main/java/com/fadcam/streaming/HardenedLiveM3U8Server.java",
    "controller": ROOT / "app/src/main/java/com/fadcam/relay/TransportController.java",
    "direct": ROOT / "app/src/main/java/com/fadcam/relay/LocalDirectMediaTransport.java",
    "relay": ROOT / "app/src/main/java/com/fadcam/relay/ServerRoomRelayAgent.java",
}


def fail(message):
    print(f"FAIL: {message}", file=sys.stderr)
    sys.exit(1)


def read(name):
    path = FILES[name]
    if not path.is_file():
        fail(f"missing required production file: {path}")
    return path.read_text(encoding="utf-8")


def require(text, needle, label):
    if needle not in text:
        fail(f"{label}: missing `{needle}`")
    print(f"PASS: {label}")


def require_order(text, needles, label):
    pos = -1
    for needle in needles:
        next_pos = text.find(needle, pos + 1)
        if next_pos < 0:
            fail(f"{label}: missing/order break at `{needle}`")
        pos = next_pos
    print(f"PASS: {label}")


def main():
    src = {name: read(name) for name in FILES}

    # 1. User activation -> actual foreground service.
    require(src["remote_fragment"], "private void startStreaming()", "Remote tab has production activation method")
    require_order(
        src["remote_fragment"],
        [
            "new Intent(requireContext(), RemoteStreamService.class)",
            "startForegroundService(intent)",
            "bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)",
        ],
        "Remote tab -> RemoteStreamService activation",
    )

    # 2. The foreground service owns the real production object graph.
    require_order(
        src["stream_service"],
        [
            "directMediaTransport = new LocalDirectMediaTransport()",
            "relayAgent = new ServerRoomRelayAgent",
            "transportController = new TransportController(directMediaTransport, relayAgent)",
            "manager.setMediaTransport(transportController)",
        ],
        "RemoteStreamService -> production transport graph",
    )
    require_order(
        src["stream_service"],
        [
            "if (!startHttpServer())",
            "transportController.connect()",
            "RemoteStreamManager.getInstance().setStreamingEnabled(true)",
        ],
        "Service activation -> existing local HTTP Server -> streaming state",
    )
    require(src["stream_service"], "new HardenedLiveM3U8Server(this, port)", "Server Room uses existing device HTTP server")
    require(src["stream_service"], "DEFAULT_PORT = 8080", "Server Room keeps the 8080 contract")

    # 3. Actual camera recording path gates on the same streaming state.
    require(src["recording_service"], "RemoteStreamManager.getInstance().isStreamingEnabled()", "Production RecordingService observes Server Room state")
    require(src["recording_service"], "[STREAM MODE ENFORCED] Starting recording with streaming server ACTIVE", "RecordingService has explicit streaming activation evidence")

    # 4. The real fMP4 muxer is the bridge from encoded media into the manager.
    require(src["muxer"], "cachedStreamManager.onInitializationSegment(data)", "Muxer -> RemoteStreamManager initialization callback")
    require(src["muxer"], "cachedStreamManager.onFragmentComplete(segment.segmentNr, data, segment.durationMs)", "Muxer -> RemoteStreamManager media callback")
    require_order(
        src["muxer"],
        [
            "boolean serverActive = cachedStreamManager != null && cachedStreamManager.isStreamingEnabled();",
            "if (serverActive && cachedStreamManager != null) {",
            "cachedStreamManager.onInitializationSegment(data)",
            "cachedStreamManager.onFragmentComplete(segment.segmentNr, data, segment.durationMs)",
        ],
        "Encoded fMP4 -> production stream manager",
    )

    # 5. Manager is both the HLS source of truth and transport boundary.
    require(src["manager"], "fragmentBuffer[bufferHead] = fragment", "RemoteStreamManager buffers real media fragments")
    require(src["manager"], "transport.sendFragment(sequenceNumber, fragmentData, durationMs)", "RemoteStreamManager forwards each production fragment through transport boundary")
    require(src["manager"], "transport.sendInitializationSegment(initData)", "RemoteStreamManager forwards the production init segment through transport boundary")

    # 6. DIRECT is the local Server Room path; it must not create a second server/upload copy.
    require(src["direct"], "The muxer/RemoteStreamManager already owns the local fragment buffer", "Local direct transport documents single-buffer ownership")
    require(src["direct"], "public void sendFragment(int sequenceNumber, byte[] payload, long durationMs)", "Local direct transport implements media contract")
    require(src["controller"], "if (current == State.DIRECT) return directTransport.isConnected();", "TransportController makes DIRECT authoritative locally")
    require(src["controller"], "authoritative().sendFragment(sequenceNumber, payload, durationMs)", "TransportController routes media through authoritative path")

    # 7. Existing Server Room HTTP viewer reads the exact manager buffer.
    require(src["http"], '"/live.m3u8".equals(uri)', "Server Room exposes live HLS endpoint")
    require(src["http"], '"/init.mp4".equals(uri)', "Server Room exposes init segment endpoint")
    require(src["http"], 'uri.startsWith("/seg-") && uri.endsWith(".m4s")', "Server Room exposes media segment endpoint")
    require(src["http"], "streamManager.getInitializationSegment()", "HLS server reads manager init segment")
    require(src["http"], "streamManager.getBufferedFragments()", "HLS server reads manager fragment buffer")

    # 8. Hardened HTTP layer leaves media delivery public while protecting state/control.
    require(src["hardened_http"], 'return "/live.m3u8".equals(uri)', "Hardened Server Room preserves public HLS path")
    require(src["hardened_http"], '|| "/init.mp4".equals(uri)', "Hardened Server Room preserves public init path")
    require(src["hardened_http"], '|| (uri.startsWith("/seg-") && uri.endsWith(".m4s"))', "Hardened Server Room preserves public segment path")

    # 9. Relay remains an alternate transport; this gate must not mistake failover for local Server Room proof.
    require(src["relay"], "class ServerRoomRelayAgent", "Relay boundary exists separately from local HTTP Server")

    print("\nSERVER ROOM PRODUCTION WIRING: VERIFIED")
    print("ANDROID -> RemoteStreamService -> existing :8080 Server Room")
    print("CAMERA -> fMP4 muxer -> RemoteStreamManager -> HLS buffer -> /live.m3u8 -> /seg-*.m4s")
    print("DIRECT local delivery remains the existing Server Room; RELAY is an alternate transport, not a second local server.")
    print("\nThis gate is source-level. A physical-device viewer run is still required for final runtime proof of rendered frames.")


if __name__ == "__main__":
    main()
