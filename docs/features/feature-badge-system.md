# New Feature Badge System

## Overview

The New Feature Badge System is a centralized mechanism to highlight new features to users. Badges appear as green "NEW" labels on UI elements and automatically disappear once the user interacts with that feature.

**Design Goal:** Easy tracking of new features. When features are ready for release, all badge code can be removed cleanly via the removal checklist.

---

## How It Works

### Core Components

1. **NewFeatureManager.java** - Centralized badge lifecycle manager
   - `shouldShowBadge(context, featureKey)` - Returns true if feature not yet seen
   - `markFeatureAsSeen(context, featureKey)` - Marks feature as seen in SharedPreferences
   - `resetFeatureBadge(context, featureKey)` - Testing utility to reset badges

2. **new_badge_background.xml** - Badge styling
   - Green background (#4CAF50)
   - 4dp border radius
   - White "NEW" text, bold 9sp

### Feature Keys

Each badge uses a unique key for independent tracking:

| Feature Key | Description |
|---|---|
| `"remote"` | Remote streaming tab |
| `"watermark"` | Watermark settings in Quick Access |
| `"watermark_custom_text"` | Custom Text row inside Watermark settings |
| `"fadrec"` | FadRec mode in Home tab switcher |

---

## Current Implementations

### 1. Remote Tab Badge

**Feature Key:** `"remote"`

**Files:**
- `app/src/main/java/com/fadcam/MainActivity.java`
  - Line ~138-157: `updateFeatureBadgeVisibility()` method - Shows/hides the badge TextView

- `app/src/main/res/layout/activity_main.xml`
  - Lines 24-44: Badge TextView (`remote_nav_badge`)

- `app/src/main/java/com/fadcam/ui/RemoteFragment.java`
  - Line ~230: `onResume()` - Marks feature as seen when entering Remote tab

**Behavior:**
- Green "NEW" badge shows on Remote nav button
- Badge disappears when user enters Remote tab

**Removal Checklist:**
- [ ] Delete `remote_nav_badge` TextView from `activity_main.xml` (lines 24-44)
- [ ] Remove `updateFeatureBadgeVisibility()` method from `MainActivity.java`
- [ ] Remove `updateFeatureBadgeVisibility()` method call from `MainActivity.onCreate()`
- [ ] Remove `NewFeatureManager.markFeatureAsSeen()` call from `RemoteFragment.onResume()`

---

### 2. Watermark Quick Access Badge

**Feature Key:** `"watermark"`

**Files:**
- `app/src/main/res/layout/fragment_settings_home.xml`
  - Lines 205-265: Watermark row with NEW badge (`watermark_new_badge`)

- `app/src/main/java/com/fadcam/ui/SettingsHomeFragment.java`
  - Lines ~138-147: `manageBadgeVisibility()` method - Shows/hides watermark badge
  - Lines ~100-110: Watermark row click listener - Marks badge as seen when clicked

**Behavior:**
- Green "NEW" badge shows on Watermark row in Quick Access
- Badge disappears when user clicks the watermark row

**Removal Checklist:**
- [ ] Delete `watermark_new_badge` TextView from `fragment_settings_home.xml` (lines 205-265)
- [ ] Remove `manageBadgeVisibility()` method from `SettingsHomeFragment.java`
- [ ] Remove `NewFeatureManager.markFeatureAsSeen()` call from watermark row click listener
- [ ] Remove badge visibility management code from `SettingsHomeFragment.java`

---

### 3. Watermark Custom Text Badge

**Feature Key:** `"watermark_custom_text"`

**Files:**
- `app/src/main/res/layout/fragment_settings_watermark.xml`
  - Lines 195-265: Custom Text row with NEW badge (`badge_custom_text`)

- `app/src/main/java/com/fadcam/ui/WatermarkSettingsFragment.java`
  - Line ~63: Badge visibility initialization in `onViewCreated()`
  - Lines ~72-82: Custom Text row click listener - Marks badge as seen when clicked

**Behavior:**
- Green "NEW" badge shows on Custom Text row inside Watermark settings
- Badge disappears only when user clicks the Custom Text row (independent from main watermark badge)

**Removal Checklist:**
- [ ] Delete `badge_custom_text` TextView from `fragment_settings_watermark.xml` (lines 195-265)
- [ ] Remove badge visibility code from `WatermarkSettingsFragment.onViewCreated()`
- [ ] Remove `NewFeatureManager.markFeatureAsSeen()` call from Custom Text row click listener

---

### 4. FadRec Mode Badge

**Feature Key:** `"fadrec"`

**Files:**
- `app/src/main/res/layout/mode_switcher_segmented.xml`
  - Lines 14-18: FadRec segment with NEW badge (`badge_fadrec`)

- `app/src/main/java/com/fadcam/ui/components/ModeSwitcherComponent.java`
  - Lines ~76-87: Badge visibility initialization in `initialize()` method
  - Lines ~291-305: FadRec click handler - Marks badge as seen when FadRec is selected

**Behavior:**
- Green "NEW" badge shows on FadRec button in Home tab mode switcher
- Badge disappears when user clicks the FadRec mode

**Removal Checklist:**
- [ ] Delete `badge_fadrec` TextView from `mode_switcher_segmented.xml` (lines 14-18)
- [ ] Remove badge visibility code from `ModeSwitcherComponent.initialize()`
- [ ] Remove `NewFeatureManager.markFeatureAsSeen()` call from FadRec click handler in `handleModeClick()`

---

## Removal Guide (For Release)

When badge features are ready to be released without NEW badges:

1. **For each feature**, follow its "Removal Checklist" above
2. **Delete NewFeatureManager.java** (only if NO other features use badge system)
   - Location: `app/src/main/java/com/fadcam/ui/utils/NewFeatureManager.java`
3. **Delete new_badge_background.xml** drawable
   - Location: `app/src/main/res/drawable/new_badge_background.xml`
4. **Rebuild and test:**
   - `./gradlew assembleDefaultDebug installDefaultDebug`
   - Verify no visual glitches or missing UI elements

---

## SharedPreferences Storage

Badge states are stored as:
```
badge_{featureKey}_seen = true/false
```

Examples:
- `badge_remote_seen`
- `badge_watermark_seen`
- `badge_watermark_custom_text_seen`
- `badge_fadrec_seen`

---

## Related Files Summary

### Core Files
- `app/src/main/java/com/fadcam/ui/utils/NewFeatureManager.java` - Badge manager
- `app/src/main/res/drawable/new_badge_background.xml` - Badge styling

### Modified Files
1. `app/src/main/java/com/fadcam/MainActivity.java` - Remote badge
2. `app/src/main/res/layout/activity_main.xml` - Remote badge UI
3. `app/src/main/java/com/fadcam/ui/RemoteFragment.java` - Remote badge logic
4. `app/src/main/res/layout/fragment_settings_home.xml` - Watermark badge UI
5. `app/src/main/java/com/fadcam/ui/SettingsHomeFragment.java` - Watermark badge visibility
6. `app/src/main/res/layout/fragment_settings_watermark.xml` - Custom Text badge UI
7. `app/src/main/java/com/fadcam/ui/WatermarkSettingsFragment.java` - Custom Text badge logic
8. `app/src/main/res/layout/mode_switcher_segmented.xml` - FadRec badge UI
9. `app/src/main/java/com/fadcam/ui/components/ModeSwitcherComponent.java` - FadRec badge logic
