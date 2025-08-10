# FadCam Settings Refactor Plan

Goal: Decompose the monolithic `SettingsFragment` + giant XML into modular, iOS‑style grouped setting screens while preserving ALL current logic, preference keys, and behaviors. Refactor proceeds incrementally with safe, verifiable steps.

---
## 0. Guiding Principles
1. No breaking of existing preference keys or broadcast actions.
2. Move UI first, then (optionally) extract logic into lightweight controllers/helpers – never rewrite logic inline during the same step you move it.
3. Keep every step shippable (feature‑flag / fall back possible until late phase).
4. Small PR-sized chunks: each phase should compile & pass manual smoke (open and toggle each moved setting -> persists -> reopens intact).
5. Visual polish AFTER structural split (avoid mixing concerns).

---
7.2 Bottom sheet pickers (PARTIAL – video recording settings migrated)
7.3 Theming utility (`ThemeUiUtil`) (PENDING)
7.4 Helper text compression / relocation (PARTIAL – removed, not relocated)
7.5 Consistent trailing divider removal (DONE)
7.6 Unified compact card row style (DONE)
7.7 Overlay navigation unified fade animation (DONE)

### Phase 8 – Cleanup & Removal
8.1 Remove dead legacy code (PENDING)
8.2 Delete/slim monolithic layout (PENDING)
8.3 Slim or deprecate `SettingsFragment` (PENDING)
8.4 Final style/lint pass (PENDING)

### Phase 9 – Optional Enhancements
9.1 Settings search (PENDING)
9.2 Watermark live preview (PENDING)
9.3 Compose migration (PENDING)
9.4 About screen w/ version & licenses (PENDING)

---
## 4. Master Checklist (Live Progress)
Legend: [x] Done · [~] Partial/In Progress · [ ] Pending

### Phase 1

- [x] Home fragment & navigation
- [ ] Feature flag fallback

### Phase 2 (Appearance)

- [x] Fragment & rows migrated
- [ ] Legacy removal
- [~] Regression

### Phase 3 (Video)

- [x] Fragment & UI migrated
- [ ] Legacy rows removed
- [~] Full regression

### Phase 4 (Audio & Storage)

- [x] Audio fragment
- [x] Storage fragment (UI)
- [x] Storage chooser logic
- [x] Audio mic selection parity
- [ ] Legacy rows removed
- [~] Regression

### Phase 5 (Location & Privacy)

- [x] Fragment
- [~] Permission flow re-test
- [ ] Legacy rows removed

### Phase 6 (Security / Behavior / Advanced / Watermark)

- [x] Fragments
- [~] App Lock logic migrated
- [~] Watermark logic migrated
- [ ] Legacy rows removed
- [~] Regression

### Phase 7 (Polish)

- [x] Compact row style
- [x] Card grouping
- [x] Icon tint + 14dp chevron
- [x] Removed trailing dividers
- [ ] Reusable row layouts
- [~] Bottom sheets (Video settings implemented; extend to Audio/Storage pickers next)
- [ ] Theme util
- [~] Helper text relocation
- [x] Unified overlay open/close animation
- [x] About header color normalized

### Phase 8 (Cleanup)

- [ ] Dead code removal
- [ ] Legacy layout deletion/slim
- [ ] Fragment slimming
- [ ] Final lint/style

### Phase 9 (Enhancements)
- [ ] Search
- [ ] Watermark preview
- [ ] Compose migration
- [ ] About screen

### New Follow-Up Tasks
- [ ] Deduplicate audio/video logic (eliminate dual sources)
- [ ] Audit & remove any remaining per-fragment back handlers (most removed)
- [ ] Shorten any remaining verbose status strings (pattern used for audio source)
- [ ] Accessibility: add contentDescription for icons & chevrons
- [ ] Overdraw/performance audit on low-end devices
- [ ] Unit tests for SharedPreferencesManager keys
- [x] Implement Storage chooser migration & broadcast test (basic broadcast wired; add instrumentation later)
- [x] README unified bottom sheet (content migrated from dialog)
- [ ] README markdown rich rendering (future enhancement)
- [~] Review flow (WebView Google Form integrated; optional Play in-app review later)

---
## 5. Risk & Mitigation Matrix
| Risk | Mitigation |
|------|------------|
| Logic regression (camera/audio) | Copy methods verbatim first, refactor later |
| Preference key mismatch | No renames; rely on central constants |
| Receiver leaks | Register only in active fragments; audit lifecycle |
| Theming inconsistencies | Introduce `ThemeUiUtil` central helper (Phase 7) |
| UI duplication | Replace rows with reusable layouts (Phase 7) |

