# FadPlay TV

TV-facing viewer application boundary for remote-friendly live channels, VOD, EPG and account access.

The client consumes Gateway/Core APIs through versioned contracts and never accesses PostgreSQL, MinIO, or MediaMTX directly.

## Planned vertical slices

1. Live channel playback.
2. EPG navigation.
3. VOD catalogue.
4. Remote/controller navigation.
5. Entitlement-aware playback.
