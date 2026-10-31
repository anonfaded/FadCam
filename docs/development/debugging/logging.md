# Logging (FLog)

FadCam uses a privacy-aware logging wrapper `FLog` for user-facing debug logs.

## Why
- Full device logcat access is restricted on Android; in-app logs must be explicit.
- We want a single, safe way to capture logs in `FADCAM_debug.html` without leaking sensitive data.

## What FLog Does
- Writes to both `android.util.Log` (logcat) **and** the in-app HTML log (`com.fadcam.Log`).
- Applies privacy redaction before writing:
  - URLs → `[REDACTED_URL]`
  - Token-like strings → `REDACTED`
  - File paths → `[REDACTED_PATH]`
- Supports a custom redactor hook for additional scrubbing.

## Usage
```java
FLog.d("Tag", "Starting recording...");
FLog.e("Tag", "Failed to open camera: " + reason);
FLog.e("Tag", "Crash", exception);
```

## Notes
- Existing `com.fadcam.Log` calls remain valid and unchanged.
- `FLog` is recommended for any user-facing debug logging to ensure privacy.