---
## 6. Controller Extraction Targets (Optional)
- CameraSettingsController
- VideoEncodingController
- StorageController
- AudioController
- ThemeUiUtil

Extraction happens only after stable UI migration to avoid simultaneous move+rewrite risk.

---
## 7. Regression Checklist (Per Fragment)
1. Open fragment – no crash
2. Change each setting – immediate UI update
3. Navigate away/back – state persists
4. App restart – state persists
5. Related runtime actions still behave (recording, watermark, audio)
6. Theme applied correctly

---
## 8. Current Status Summary (Live)
All modular fragments exist; UI polishing (compact rows, grouped cards, chevrons) complete. Audio strings shortened; redundant back logic removed. Legacy `SettingsFragment` still retains original UI/logic blocks (duplicate source of truth). Major remaining work: migrate storage chooser & remaining watermark/app lock logic, remove legacy code, introduce reusable components & theming utility, then cleanup. No regressions reported in basic manual tests; deeper device/permission/storage flows still pending.

---
## 9. Next Immediate Actions (Revised)

1. Extend bottom sheet pattern to Audio (mic source) & Storage (future chooser) for UI consistency.
2. Implement Storage chooser & migrate logic; verify persistence & broadcast.
3. Migrate remaining watermark + app lock logic; remove corresponding legacy rows.
4. Introduce reusable row layout XMLs and refactor two pilot fragments (Video & Appearance).
5. Create `ThemeUiUtil`; refactor color/tint code in 2–3 fragments.
6. Accessibility & regression pass (TalkBack, rotation, low-light theme).
7. Begin dead code purge and monolithic layout deletion.
8. Update plan & decide on controller extraction vs closure.

---
\n## 10. Notes
- Commit prefix: `refactor(settings): phase-x.y description`

- Keep PRs small & independently shippable.

---
\n## 11. Added (Aug 9 2025) – Remaining Migration Audit & Bottom Sheet Picker Plan

### 11.1 Remaining Legacy Logic To Extract / Verify

- [ ] Location embedding toggle & broadcast parity (distinct from location watermark) into Location & Privacy fragment.
- [ ] Auto update check switch (PREF_AUTO_UPDATE_CHECK) – migrate to Behavior fragment.
- [ ] Theme/App Icon residual handlers (ensure no hidden listeners only in monolith).
- [ ] Debug / developer toggles (logging, README trigger) -> Advanced fragment parity.
- [ ] Haptics / vibration helper centralization (vibrateTouch) – consider `HapticsUtil`.
- [ ] Bitrate helper text duplication – keep single implementation post-picker.
- [ ] Camera fallback when no back camera enumerated – regression test.
- [ ] Accessibility contentDescription for all setting rows.
- [ ] Permission re-init broadcasts (location) fully outside monolith.

### 11.2 Feature Flag

Added in `SharedPreferencesManager`: `new_pickers_enabled` (default false) gating modern pickers.

### 11.3 Bottom Sheet Picker Architecture

Components:

1. OptionItem (id, title, optional subtitle/badge)
2. PickerBottomSheetFragment (args: title, list<OptionItem>, selectedId, allowSearch, analyticsKey)
3. NumericInputBottomSheetFragment (min, max, unit, current, default, thresholds)
4. Mapper utilities (ResolutionMapper, FrameRateMapper, CodecMapper, CameraMapper, ZoomRatioMapper, SplitSizeMapper)
5. FragmentResult: key `picker_result_\<setting>` -> { selectedId, customValue? }

Migration Order (behind flag): Orientation -> Resolution -> Frame Rate -> Codec -> Zoom Ratio -> Bitrate (two-step) -> Video Splitting.

### 11.4 Incremental Steps

- S1 Scaffold picker framework (no wiring)
- S2 Wire Orientation picker (flag ON), fallback to dialog otherwise
- S3 Regression orientation persistence
- S4 Add Resolution + Frame Rate pickers
- S5 Add Codec + Zoom Ratio
- S6 Add Bitrate mode sheet + numeric custom sheet
- S7 Add Video Splitting sheet
- S8 Internal QA enable flag; refine UX
- S9 Remove legacy dialogs; default flag true

### 11.5 Picker Testing Matrix

