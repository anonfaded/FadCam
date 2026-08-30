# Server Room outbound relay protocol

The relay is the CGNAT fallback around the existing Server Room HTTP service. The Server Room implementation itself is not modified.

## Transport

All `/v1/tunnel/*` traffic must go through the public Caddy endpoint over HTTPS. Never send device credentials over plain HTTP. The relay uses bearer authentication for the device's provisioned secret and issues a short-lived opaque session token after registration.

## Register

`POST /v1/tunnel/register`

Headers:

```text
Authorization: Bearer <device-secret>
Content-Type: application/json
```

Body:

```json
{"device_id":"<stable-device-id>"}
```

Response:

```json
{
  "session":"<opaque-session-token>",
  "viewer_url":"/stream/<opaque-viewer-key>",
  "expires_in":600
}
```

The device must keep the session token private. A new registration invalidates the previous session for that device.

## Poll

`GET /v1/tunnel/poll`

Header:

```text
Authorization: Bearer <session-token>
```

The request long-polls until a public viewer needs a resource or the poll times out.

A request looks like:

```json
{
  "id":"<request-id>",
  "method":"GET",
  "path":"/live/index.m3u8",
  "query":"x=y",
  "headers":{"Accept":"*/*"}
}
```

The Android side must execute this request against the existing local Server Room HTTP server and return the bytes; it must not expose the local server directly to the Internet.

## Respond

`POST /v1/tunnel/respond`

Headers:

```text
Authorization: Bearer <session-token>
Content-Type: application/json
```

Body:

```json
{
  "id":"<request-id>",
  "status":200,
  "headers":{"Content-Type":"application/vnd.apple.mpegurl"},
  "body":"<base64-response-body>"
}
```

Only the response for a pending request is accepted. The relay does not persist it to disk.

## Heartbeat

`POST /v1/tunnel/heartbeat` with the session bearer token keeps the session alive.

## Public playback

The viewer receives:

```text
https://<public-host>/stream/<opaque-viewer-key>/<media-path>
```

The relay maps that opaque key to exactly one active device session. Unknown or expired keys return `404`.

## Direct/fallback behavior

The public gateway uses the same `/stream/<viewer-key>/...` URL in both modes:

1. local discovery succeeds → strip `/stream/<viewer-key>` and proxy to the existing Server Room HTTP server;
2. local discovery fails → send the unchanged request to the outbound relay;
3. discovery recovers → subsequent requests automatically return to the direct path.

No Server Room control endpoint is proxied by this gateway.
