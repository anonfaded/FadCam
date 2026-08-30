# Public Server Room gateway

This is an opt-in public edge for the existing FadCam Remote / Server Room live HTTP stream. It does not modify the Android Server Room service and does not require cloud object storage.

The gateway automatically discovers a Server Room on private networks attached to the gateway host. It probes only the configured `SERVER_ROOM_PORT_START`–`SERVER_ROOM_PORT_END` range (8080–8089 by default) and verifies the existing `/auth/check` endpoint before accepting a device as Server Room. No phone IP or port is stored in `.env`.

```dotenv
PUBLIC_HOSTNAME=stream.example.com
# Optional when the Server Room is on a routed private subnet not visible from
# the gateway's interfaces:
# DISCOVERY_SUBNETS=192.168.50.0/24
```

Start the edge:

```bash
docker compose --env-file server/.env --profile public -f server/docker-compose.yml up -d server-room-discovery server-room-gateway
```

The gateway forwards only live media resources (`.m3u8`, `.m4s`, `.mp4`, `.ts`, `.aac`, `.webm`). It does not forward arbitrary Server Room control/API paths. The requested media path is preserved so relative HLS segment references continue to work.

`PUBLIC_HOSTNAME` must resolve to the gateway's public IP and TCP 80/443 must reach it for Caddy's automatic HTTPS certificate management. For a direct public-IP deployment, `PUBLIC_HOSTNAME=:80` provides HTTP only; use a real hostname for automatic HTTPS.

A private phone address is not Internet-routable by itself. If the phone is behind CGNAT and the gateway is not on a network that can reach it, automatic LAN discovery cannot cross that boundary; an outbound tunnel/relay is required. The phone's control service must never be exposed directly.

The discovery service reports its current target through its internal `/healthz` endpoint. It rescans periodically so DHCP/port changes are picked up without manual configuration.

A real external-network playback test remains required after deployment: start a Server Room stream, request the public `.m3u8` URL from a network outside the LAN, and verify both the playlist and referenced media segments play successfully.
