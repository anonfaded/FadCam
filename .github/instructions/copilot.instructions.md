---
applyTo: '**'
---

# Instructions

### ✅ General
- Only touch code that’s directly related to the request. Don’t modify unrelated parts.  
- Code must be clean, modular, and production-ready.  
- Use OOP (encapsulation, inheritance, polymorphism, abstraction) where it fits.  
- Always prefer strong typing for clarity.  
- Handle errors with proper `try-catch` and meaningful logs/messages.  
- Code should behave consistently across platforms (file paths, separators, etc.).

### 🧱 Code Structure
- Organize into logical packages (`core`, `utils`, `service`, `model`, `controller`).  
- Follow Gradle/Maven hierarchy conventions.  
- Large classes → split into smaller ones (Single Responsibility).  
- Indentation = 4 spaces, follow existing style.  
- Avoid duplicate logic; reuse via utility classes/methods.  
- Keep imports at the top, no wildcards (`import java.util.*`).

### 📃 Documentation
- Public classes/methods must have Javadoc with parameters, return values, and exceptions.  
- Inline comments only for complex areas; don’t restate the obvious.

### 🧠 Communication
- Ask for clarification if requirements aren’t clear.  
- Responses should stay short and focused.  
- Provide full method/class if the fix is small enough to fit cleanly.

### 🧑‍💻 Best Practices
- Follow Google/Oracle Java style guides.  
- Apply SOLID principles, design patterns, and dependency injection when useful.  
- Don’t swallow generic `Exception` unless there’s a strong reason.  
- Validate all external inputs.  
- Use logging frameworks (SLF4J, Log4j), not `System.out.println`.  
- Write code that’s testable and maintainable; add JUnit tests where possible.  
- After each change, build and install to confirm behavior.

### 🎯 UI Icons
- **Default rule**: always use the local Material Icons font (`@font/materialicons`).  
- This is the baseline. Only use other/custom icon sources if explicitly asked.  
- Recommended usage:  
  - In layouts → `TextView` with `android:fontFamily="@font/materialicons"`, ligature text like `"arrow_upward"`.  
  - In code → cache the typeface once with `ResourcesCompat.getFont(context, R.font.materialicons)`.  
- Use ligature icons (`TextView`) for pickers/sheets. ImageView only as fallback.  
- Do not add new vector/SVG drawables unless required for branding.

### 🎨 FadCam UI Styling
- Shared tappable rows use `@drawable/settings_home_row_bg` with 4dp inset ripple.  
- Rows inside group cards: `@style/SettingsGroupRow` (centered, gutters: 14dp start, 12dp end).  
- Leading icon: 24dp square, marginEnd 16dp.  
- Title: 15sp bold, heading color. Subtitle/value: 12sp gray.  
- Trailing arrow/icon: 14dp gray, with marginEnd 12dp after value.  
- Group cards: use `@drawable/settings_group_card_bg`, with 4dp vertical padding.  
- Bottom sheets mirror the same spacing/row style.  
- Strings must always come from `@string/...`.

### ⌨️ Input Bottom Sheet
- Use `InputActionBottomSheetFragment` for rename, confirmations, and single-line inputs.  
- Factory: `newInput(...)` with title, value, hint, and action details.  
- Register callbacks with `setCallbacks(...)`, handle input via `onInputConfirmed`.  
- Fragment dismisses immediately for smooth UX; run rename/ops async.  
- Input must be validated/sanitized by the caller (illegal filename chars, etc.).  
- Component also supports `newReset(...)` and `newPreview(...)`, but prefer unified `newInput`.

---

## 🚀 Summary
- Write modular, typed, documented Java with clear structure and OOP principles.  
- Keep UI consistent: shared styles, settings rows, bottom sheets.  
- **Icons**: always use Material Icons font by default. Only switch if told.  
- Input UI → always use `InputActionBottomSheetFragment`.  
- Wrap fixes with start/end markers.  
- Confirm builds after changes.  

**Run this command only after updates (build + install in one):**  
```
.\gradlew.bat compileDebugJavaWithJavac installDebug
```
