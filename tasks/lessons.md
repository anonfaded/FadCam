# Lessons

## 2026-08-24 — CI verification
- Never re-add a unit-test gate without checking that the tests still match the current implementation.
- `RecordingToggleActivity.finishWithoutUi()` intentionally calls `finish()` only; an older test expected `moveTaskToBack(true)` and therefore became stale.
- CI verification runs must not cancel an earlier run merely because a newer commit was pushed; each commit under investigation must finish independently so its result is auditable.
