# UI/UX Preferences

- Prefers modern, iOS/macOS-style UI/UX with clean layouts. Confidence: 0.85
- Wants responsive, scalable card designs where content scales and stays within bleed areas, credit-card-style, with no excessive padding or wasted space. Confidence: 0.9
- Avoids truncation/ellipsis; prefers properly fixed layouts and tooltips over inline clutter. Confidence: 0.85
- Prefers professional UI copy (e.g., "feature" rather than "button"). Confidence: 0.8
- Avoids duplicate UX elements (e.g., duplicate sign-out buttons, duplicate "Linked" labels); consolidates them. Confidence: 0.75
- Prefers simple, non-alarming user-facing previews (e.g., preferences import): show a clean summary of what will be imported ("N settings ready to import") with key/type/value rows — no warning counts, no per-entry status colors, no "Import Anyway" gate; technical validation and type-repair happen silently behind the scenes because warnings are useless to users and only confuse them. Confidence: 0.9
- Prefers rounded-corner, material-card-shaped UI elements that match existing components (e.g., the bottom-sheet picker header/banner style), not plain rectangular banners. Confidence: 0.85
- Wants color-coded syntax highlighting in JSON/preview displays "for better looks" — keys, string values, numbers, booleans, and type chips each in a distinct color (classic JSON syntax coloring), not plain monochrome rows. Confidence: 0.9
- On dark backgrounds, prefers high-contrast dark-theme JSON syntax palettes (One Dark / Material Ocean style: soft blue keys, soft green strings, soft coral numbers, cyan booleans, light blue-gray for others) — explicitly dislikes purple, danger red, and warning yellow on dark UIs; wants "nicer" syntax-highlighting colors that don't feel alarming. Confidence: 0.9
- Scrolling inside a bottom sheet's scrollable content must not dismiss the sheet (an accidental scroll fling closing the dialog is a bug); the sheet's drag-to-dismiss should be disabled while content is scrolled and re-enabled only at the top. Confidence: 0.85
- A sheet/dialog that triggers an action must dismiss itself *before* the action runs — especially when the action recreates the activity — so it never lingers on screen after completion (e.g., the preferences preview sheet staying open after import is a bug). Confidence: 0.75
- In a single-mode variant (Lite = FadCam only), the whole multi-mode selector UI must go — remove the entire mode pill bar rather than just the unsupported segment, since a switcher with one option is meaningless. Confidence: 0.8
- When a section is excluded from a variant, the entire visible group must disappear — its title/header text and any adjacent buttons (info/edit icons) along with the content container, not just the main list — the user spotted a lingering "Mini Apps" title/header in the Lite sidebar after only the container row was hidden and reported it as an unfinished cut. Confidence: 0.85
