# Developer & Agent Guide: Best Practices for Workflow Orchestration and Task Management

## Workflow Orchestration

### 1. Plan Node Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately - don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
-Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness
  
### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes - don't over-engineer
- Challenge your own work before presenting it

#### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests - then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Task Management
1. **Plan First**: Write plan to `tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review section to `tasks/todo.md`
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

## Core Principles

### 7. Device Test Playbook — record, pull, verify (FadCam)

Use this exact flow when testing recordings on a connected phone. Do NOT improvise or
re-invent these steps.

**Install** (always via gradle — never `adb install` the APK manually):
```bash
./gradlew :app:installDefaultDebug
```

**Package names** (CRITICAL — debug has `applicationIdSuffix = ".beta"`):
- App package: `com.fadcam.beta` (stable release is `com.fadcam` — never record into it)
- Manifest classes resolve against the *namespace* `com.fadcam`, NOT the app id.
  Relative names (`com.fadcam.beta/.RecordingStartActivity`) FAIL with "does not exist".
  Always use the full component: `com.fadcam.beta/com.fadcam.RecordingStartActivity`.

**Record** (start → wait → stop):
```bash
adb -s <SERIAL> shell am start -n com.fadcam.beta/com.fadcam.RecordingStartActivity
sleep 15   # recording duration
adb -s <SERIAL> shell am start -n com.fadcam.beta/com.fadcam.RecordingStopActivity
```

**Find the file** (app saves to a custom SAF download folder; the path may be
double-prefixed e.g. `Download/FadCam/Camera/Back/Camera/Back/` — do not hardcode it):
```bash
adb -s <SERIAL> shell "find /storage/emulated/0/Download /storage/emulated/0/Android/data/com.fadcam.beta -name '*.mp4' -mmin -5 2>/dev/null"
adb -s <SERIAL> pull "<PATH>" /tmp/fadcam_session/out/
```

**Verify the recording** (always — this catches the stsz/trun mismatch class of bugs):
1. `ffmpeg -v error -i file.mp4 -f null -` → must exit 0, no "Invalid NAL unit size"
2. `ffprobe -v error -show_streams -show_format file.mp4` → duration must NOT be `-1ms`
3. Box-walk with Python: appended `moov` stsz entries must EXACTLY equal the concatenated
   fragment `trun` sample sizes for BOTH tracks (0 mismatches = PASS). trun flag bits:
   `0x001`=data_offset, `0x100`=duration, `0x200`=size, `0x400`=flags; media3 interleaves
   per-sample `[duration, size, flags]`, NOT grouped arrays.

**Cleanup** (after every test — never leave artifacts):
```bash
adb -s <SERIAL> shell rm -f "<PULLED_PATH>"
rm -rf /tmp/fadcam_session
```

## Core Principles (legacy)
- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Backward Compat**: Never add backward-compatible overloads, fallbacks, or polyfills to support outdated code. When frontend/backend/Android contract changes (method signatures, API shapes), always ask the user before silently adding compat shims. Clean deploys preferred over layered hacks.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

---