# Fad Platform Contracts

This directory contains stable contracts shared by FadCam, Fad Core, the media gateway, playout, and audience clients.

Contracts must describe metadata and control messages, not embed media bytes.

## Initial entities

- User
- Organization
- Channel
- Production
- MediaAsset
- LiveStream
- Programme
- ScheduleEntry
- PlaybackSession

All externally consumed contracts will be versioned under `/api/v1` or an equivalent event version.
