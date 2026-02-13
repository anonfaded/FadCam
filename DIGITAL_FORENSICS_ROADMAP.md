# Digital Forensics Roadmap

## Summary
Add a new Advanced subsection named **Digital Forensics** (separate from Motion Lab) to deliver:

- AI-based event intelligence
- Reliable video-to-data linkage across rename/move/paste-back
- Optional live preview AI overlay
- Future cloud-sync-ready architecture

This roadmap is modular, realtime-oriented, and privacy-first.

## Product Positioning
- Keep **Advanced** section unchanged.
- Add a new feature card: **Digital Forensics**.
- Keep **Motion Lab** as separate feature.
- Discord alerts: **Coming Soon** placeholder for now.

## Primary Requirement: Persistent Video Identity Linkage

### Goal
A video should retain linked forensic/event data after:
- rename
- folder move
- export/import back into FadCam

### Identity Strategy
1. **Exact Fingerprint**
- sampled SHA-256 blocks
- file size
- duration
- codec/container profile

2. **Visual Fingerprint**
- keyframe perceptual hash (pHash/aHash)

3. **Origin Hints**
- first-seen path/name/timestamps

### Match Policy
- Exact match -> auto relink
- High-confidence similarity -> **Probable relink** (auto + badge)
- Low confidence -> create new media record

### Metadata Embedding
- Write `media_uid` into video metadata where possible (best-effort)
- Never rely only on metadata (it can be stripped)
- DB fingerprints remain source of truth

## Data Model (Room SQLite)

### `media_asset`
- `media_uid` (UUID PK)
- `current_uri`
- `display_name`
- `category/subtype`
- `size_bytes`
- `duration_ms`
- `codec_info`
- `exact_fingerprint`
- `visual_fingerprint`
- `first_seen_at`
- `last_seen_at`
- `link_status` (`EXACT`, `PROBABLE`, `NEW`)

### `ai_event`
- `event_uid` (UUID PK)
- `media_uid` (FK)
- `event_type` (`PERSON`, `VEHICLE`, `PET`, `DANGEROUS_OBJECT`)
- `start_ms`
- `end_ms`
- `confidence`
- `bbox_norm`
- `track_id`
- `priority`
- `thumbnail_ref`

### `integrity_link_log`
- `log_uid`
- `media_uid`
- `action` (`LINKED_EXACT`, `LINKED_PROBABLE`, `UNLINKED`, `MISMATCH`)
- `score`
- `timestamp`

### `sync_queue` (future)
- `op_uid`
- `entity_type`
- `entity_id`
- `operation`
- `payload_json`
- `status`
- `retry_count`

## Feature Toggles (Digital Forensics screen)
- [x] Enable Digital Forensics
- [x] Event classes (Person / Vehicle / Pet)
- [x] Dangerous object tags toggle (opt-in)
- [x] Show AI overlay in live preview
- [x] Daily summary
- [x] Heatmap insights
- [x] Discord alerts (Coming Soon placeholder)

## Phase-by-Phase Execution

## Phase 0 - UX Shell + Navigation
- [x] Create Digital Forensics card in Advanced
- [x] Add settings screen skeleton
- [x] Add Coming Soon Discord row
- [x] Add helper texts and safety disclaimers

**Deliverable:** Navigable Digital Forensics settings UI (no backend inference yet).

## Phase 1 - DB & Identity Foundation
- [x] Add Room DB module, entities, DAOs, migrations (baseline v1 schema)
- [x] Implement exact fingerprint generator (sampled SHA-256 blocks)
- [x] Implement visual fingerprint generator (keyframe aHash baseline)
- [x] Build relink engine (exact + probable baseline scoring)
- [x] Integrate with existing Records scan pipeline (async background hook)
- [x] Add link status badges support in data layer (`link_status` stored)

**Deliverable:** Persistent asset identity with robust relink behavior.

## Phase 2 - Event Capture Integration
- [ ] Extend existing analysis pipeline to emit structured events
- [ ] Persist events to `ai_event`
- [ ] Store representative thumbnails
- [ ] Keep dangerous-object tagging opt-in only
- [ ] Ensure motion-trigger path remains stable

**Deliverable:** Event metadata linked to media assets.

## Phase 3 - Events UX
- [ ] Add Events view in Records context
- [ ] Add filters (type, date, confidence)
- [ ] Tap event -> seek exact timestamp
- [ ] Show link quality badge (Exact/Probable)

**Deliverable:** Searchable evidence timeline experience.

## Phase 4 - Insights + Overlay
- [ ] Add daily summary cards
- [ ] Add heatmap from bbox centers
- [ ] Add optional live overlay rendering (auto labels/boxes only)
- [ ] Ensure overlay-off mode has minimal overhead

**Deliverable:** Intelligence layer without manual drawing workflow.

## Phase 5 - Hardening
- [ ] Rename/move/import stress testing
- [ ] Re-encode similarity behavior validation
- [ ] Mid-range thermal/performance checks
- [ ] Regression checks for Motion Lab + Records
- [ ] DB migration and recovery tests

**Deliverable:** Production-ready reliability baseline.

## Acceptance Criteria
- [ ] Rename/move keeps data linked automatically
- [ ] Paste-back relinks via exact/probable match
- [ ] No false hijack of unrelated videos
- [ ] Events open correct video timestamps
- [ ] Motion Lab remains independent from Digital Forensics toggles
- [ ] Mid-range realtime performance remains acceptable

## Risks & Mitigations
- Re-encoded files lose exact match  
  Mitigation: visual fingerprint + probable-match badge

- False probable relink  
  Mitigation: confidence thresholds + link log + optional review queue later

- Thermal increase with inference/overlay  
  Mitigation: adaptive FPS and defaults OFF

## Files to Maintain for Context
- `DIGITAL_FORENSICS_ROADMAP.md` (master roadmap)
- `DIGITAL_FORENSICS_PHASE_LOG.md` (ongoing decisions/findings)
- `MOTION_LAB_ROADMAP.md` (separate, cross-link only)

## Defaults
- Digital Forensics: OFF by default
- Dangerous object tags: OFF by default
- Overlay: OFF by default
- Match policy: Exact + Probable (with badge)
- Discord alerts: Coming Soon (no active network flow)
