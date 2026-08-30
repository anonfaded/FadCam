# Public Server Room gateway

This is an opt-in HTTPS edge for the existing FadCam Remote / Server Room live HTTP stream. It does not modify the Android Server Room service and does not require cloud object storage.

Configure `server/.env` on a gateway host that can reach the Server Room phone:

```dotenv
PUBLIC_HOSTNAME=stream.example.com
SERVER_ROOM_URL=http://192.168.1.100:8080
```

Start the edge:

```bash
docker compose --env-file server/.env --profile public -f server/docker-compose.yml up -d server-room-gateway
```

The gateway forwards only media resources (`.m3u8`, `.m4s`, `.mp4`, `.ts`, `.aac`, `.webm`). It does not forward arbitrary Server Room control/API paths. The requested media path is preserved so relative HLS segment references continue to work.

`PUBLIC_HOSTNAME` must resolve to the gateway's public IP and TCP 80/443 must reach it for Caddy's automatic HTTPS certificate management.

A private phone address is not Internet-routable by itself. If the phone is behind CGNAT, place the gateway on a public host and establish an outbound tunnel/relay from the LAN. Do not expose the phone's control service directly.

A real external-network playback test remains required after deployment: start a Server Room stream, request the public `.m3u8` URL from a network outside the LAN, and verify both the playlist and referenced media segments play successfully.
