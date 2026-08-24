# Lessons

## 2026-08-24 — CI verification
- Never re-add a unit-test gate without checking that the tests still match the current implementation.
- `RecordingToggleActivity.finishWithoutUi()` intentionally calls `finish()` only; an older test expected `moveTaskToBack(true)` and therefore became stale.
- CI verification runs must not cancel an earlier run merely because a newer commit was pushed; each commit under investigation must finish independently so its result is auditable.
- Release signing must be validated as a real Gradle task before the expensive release build; a generated CI key is acceptable for device verification but must never fall back to the Android debug certificate.
- Do not hard-code one Android build-tools patch version when the runner can expose a newer compatible version; select the installed version and verify the required tools exist.
- Rolling GitHub releases can fail if an old tag survives release deletion; explicitly delete the tag and poll for remote disappearance before creating the replacement release.
- Upgrade GitHub Actions to Node 24-compatible releases rather than accepting deprecation warnings as harmless. GitHub moved Actions runners to Node 24 by default in June 2026.
