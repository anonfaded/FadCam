# AGENTS.md — Documentation

Documentation-scope instructions. The root [`AGENTS.md`](../AGENTS.md) covers the project; this
file covers `docs/` only.

## Layout

- `home.md` — index; every page is linked here.
- `architecture/` — system design and internals.
- `development/setup/` — build and dependency setup.
- `development/debugging/` — logging and diagnostics.
- `development/fastlane/` — release metadata limits.
- `features/` — feature behaviour and lifecycle.
- `streaming/` — streaming usage, limits and testing.

New pages go in the matching folder and are added to `home.md`. A new top-level folder requires
updating this file and `home.md`.

## Names

- Lowercase, hyphen-separated: `dual-camera.md`.
- No underscores, spaces or capitals; `AGENTS.md` is the only exception.
- Don't repeat the folder name: `architecture/streaming.md`, not `streaming-architecture.md`.

## Links

- Relative Markdown links only: `../architecture/hybrid-mp4.md`.
- Link related pages instead of duplicating content.
- Keep `home.md` entries in sync with renames and deletions.

## Content

- One subject per page; split long pages.
- No handoff or session notes.
