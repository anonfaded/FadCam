# Digital Forensics Roadmap

## Summary
Add a new Advanced subsection named **Digital Forensics** (separate from Motion Lab) to deliver:

- AI-based event intelligence
- Reliable video-to-data linkage across rename/move/paste-back
- Optional live preview AI overlay
- Future cloud-sync-ready architecture

This roadmap is modular, realtime-oriented, and privacy-first.

## Lab Tab Consolidation (Locked 2026-02-14)
- [x] Add 6th bottom-nav item: `Lab`
- [x] Add dedicated tab-root surface titled **Forensic Intelligence**
- [x] Move forensic browsing into Lab (Events + Insights sections)
- [x] Remove Events/Insights navigation rows from Digital Forensics settings (settings now control-only)
- [x] Keep single source-of-truth flow: detector -> recorder -> Room DB -> Lab UI
- [x] Bump forensics DB schema and use latest clean destructive reset policy for this refactor
- [x] Keep English-first strings for new UI labels in this phase

## Hard-Cut Runtime Stack (Locked 2026-02-15)
- [x] Use EfficientDet-Lite1 as the only detector path in recording analysis
- [x] Remove overlay payload compatibility fallback logic in renderer path (strict `LABEL|CONF|cx|cy|w|h|TYPE`)
- [x] Shift forensic event persistence to real-time heartbeat writes (no stop-only dependency)
- [x] Add multi-event active aggregation keyed by media+type+class
- [x] Add persistent snapshot evidence table (`ai_event_snapshot`) + DAO
- [x] Add lifecycle metadata fields to `ai_event` (`status`, seen-range, sample count, peak confidence, alert fields)
- [x] Bump forensics DB schema to latest (`version=5`, destructive migration policy)
- [x] Move Lab root from tabbed pager to events-first single surface with top-right hamburger
- [x] Add Lab sidebar shell with Insights/admin destinations

## Architecture Decision (Locked 2026-02-13)
- [x] Single detector source of truth: **EfficientDet-Lite1**
- [x] Legacy person-only detector removed
- [x] No detector fallback paths to legacy models
- [x] Preview overlay only (never burned into encoded video)
- [x] Detector runtime uses **TFLite Task Vision API** (metadata-driven labels/output parsing, no manual label file)

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
- [x] Daily summary (always-on analytics)
- [x] Heatmap insights (always-on analytics)
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
- [x] Extend existing analysis pipeline to emit structured events (motion START/STOP transitions)
- [x] Persist events to `ai_event`
- [x] Store representative thumbnails (timeline reference via `thumbnail_ref`)
- [x] Keep dangerous-object tagging opt-in only
- [x] Ensure motion-trigger path remains stable (async, no control-flow changes)

**Deliverable:** Event metadata linked to media assets.

## Phase 3 - Events UX
- [x] Add Events view in Records context
- [x] Add filters (type, date, confidence)
- [x] Tap event -> seek exact timestamp and open paused (no autoplay)
- [x] Show link quality badge (Exact/Probable)
- [x] Show proof frame thumbnail for each event row

**Deliverable:** Searchable evidence timeline experience.

## Phase 4 - Insights + Overlay
- [x] Add daily summary cards
- [x] Add heatmap from bbox centers
- [x] Add optional live overlay rendering (auto labels baseline)
- [x] Ensure overlay-off mode has minimal overhead

**Deliverable:** Intelligence layer without manual drawing workflow.

## Phase 5 - Hardening
- [x] Rename/move/import stress hardening (multi-signal relink guards + confidence threshold)
- [x] Re-encode similarity baseline validation path (visual hash probable relink)
- [x] Mid-range thermal/performance safeguards (async indexing/event writes, overlay toggle off by default)
- [x] Regression checks for Motion Lab + Records (compile + non-blocking hooks)
- [x] DB migration and recovery tests baseline (migration 1->2 added for new event metadata)

**Deliverable:** Production-ready reliability baseline.

## Acceptance Criteria
- [ ] Rename/move keeps data linked automatically
- [ ] Paste-back relinks via exact/probable match
- [ ] No false hijack of unrelated videos
- [x] Events open correct video timestamps
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
