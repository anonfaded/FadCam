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
- Organize into logical packages (`core`, `utils`, `service`, `model`, `controller`, `viewmodel`, `repository`).  
- Follow Gradle/Maven hierarchy conventions.  
- Large classes → split into smaller ones (Single Responsibility).  
- Indentation = 4 spaces, follow existing style.  
- Avoid duplicate logic; reuse via utility classes/methods.  
- Keep imports at the top, no wildcards (`import java.util.*`).

### 🏗️ Architecture: MVVM (Model-View-ViewModel)
- **Model**: Data classes, repositories, data sources. Pure data, no UI logic.  
- **View**: Activities, Fragments, layouts. Only UI rendering. NO business logic.  
- **ViewModel**: Holds UI state, processes user input, communicates with repositories. Survives configuration changes.  
- Use `AndroidX.lifecycle.ViewModel` for ViewModels (survives orientation changes).  
- Use `LiveData<T>` or `StateFlow<T>` for reactive state management.  
- Views observe ViewModels; never access repositories directly from UI.  
- One ViewModel per screen/fragment (or group of related screens).  
- ViewModels are disposable: clear references in `onCleared()`.

### 🔗 Dependency Injection
- Inject dependencies via constructor or setter, avoid `new` inside classes.  
- Use repository pattern for data access (abstract behind interfaces).  
- Repositories inject data sources (Room, Network, SharedPrefs).  
- Avoid tight coupling; depend on abstractions, not concrete classes.  
- Consider using Dagger/Hilt for complex DI (optional but recommended for scale).

### 🎯 Object-Oriented Programming (OOP) Principles
- **Encapsulation**: Private fields, public getters/setters, package-private helpers.  
- **Inheritance**: Use base classes/interfaces for common behavior. Prefer composition over inheritance.  
- **Polymorphism**: Use interfaces to define contracts; implement for different behaviors.  
- **Abstraction**: Hide implementation details; expose only what's needed (public API).  
- **Single Responsibility**: Each class has ONE reason to change.  
- **Open/Closed**: Classes open for extension, closed for modification (use abstract classes/interfaces).  
- **Liskov Substitution**: Subclasses must be usable anywhere parent is used.  
- **Interface Segregation**: Many small, focused interfaces > few large ones.  
- **Dependency Inversion**: Depend on abstractions, not concrete implementations.

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
- **Design Patterns**: Builder (complex objects), Observer (state changes), Adapter (multiple sources), Factory (object creation).  
- Don't swallow generic `Exception` unless there's a strong reason.  
- Validate all external inputs.  
- Use logging frameworks (SLF4J, Log4j), not `System.out.println`.  
- Write code that's testable and maintainable; add JUnit tests where possible.  
- After each change, build and install to confirm behavior.  
- **Thread Safety**: Use `synchronized`, `volatile`, or concurrent collections for multi-threaded access.  
- **Memory Management**: Avoid memory leaks (unregister listeners, clear references in `onDestroy()`).  
- **Null Safety**: Use `@Nullable` and `@NonNull` annotations; check for null before use.  
- **Immutability**: Prefer immutable objects for data classes; use `final` fields when possible.

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
- **MVVM Architecture**: Model (data) → ViewModel (logic) → View (UI). Never mix concerns.  
- **OOP Principles**: Encapsulation, inheritance, polymorphism, abstraction. Use SOLID.  
- **DI & Patterns**: Inject dependencies; use design patterns for maintainability.  
- Write modular, typed, documented Java with clear structure.  
- Keep UI consistent: shared styles, settings rows, bottom sheets.  
- **Icons**: always use Material Icons font by default. Only switch if told.  
- Input UI → always use `InputActionBottomSheetFragment`.  
- Wrap fixes with start/end markers.  
- Confirm builds after changes.  
- When writing code, always include error handling, logging, and input validation as appropriate.
- When writing documentation, ensure clarity, conciseness, and completeness.
- When i cancel or skip a command, always ask for feedback using askUser MCP server tool.

**Run this command only after updates (build + install in one), and also never use '2>&1 | tail -30' type of command pipes so i can see the output in realtime:**  
```
 ./gradlew assembleDefaultDebug installDefaultDebug
```
