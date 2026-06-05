# Cloud Streaming Usage And Limits

## Canonical Rules

- Pro Plus: up to 1 linked FadCam device.
- Lab: up to 3 linked FadCam devices.
- Monthly usage counts relay egress: bytes served from `/stream/...` to viewers.
- Phone-to-relay uploads through `/upload/...` do not consume monthly usage.
- A device may keep uploading while nobody watches. This uses relay ingress/storage resources, but monthly viewer usage remains unchanged.
- Relay services must never collect, process, log, or persist client IP addresses. Rate limiting uses user/device route keys.
- Missing or stale telemetry is an error, never an inferred zero. Zero is reported only from a current measured window.

## Metric Meanings

### ID Dashboard: Monthly Network Usage

This is the canonical billing/limit metric. Nginx records successful `200` and `206` stream responses, Vector sends events to the usage worker, Redis buffers them, and Supabase rolls them into the current month's `stream_egress` total.

The ID dashboard fetches current usage immediately on page load and polls every 30 seconds while `/lab` is open.

### FadCam Stream Dashboard: Relay Total Served

In cloud mode, this shows aggregate bytes the relay served to viewers since the relay counter began. Redis increments this counter from the same structured egress events used by the usage pipeline. It must not use bytes uploaded by the phone. In local mode, `Total Served` shows bytes served directly by the phone's local stream server.

Do not compare this cumulative value directly with the ID dashboard's current-month total. The ID dashboard is the canonical usage-limit metric.

## What Counts

Counted:

- HLS playlists, initialization data, and media segments successfully served from `/stream/...`.
- Every viewer's delivered bytes. Two viewers generally consume about twice one viewer's usage.

Not counted:

- Phone uploads to `/upload/...`.
- Stream data generated while nobody requests playback.
- Failed stream responses outside successful `200`/`206` delivery.

## Viewer Concurrency

Linked-device limits are enforced by `get_user_limits()` during device registration.

Viewer/client concurrency is enforced by anonymous Redis leases on the relay. Pro Plus permits 1 concurrent viewer and Lab permits 3. Browser tabs heartbeat every 20 seconds; leases expire after 60 seconds if a tab or device disappears. Admission is atomic per user.

## Operations

Primary path:

`nginx /stream response -> fadcam-usage-events.log -> Vector -> usage-worker -> Redis -> Supabase usage_meter_events/usage_rollup_monthly -> ID dashboard`

Viewer count comes from anonymous relay-local Redis leases. Stored lease data contains only random session ID, user/device scope, and expiry.

The `/stream` location writes only structured usage-meter events. Raw access logs are disabled. Usage events do not contain client IP addresses or User-Agent values.

Local-network streaming may use private LAN IP addresses for local client metrics. Cloud relay application/configuration must never read, process, log, or persist viewer IP addresses.

Viewer heartbeats and playback authorization never call Supabase. Supabase Edge Functions are used only for the existing handoff/token issuance flow.

Redis atomically deduplicates each relay response event before queueing it and incrementing Relay Total Served. This prevents Vector retries from inflating the relay counter while Supabase rejects the duplicate event.