- Select option -> preference updates + row value updates
- Reopen sheet -> correct selection highlighted
- Cancel -> no change
- Numeric invalid -> disabled confirm + inline error
- Boundary min/max accepted
- Rotation preserves state
- Back dismiss only sheet

### 11.6 Accessibility

- Row contentDescription: "\<Title>, current value \<Value>"
- Sheet items announce selection state
- Numeric sheet error role=alert

### 11.7 Deferred Controllers

After picker rollout, optionally introduce `VideoSettingsController` & `AudioSettingsController` for enumeration & labeling; defer to avoid churn.

### 11.8 Cleanup Triggers

- After S4: mark orientation dialog deprecated (remove post S9)
- After S7: schedule monolith slimming pass (video/audio dialog methods)

### 11.9 Tooling

- Add instrumentation/unit tests for: new pickers flag, bitrate custom save, split size custom bounds.

### 11.10 Open Questions

- Kotlin adoption for new pickers? (Currently staying in Java; revisit post-cleanup.)
- Search activation threshold (e.g., >12 options) – future enhancement.

---
\n## 12. Immediate Next Actions (Updated)

1. Migrate location embedding + auto update switch.
2. Expose hidden dev toggle to flip `new_pickers_enabled`.
3. Scaffold picker framework (S1).
4. Implement Orientation picker (S2) & verify.
5. Proceed to Resolution & Frame Rate pickers (S4).

Status: Step 2 partially complete (flag added, no UI). Next: add dev toggle + scaffold OptionItem & empty bottom sheet.
\n## 2. Target Structure (Grouped / iOS Style)
Top-level “Settings Home” with grouped cards (or list) navigating to focused sub‑screens:

| Group | Contents |
|-------|----------|
| Appearance | Theme, App Icon, Language |
| Security | App Lock |
| Camera & Lenses | Front/Back toggle, Back lens picker |
| Video Recording | Resolution, Frame Rate, Zoom Ratio, Codec, Bitrate, Orientation, Splitting |
| Watermark (own screen) | Watermark option + future preview |
| Audio | Enable audio, Input source select |
| Storage | Internal vs Custom + path chooser |
| Location & Privacy | Location toggle, Embed toggle |
| Behavior | Onboarding, Auto update check |
| Advanced / About | Debug logging, README, Review (later About info) |

Reusable Row Patterns:

- Toggle Row (icon + title + subtitle + switch)
- Value Row (icon + title + current value + chevron)
- Navigation Row (icon + title + chevron, optional subtitle)

---
\n## 3. Phased Execution Roadmap

### Phase 0 – Baseline / Safety
Capture baseline (current fragment untouched). Add this plan file. (DONE)

### Phase 1 – Navigation Skeleton & Home
1.1 Add this plan & checklist (DONE)
1.2 Scaffold `SettingsHomeFragment` + layout with group entries (DONE)
1.3 Wire `MainActivity` (or wherever tab selects current Settings) to open new Home instead of legacy (PENDING)
1.4 Add placeholder click handlers (Toast) (DONE in scaffold)
1.5 Add feature flag / fallback (optional) (PENDING)

### Phase 2 – Extract Appearance
2.1 Create `AppearanceSettingsFragment`
2.2 Copy only Theme/App Icon/Language UI rows to new layout
2.3 Reuse existing setup methods (or extract to `AppearanceSettingsController`)
2.4 Remove those rows from legacy fragment (leave stubs / comments)
2.5 Manual regression (theme switch persists & applies)

### Phase 3 – Extract Video (Largest Risk)
3.1 New `VideoSettingsFragment` (camera selection + lens + resolution + frame rate + zoom + codec + bitrate + orientation + splitting)
3.2 Introduce `CameraSettingsController` & `VideoSettingsController` (pure UI wiring & reuse of existing logic methods)
3.3 Migrate camera detection methods
3.4 Ensure all preference writes identical (verify keys)
3.5 Remove migrated UI blocks from legacy fragment
3.6 Smoke: switch front/back, lens changes, resolution/fps persists, bitrate dialog opens

### Phase 4 – Extract Audio & Storage
4.1 `AudioSettingsFragment` (audio toggle + input source dialog launcher)
4.2 `StorageSettingsFragment` (radio group + custom folder chooser)
4.3 Move related receiver registration only where needed
4.4 Manual regression: custom folder persists, broadcast still dispatched

