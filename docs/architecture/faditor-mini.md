# Faditor Mini — Architecture Overview

## Quick Reference: File Locations

### Core Activity & Entry Point
- **FaditorEditorActivity.java** — Main editor activity (full-screen)  
  📍 `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`
- **FaditorMiniFragment.java** — Tab entry point (video picker)  
  📍 `app/src/main/java/com/fadcam/ui/FaditorMiniFragment.java`

### Data Models
- **Clip.java** — Single video segment  
  📍 `app/src/main/java/com/fadcam/ui/faditor/model/Clip.java`
- **Timeline.java** — Ordered list of clips  
  📍 `app/src/main/java/com/fadcam/ui/faditor/model/Timeline.java`
- **FaditorProject.java** — Project wrapper  
  📍 `app/src/main/java/com/fadcam/ui/faditor/model/FaditorProject.java`
- **ExportSettings.java** — Export configuration  
  📍 `app/src/main/java/com/fadcam/ui/faditor/model/ExportSettings.java`

### Player & Playback
- **FaditorPlayerManager.java** — ExoPlayer lifecycle & controls  
  📍 `app/src/main/java/com/fadcam/ui/faditor/player/FaditorPlayerManager.java`
- **TimelineView.java** — Custom timeline widget with trim handles  
  📍 `app/src/main/java/com/fadcam/ui/faditor/timeline/TimelineView.java`

### Export & Processing
- **ExportManager.java** — Media3 Transformer orchestration  
  📍 `app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java`

### Project Persistence
- **ProjectStorage.java** — JSON persistence (Gson + auto-save)  
  📍 `app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java`
- **UndoManager.java** — Undo/redo state management  
  📍 `app/src/main/java/com/fadcam/ui/faditor/undo/UndoManager.java`

### Utilities
- **crop/** — Crop transformation logic  
  📍 `app/src/main/java/com/fadcam/ui/faditor/crop/`
- **util/** — Helpers (time formatting, etc.)  
  📍 `app/src/main/java/com/fadcam/ui/faditor/util/`

---

## Architecture Summary

```
User Flow:
FaditorMiniFragment (tab)
  ↓ [video picker]
FaditorEditorActivity (full-screen)
  ├─ FaditorPlayerManager (ExoPlayer playback)
  ├─ TimelineView (custom timeline UI)
  ├─ ProjectStorage (JSON persistence)
  └─ ExportManager (Media3 Transformer)
  ↓ [export done]
FaditorMiniFragment (back to tab with exported file)
```

### Key Design Decisions

| Component | Technology | Why |
|-----------|-----------|-----|
| **Playback** | Media3 ExoPlayer | Battle-tested, hardware acceleration, effects-capable |
| **Export** | Media3 Transformer | Frame-accurate lossless trim, unified pipeline |
| **Trim Mode** | EditList (near-lossless) | Precise frame-level accuracy without re-encoding entire file |
| **Persistence** | JSON (Gson) + auto-save | Simple, scalable, 3-second debounce |
| **Navigation** | Separate Activity | Clean lifecycle, SurfaceView z-order isolation |

### Data Model (Scalable)

```
FaditorProject
├── id: UUID
├── name: String
├── timeline: Timeline
│   └── clips: List<Clip>
│       ├── sourceUri: Uri
│       ├── inPointMs: long
│       ├── outPointMs: long
│       ├── speedMultiplier: float
│       └── effects: List<Effect>
└── exportSettings: ExportSettings
    ├── resolution, quality, format
```

**Supports:** Single trim → multi-clip → effects → audio mixing (future phases)

### Auto-Save Mechanism

- **Debounce Delay:** 3 seconds (configurable via `AUTO_SAVE_DELAY_MS`)
- **Trigger:** Every edit (trim, rotate, flip, crop, speed, audio)
- **Files Saved Together:**
  - `files/faditor/projects/{projectId}/project.json`
  - `files/faditor/projects/{projectId}/undo_history.json`
- **Implementation:** Handler-based debounce in `FaditorEditorActivity`
  ```java
  Handler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY_MS)
  // Resets timer on each edit (debounce)
  ```

---

## Referenced Document
- Full details: See the main repo file `FADITOR_MINI_PLAN.md` for complete tech stack and phase roadmap.
