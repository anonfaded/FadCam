# Digital Forensics Phase Log

## Current Status
- Active phase: **Phase 1 (DB & Identity Foundation)**
- Last updated: 2026-02-13

## Phase 0 - UX Shell + Navigation
### Completed
- [x] Added **Digital Forensics** row in `fragment_settings_advanced.xml`
- [x] Wired navigation from `AdvancedSettingsFragment` to `DigitalForensicsSettingsFragment`
- [x] Added `DigitalForensicsSettingsFragment` screen with:
  - [x] Master enable toggle
  - [x] Event class toggles (person/vehicle/pet)
  - [x] Dangerous object toggle
  - [x] Overlay toggle
  - [x] Daily summary toggle
  - [x] Heatmap toggle
  - [x] Discord alerts Coming Soon row
- [x] Added preference keys in `Constants.java`
- [x] Added preference getters/setters in `SharedPreferencesManager.java`
- [x] Added strings for Digital Forensics UI
- [x] Added roadmap files:
  - [x] `DIGITAL_FORENSICS_ROADMAP.md`
  - [x] `DIGITAL_FORENSICS_PHASE_LOG.md`

### Findings
- Current implementation is UI + preference shell only (expected for Phase 0).
- Motion Lab remains independent and unaffected.
- Discord integration intentionally placeholder-only.

### Open items before Phase 1
- [ ] Define Room package location and naming conventions
- [ ] Freeze migration policy (versioning + fallback)
- [ ] Finalize exact fingerprint sampling algorithm
- [ ] Finalize visual fingerprint extraction strategy

## Phase 1 - DB & Identity Foundation
### Completed (baseline implementation)
- [x] Added Room dependencies and local DB module (`digital_forensics.db`)
- [x] Added entities: `media_asset`, `ai_event`, `integrity_link_log`, `sync_queue`
- [x] Added DAOs and `ForensicsDatabase` singleton
- [x] Implemented exact fingerprint baseline (sampled SHA-256)
- [x] Implemented visual fingerprint baseline (keyframe aHash)
- [x] Implemented relink engine with exact/probable policy
- [x] Added non-blocking Records pipeline indexing hook
- [x] Persisted `link_status` for future UI badges

### Findings
- Room schema is in place and compile-valid.
- Records loading remains non-blocking; indexing runs on a separate single-thread executor.
- Current visual fingerprint is a lightweight baseline; future phases can improve robustness for heavy re-encodes.

### Next (Phase 2 prep)
- [ ] Emit structured AI events into `ai_event` from analysis pipeline
- [ ] Add thumbnail references for events
- [ ] Add event retention policy and cleanup strategy

## Notes
- Keep all heavy compute asynchronous.
- Preserve existing Records performance characteristics.
- Do not couple Digital Forensics toggles to Motion Lab control flow in this phase.