### Phase 5 – Extract Location & Privacy
5.1 `LocationPrivacySettingsFragment` (two toggles)
5.2 Verify permissions prompts unchanged

### Phase 6 – Security, Behavior, Advanced, Watermark
6.1 `SecuritySettingsFragment` (App Lock)
6.2 `BehaviorSettingsFragment` (Onboarding, Auto update)
6.3 `AdvancedSettingsFragment` (Debug, README, Review) – README dialog reused
6.4 `WatermarkSettingsFragment` – move watermark logic; optionally add preview placeholder
6.5 Remove respective rows from legacy fragment

### Phase 7 – Reusable Components + Polish
7.1 Create row layouts (`row_setting_toggle.xml`, `row_setting_value.xml`, `row_setting_nav.xml`)
7.2 Replace ad-hoc LinearLayouts in new fragments
7.3 Introduce bottom sheet pickers (resolution, frame rate, codec, watermark) replacing spinners
7.4 Consolidate theming logic into `ThemeUiUtil` (central color & mode functions)
7.5 Trim long helper texts → info dialog / bottom sheet

### Phase 8 – Cleanup & Removal
8.1 Remove unused methods from legacy fragment
8.2 Delete legacy layout blocks
8.3 Shorten `SettingsFragment` (possibly deprecate entirely)
8.4 Final pass: code style, import cleanup

### Phase 9 – Optional Enhancements (Post-Core)
9.1 Settings search
9.2 Watermark live preview
9.3 Compose migration for row list
9.4 About screen with version/build info

---
\n## 4. Master Checklist (Live Progress)

Legend: [ ] Pending | [~] In Progress | [x] Done

### Phase 0
- [x] Baseline capture (no functional change)
- [x] Plan document added

### Phase 1 (Navigation + Home)
 [ ] Removed from legacy (legacy still contains original rows / logic)
 [~] Regression passed (basic manual toggle + persistence verified; full theme edge cases pending)
- [x] Integrate Home fragment into existing navigation entry point
 [ ] Legacy rows removed (still present in `SettingsFragment` – cleanup scheduled Phase 8)
 [~] Regression passed (core interactions tested; exhaustive device/lens matrix & rotation tests pending)
### Phase 2 (Appearance)
 [x] Fragments created (`AudioSettingsFragment`, `StorageSettingsFragment`)
 [ ] Storage chooser + broadcast verified (UI row present; chooser logic not yet migrated)
 [x] Audio input selection works (dialog + preference updates in new fragment)
 [ ] Legacy rows removed
 [~] Regression passed (audio toggles & input selection verified; storage persistence not yet validated)
- [ ] Removed from legacy
 [x] Fragment created (`LocationPrivacySettingsFragment`)
 [~] Permission flow intact (needs re-test for first-run permission & denial path)
 [ ] Legacy rows removed

 [x] Fragments created (`SecuritySettingsFragment`, `BehaviorSettingsFragment`, `AdvancedSettingsFragment`, `WatermarkSettingsFragment`)
 [~] App Lock operations intact (UI migrated; end-to-end lock/unlock regression pending)
 [~] Watermark logic relocated (UI moved; some logic still referenced in legacy; preview not implemented)
 [ ] Legacy rows removed
- [x] UI migrated (camera, lens, resolution, fps, codec, bitrate, orientation, zoom, splitting)
 Core polish partially applied early (not all original subtasks complete yet):
 [x] Compact single-line row pattern applied across all new fragments
 [x] Card grouping with rounded background applied
 [x] Unified icon tint + right-aligned value + 14dp chevron
 [x] Removed trailing divider in each card's last row
 [ ] Reusable row layouts (still using duplicated inline LinearLayouts)
 [ ] Bottom sheet pickers (dialogs/spinners still legacy style)
 [ ] Central theming util (theme logic still duplicated)
 [ ] Helper text compression (most helper text removed; not yet relocated into dialogs/tooltips)
// -------------- Fix Ended for this section(Phase 3 Video Status)-----------
 [ ] Remove dead code (identify superseded methods per feature)
 [ ] Delete / greatly slim legacy `SettingsFragment` & monolithic layout
 [ ] Final style/lint pass
 [ ] Extract controllers (if still desired) OR decide to keep inline for now and close plan
- [ ] Fragments created
 [ ] Settings search
 [ ] Watermark preview
 [ ] Compose migration (phase gate)
 [ ] About screen (expanded build/version, OSS licenses)
