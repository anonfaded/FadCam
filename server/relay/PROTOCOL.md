# FadCam Relay Session Boundary

This directory is the server-side boundary for relay protocol ownership. It must not duplicate the existing Server Room media pipeline or gateway.

## Session lifecycle

```text
CONNECT → AUTHENTICATE → REGISTER_SESSION
                         ↓
                    HEARTBEAT
                         ↓
                  POLL / CONTROL
                         ↓
                     MEDIA
                         ↓
                   ACKNOWLEDGE
                         ↓
                      RENEW
                         ↓
                      CLOSE
```

## Request contract

Every relay request must carry:

- `deviceId`: server-recognized device identity;
- `sessionId`: one logical relay session identifier;
- `timestampMillis`: request creation time;
- `timeoutMillis`: request validity window;
- `sequence`: strictly increasing per logical session;
- `authentication`: server-verifiable authentication material;
- `operation`: protocol operation.

The server must reject requests that are:

- unauthenticated;
- from an unknown device;
- from a revoked device;
- for an expired session;
- outside the accepted timestamp window;
- duplicated or out of sequence;
- associated with a different device/session than the authenticated principal.

## Reconnect invariant

A network reconnect must not create a second logical media session accidentally.

The server should resume the existing `sessionId` when its lease is still valid. If the session has expired, the old session must be closed/invalidated before a new session is accepted.

## Media invariant

Relay is a transport path only. The existing Server Room remains the authoritative media owner. Relay code must not create a parallel camera, recording, gateway, or media pipeline.

## Failure handling

The client state machine is deterministic:

```text
CONNECTED
    │ heartbeat timeout
    ▼
DEGRADED
    │ bounded reconnect
    ▼
RECONNECTING
    ├── success → CONNECTED
    └── retry budget exhausted → OFFLINE
```

There are no unbounded retry loops. Backoff and retry scheduling belong to the transport adapter; the session controller owns the state transition and retry budget.

## Required behavioral cases

The relay test suite must cover:

1. duplicate request;
2. late/stale request;
3. expired session;
4. invalid device;
5. revoked device;
6. replayed request;
7. server restart;
8. network loss;
9. network restoration;
10. DIRECT → RELAY → DIRECT without duplicating the logical media session.
