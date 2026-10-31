# Cloud Streaming Usage And Limits

## Canonical Rules

- Free and Patreon-unlinked users may link 1 FadCam device to complete setup.
- Cloud upload, handoff, playback, and viewer access require a linked, recently verified Patreon identity with Pro Plus, Lab, or linked beta access.
- Beta access uses Pro Plus limits but never bypasses Patreon linking.
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

Linked-device limits are enforced atomically by `register_user_device()` using the canonical `get_user_entitlement()` result.

Viewer/client concurrency is enforced by anonymous Redis leases on the relay. Pro Plus permits 1 concurrent viewer and Lab permits 3. Browser tabs heartbeat every 20 seconds; leases expire after 60 seconds if a tab or device disappears. Admission is atomic per user.

## Membership Verification

Patreon webhooks update membership changes immediately. A scheduled verification job checks all linked Patreon accounts every 6 hours to recover from missed webhooks. Streaming fails closed when the latest successful verification is older than 48 hours.

Stream tokens expire after 2 hours and device API authorization is cached for 110 minutes. This keeps the projected token-issuance plus auth-validation path for roughly 500 always-on devices below Supabase's 500K monthly Edge Function allowance. Playback and viewer heartbeats remain relay-local and do not call Supabase per request. Unlinking Patreon or a device blocks new tokens immediately and bounds already-issued access to under 2 hours.

## Operations

Primary path:

`nginx /stream response -> fadcam-usage-events.log -> Vector -> usage-worker -> Redis -> Supabase usage_meter_events/usage_rollup_monthly -> ID dashboard`

Viewer count comes from anonymous relay-local Redis leases. Stored lease data contains only random session ID, user/device scope, and expiry.

The `/stream` location writes only structured usage-meter events. Raw access logs are disabled. Usage events do not contain client IP addresses or User-Agent values.

Local-network streaming may use private LAN IP addresses for local client metrics. Cloud relay application/configuration must never read, process, log, or persist viewer IP addresses.

Viewer heartbeats and playback authorization never call Supabase. Supabase Edge Functions are used only for the existing handoff/token issuance flow.

Redis atomically deduplicates each relay response event before queueing it and incrementing Relay Total Served. This prevents Vector retries from inflating the relay counter while Supabase rejects the duplicate event.

## End-To-End Test Matrix

| Account state | Device linking | Phone upload/token | Open stream | Viewer limit |
|---|---:|---:|---:|---:|
| Patreon not linked, normal or beta | 1 device | Denied: `PATREON_REQUIRED` | Denied | 0 |
| Patreon linked, Free | 1 device | Denied: `SUBSCRIPTION_REQUIRED` | Denied | 0 |
| Patreon linked, verification older than 48h | 1 device | Denied: `MEMBERSHIP_VERIFICATION_REQUIRED` | Denied | 0 |
| Patreon linked, beta | 1 device | Allowed | Allowed | 1 |
| Patreon linked, Pro Plus | 1 device | Allowed | Allowed | 1 |
| Patreon linked, Lab | 3 devices | Allowed | Allowed | 3 |
| Any streaming account at monthly limit | Existing devices remain linked | Denied: `MONTHLY_DATA_LIMIT_REACHED` | Denied | Existing leases expire normally |

Manual verification order:

1. Link one device before connecting Patreon; confirm the device appears and streaming stays locked.
2. Mark the same account beta without linking Patreon; confirm streaming remains locked.
3. Connect a Free Patreon account; confirm linking remains available and streaming remains locked.
4. Verify Pro Plus and Lab token issuance, upload, handoff, playback, device limits, and viewer limits.
5. Unlink Patreon and unlink a device; confirm new tokens fail immediately and existing access expires within the two-hour token/auth-cache window.
6. Confirm viewer playback increments monthly usage while phone-only upload does not.
