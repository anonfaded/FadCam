# FadPlay Web

Viewer-facing web application boundary for live channels, VOD, EPG, accounts and playback.

The application consumes versioned contracts from `contracts/` and must never connect directly to PostgreSQL, MinIO, or MediaMTX.

## Planned vertical slices

1. Channel directory and live playback.
2. Programme guide.
3. VOD catalogue and playback.
4. Accounts and watch history.
5. Entitlements and advertising.