## 8.1 Newly Identified Follow-Up Tasks (Added During Implementation)
 [ ] Normalize duplicate audio / video logic still present in legacy fragment to avoid split source of truth.
 [ ] Central Back/Overlay handler: remove any remaining per-fragment back code (most already removed; audit others).
 [ ] Consistent string shortening (e.g., removed "Currently using:" prefix) – replicate for any other verbose status strings.
 [ ] Accessibility pass: content descriptions for newly added ImageViews (chevrons, icons) where missing.
 [ ] Scroll performance check on lower-end devices (ensure no overdraw from nested backgrounds).
 [ ] Unit test SharedPreferencesManager getters/setters for migrated keys.

 All targeted modular fragments have been created and visually standardized (compact rows, grouped cards, consistent icon tint, 14dp chevrons). Appearance, Video, Audio, Storage (basic), Location & Privacy, Behavior, Security, Advanced, and Watermark screens are in place. Logic largely copied (not yet abstracted into controllers) and legacy `SettingsFragment` still contains original blocks to be cleaned. Audio strings shortened and redundant back handler removed. Storage chooser logic and some watermark/app lock behaviors still rely on legacy implementation. Phase 7 component abstraction (reusable row layouts, theming utility, bottom sheets) not yet started; cleanup & de-dup remain the next structural milestone.

- [ ] Fragment created
- [ ] Permission flow intact
- [ ] Legacy rows removed

### Phase 6 (Security / Behavior / Advanced / Watermark)

- [ ] Fragments created
- [ ] App Lock operations intact
- [ ] Watermark logic relocated
- [ ] Legacy rows removed

### Phase 7 (Components + Polish)

- [ ] Reusable row layouts
- [ ] Bottom sheet pickers
- [ ] Central theming util
- [ ] Helper text compression

### Phase 8 (Cleanup)

- [ ] Remove dead code
- [ ] Delete legacy fragment/layout (or slim wrapper)
- [ ] Final style/lint pass

### Phase 9 (Enhancements – Optional)

- [ ] Settings search
- [ ] Watermark preview
- [ ] Compose migration (phase gate)
- [ ] About screen

---
\n## 5. Risk & Mitigation Matrix

| Risk | Mitigation |
|------|------------|
| Logic regression when moving camera code | Migrate methods wholesale; do not edit lines inside during migration PR |
| Missed preference key rename causing state loss | Maintain central table of keys (already in SharedPreferencesManager) – no renames allowed |
| Broadcast receivers leak after split | Register/unregister only in fragments that own related UI; audit onStop/onStart |
| Overlapping lifecycle side effects | Keep side-effect code (e.g., camera detection) lazy until first needed |
| UI inconsistency across themes | Centralize theme color resolution before Polish phase |

---
\n## 6. Controller Extraction Targets

- CameraSettingsController: detection, resolution/fps population, lens logic, zoom, bitrate info update.
- VideoEncodingController: codec list, bitrate dialog.
- StorageController: custom path selection + broadcast.
- AudioController: mic scan & dialog.
- ThemeUiUtil: button appearance, dialog button coloring, heading color application.

Extraction happens only *after* the relevant fragment UI migrates (avoid moving + rewriting simultaneously).

---
\n## 7. Validation / Manual Regression Checklist (Per Migrated Group)

1. Open fragment -> no crash.
2. Toggle / change each setting -> immediate UI feedback.
3. Navigate away & back -> persisted state correct.
4. Kill app & relaunch -> persisted state correct.
5. Perform relevant action (start recording, etc.) -> ensure disabled controls react (where applicable).
6. Theming still applied for dark/light & custom themes.

---
\n## 8. Current Status Summary (Live)
Home screen scaffold integrated and initial visual polish (icons, subtitles, ripple rows) applied. Next: begin Phase 2 (Appearance fragment extraction) while keeping legacy accessible.

---
\n## 9. Next Immediate Actions

1. Wire `SettingsHomeFragment` into navigation (replace direct use of `SettingsFragment` in MainActivity or tab adapter) with a temporary button inside Home that opens old “All Settings (Legacy)” for parity testing.
2. Create `AppearanceSettingsFragment` layout + migrate theme/icon/language rows.
3. Update this file marking progress and noting any deltas.

---
\n## 10. Notes

- Keep commit messages prefixed: `refactor(settings): phase-x.y description`.
- Avoid combining multiple group migrations in one commit.

