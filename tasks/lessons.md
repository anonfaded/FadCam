# Lessons

## 2026-08-24 — CI verification
- Never re-add a unit-test gate without checking that the tests still match the current implementation.
- `RecordingToggleActivity.finishWithoutUi()` intentionally calls `finish()` only; an older test expected `moveTaskToBack(true)` and therefore became stale.
- CI verification runs must not cancel an earlier run merely because a newer commit was pushed; each commit under investigation must finish independently so its result is auditable.
- Release signing must be validated as a real Gradle task before the expensive release build; a generated CI key is acceptable for device verification but must never fall back to the Android debug certificate.
- Do not hard-code one Android build-tools patch version when the runner can expose a newer compatible version; select the installed version and verify the required tools exist.
- Rolling GitHub releases can fail if an old tag survives release deletion; explicitly delete the tag and poll for remote disappearance before creating the replacement release.
- Upgrade GitHub Actions to Node 24-compatible releases rather than accepting deprecation warnings as harmless. GitHub moved Actions runners to Node 24 by default in June 2026.

## 2026-08-25 — Production control room V1
- Keep the existing SCENES entry point stable and replace only its implementation so the new control room does not introduce another navigation surface.
- Treat Preview and Program as separate persistent state; selecting a scene must not silently take it live.
- Clamp persisted timing values and treat invalid persisted enum values as recoverable state corruption rather than crashing the production UI.
- Verify layout math for program-control rows separately from build correctness; weighted spacer views can silently distort a phone-sized switcher even when the APK compiles.
- Do not add fake Coming Soon controls while the production engine is being stabilized. Add each future subsystem only when its backing behavior is implemented and verified.

## 2026-08-25 — APK delivery / CI correction
- When a release workflow has already produced a verified APK for the exact master code, update README provenance from the actual release asset rather than inventing a new APK path or checksum.
- Verify the release target commit and APK SHA-256 directly from the GitHub release metadata before documenting them.
- A GitHub Contents API commit is not sufficient evidence that a new Actions run was triggered; check the actual workflow run/release state before claiming a new APK was built.
- Keep documentation-only commits explicitly separate from APK code provenance so the README never implies that an unverified build was published.
