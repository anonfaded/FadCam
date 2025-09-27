<!-- 
FORMAT GUIDELINES:
- Use # for section headers (# New Features, # Fixes)
- Use bullet points with - for individual changes
- Comments like this won't be displayed in the app
-->

<!-- This is a comment -->  

# ⚠️ Note  
- ⚠️ Major migration: FadCam is now powered by a high-performance **OpenGL pipeline** for recording and watermarking. This replaces old post-processing, making saving lightning fast with **real-time dashcam-style watermarking**.  
- 🚀 Total: 20+ new features, multiple fixes, and full UI refactor.  

---

# ✨ New Features  
- **Video Player**
  - Open with external apps  
  - Background playback + auto-stop timer  
  - Position saving (YouTube-style)  
  - Gesture overlays (volume, brightness, seek with dynamic icons)  
  - Seek customization  
  - Finer speed steps (0.25x, 0.5–0.9x, 2x long-press customizable)  
  - Scrubbing frames while seeking  
  - Waveform visualization  
  - Keep screen awake option  
  - Remove dim overlay  
  - Adjustable controls hide delay  

- **Camera**
  - Runtime controls: exposure, AE lock, AF toggle, tap-to-focus with feedback, zoom  
  - Fixed zoom level option (e.g., 1.0x default)  

- **Records Tab**
  - Hide thumbnails toggle  
  - Scroll-to-top & scroll-to-bottom buttons  
  - Improved caching and sync  
  - Optimized responsiveness  

- **Home**
  - Preview toggle in sidebar  
  - Info widget showing device orientation  

- **Stats & Info**
  - Navigation from Stats → Records  
  - Expanded metadata in Video Info: location, FPS, bitrate  

- **App Cloaking**
  - Hide in recent apps  
  - Black icon & custom shortcut icons  
  - Clock widget integration  

- **File Management**
  - Copy/move option when saving to gallery  
  - Background file operations (non-blocking)  
  - Immediate delete option in Trash  

- **Settings & UI**
  - Import/export app preferences  
  - Full UI refactor: categorized sections, previews, clearer descriptions  
  - Privacy Policy checkbox in onboarding  

- **Language**
  - Added German  

- **Other**
  - Realtime watermarking during recording  
  - Noise suppression for cleaner audio  
  - New tab: **Faditor Mini** (coming soon)  

---

# 🐞 Bug Fixes & Improvements  
- Crash prevented from rapid start clicks  
- Video split issue fixed  
- Preview area long-press disabling fixed  
- Resolution handling fixed: valid 4K and 8K options  
- Shortcuts now persist after app icon change  
- Wrong duration bug fixed (short videos showing as hours)  
- Fixed lens selection (wide lens was defaulting to main)  
- Fixed unplayable videos via repair option  
- Android 15 edge-to-edge display issues resolved  
- Seamless camera interruptions: recording pauses if another app opens and resumes after  
- Records tab loading optimized (faster video list)  

---

# 🔧 Chore & Permissions  
- Rebuilt FFmpeg kit for 16KB alignment  
- Removed `query-all-packages` permission  


