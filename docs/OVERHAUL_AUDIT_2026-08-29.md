# FadCam Repository-Wide Overhaul Audit

Date: 2026-08-29
Baseline: `a7600889a0b5d17573ecae748ef8fa3022fa1190` (`fused-tv-foundation`)

## Baseline evidence

- Media Pipeline E2E run #103 passed on the baseline commit.
- Monorepo Smoke run #63 passed on the baseline commit.
- The media E2E exercised authenticated publishing, MediaMTX discovery, recording, FFmpeg processing, and MinIO object verification.
- The repository default branch is `master`; the current integration branch is `fused-tv-foundation`.

## Findings

### P0 — Security and trust boundaries

1. `services/gateway/src/server.mjs` falls back to `MEDIAMTX_API_USER=any` and an empty password when configuration is missing. Missing credentials must fail closed, not silently become anonymous credentials.
2. `services/gateway/src/auth.mjs` is not wired into the gateway routes. Device authentication therefore exists as a helper but is not an enforced perimeter.
3. `services/core/src/auth.mjs` stores device identities in an in-memory `Map`. Restarting the process loses registrations and a multi-instance deployment cannot share authentication state.
4. Development PostgreSQL and MinIO credentials are committed in `deployment/docker-compose.yml`. They are acceptable only for explicitly isolated development; production configuration must not reuse them.
5. MediaMTX E2E credentials are static test credentials. They must remain isolated to CI/E2E and never become production credentials.

### P0 — Reliability

6. Core parses arbitrary JSON request bodies without a size limit and converts malformed JSON into a generic 503. This should be bounded and classified as a 400 client error.
7. Core has no explicit graceful shutdown handling for the HTTP server or PostgreSQL pool.
8. Gateway upstream fetches have no explicit timeout or cancellation policy. A stuck dependency can consume request resources indefinitely.
9. Gateway dependency health reports the MediaMTX URL rather than actually checking MediaMTX availability.
10. The current smoke test proves process readiness, not business correctness.

### P1 — Database

11. The Compose migration step executes only `001_initial_schema.sql`. There is no migration version tracking/forward migration framework visible in the deployment path.
12. Core has SQL embedded directly in the HTTP server. Persistence, validation, and transport concerns should be separated before the API grows.
13. There is no demonstrated transaction/retry policy for multi-step media state transitions.

### P1 — Docker and supply chain

14. Node service Dockerfiles use `npm install --omit=dev` without a lockfile/`npm ci` contract, reducing reproducibility.
15. Runtime containers are not explicitly non-root.
16. Images use mutable major tags in places such as `node:20-bookworm-slim`, `node:20-alpine`, and `bluenviron/mediamtx:1`; production should pin immutable versions/digests.
17. Compose exposes internal infrastructure ports directly. Production needs a private network boundary and a deliberately small public surface.

### P1 — CI/CD

18. Media E2E and monorepo smoke are good integration gates, but there is no single required quality gate combining Android, service unit tests, contract tests, security checks, and integration tests.
19. The Media3 patched dependency is cloned from a mutable external branch in build workflows rather than pinned to a known commit.
20. `build-pro.yml` verifies legacy APK output names while `app/build.gradle.kts` contains custom dynamic output naming logic; this needs to be reconciled and tested.
21. Release workflows should produce provenance/attestation and retain machine-readable test reports.

### P1 — Android

22. `app/build.gradle.kts` disables release lint failure (`checkReleaseBuilds = false`) and suppresses `MissingTranslation`. Release quality gates should not silently disable static analysis.
23. The Android build depends on a local/composite patched Media3 checkout and a local AAR tree. CI must have an explicit, reproducible source-of-truth for both.
24. The app has accumulated significant recording, self-healing, Media3, FFmpeg, CameraX, and storage complexity. The next Android pass must focus on lifecycle/process death, recording finalization, encoder compatibility, storage exhaustion, permissions, and network recovery rather than UI-only changes.

## Dependency-ordered overhaul

### Stage 1 — Fail-closed foundation

- Remove unsafe gateway credential fallbacks.
- Add bounded JSON parsing and correct 400 handling in Core.
- Add upstream request timeouts in Gateway.
- Add graceful shutdown to Core and Gateway.
- Add real dependency readiness checks.

### Stage 2 — Deterministic builds

- Introduce lockfiles for every Node service and switch runtime builds to `npm ci`.
- Pin Media3 patched commit in CI and document/update it intentionally.
- Pin production container image digests.
- Make runtime containers non-root where supported.

### Stage 3 — Database and authentication

- Introduce migration versioning.
- Extract Core repository/service layers.
- Move device credentials/state into PostgreSQL.
- Add token rotation/revocation and constant-time token verification.
- Wire authentication into protected Gateway routes with an explicit E2E bypass only in isolated CI.

### Stage 4 — Contract and failure testing

- Unit-test Core/Gateway validation and error mapping.
- Add API contract tests.
- Expand media E2E with restart, reconnect, timeout, missing object, and partial recording cases.
- Add Android instrumentation tests for recording lifecycle/process death.

### Stage 5 — Production topology

- Separate development, CI, staging, and production Compose/configuration.
- Remove direct public exposure of PostgreSQL, MinIO console, MediaMTX API, and internal service ports.
- Introduce TLS/authenticated ingress and private service networking.
- Add backups and restore verification.

### Stage 6 — Observability and release

- Structured logs with request/stream/device correlation IDs.
- Metrics for streams, recordings, FFmpeg jobs, uploads, latency, and failures.
- Readiness/liveness semantics.
- Release provenance, SBOM/dependency scanning, and rollback procedure.

## Definition of done

The overhaul is complete only when:

- A clean checkout can reproduce CI without developer-local files.
- No production secret is committed or used as a fallback.
- Device authentication survives service restart and is testable across instances.
- Database migrations are versioned and forward-only with tested upgrades.
- Gateway and Core have bounded requests, timeouts, graceful shutdown, and typed error semantics.
- Media E2E covers both happy path and controlled failure/recovery.
- Android recording survives lifecycle/process interruptions according to the documented contract.
- Production exposes only intended ingress endpoints.
- CI blocks merges/releases when any required quality gate fails.
