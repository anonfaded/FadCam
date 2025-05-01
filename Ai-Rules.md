# 📜 JAVA PROJECT RULES GUIDE

This document defines coding standards and operational guidelines for working on this Java Android project.  
All code contributions (manual or AI-generated) **must** strictly follow these rules.

---

## 1. 🔥 Code Quality & Structure

- **Follow Official Java and Android style guides**:  
  - Use Google's [Java Style Guide](https://google.github.io/styleguide/javaguide.html).
  - Follow Android's [official coding standards](https://developer.android.com/kotlin/style-guide) (adapted for Java).
- **Class Design:**
  - Single Responsibility Principle: **One class = one purpose.**
  - Keep classes **small, modular, and testable**.
- **Method Design:**
  - Method length should be **≤ 50 lines**.
  - A method should **do one thing only**.
  - **Name methods clearly** with action verbs (`calculateTotal()`, `fetchData()`, etc.).
- **Consistency is mandatory**:
  - Stick to the **same naming conventions** everywhere.
  - If a pattern or architectural decision is made (e.g., MVVM, Clean Architecture), **respect it across the project**.

---

## 2. 🛠️ Error Handling

- **NEVER** silently swallow exceptions.
- All `try-catch` blocks must:
  - Log the exception using project logging standards (see section 3).
  - Handle the error meaningfully if possible (e.g., fallback behavior, user-friendly messages).
- Throw **custom exceptions** where appropriate instead of generic ones.
- Catch **specific exceptions** instead of using a broad `Exception`.

Example:
```java
try {
    someOperation();
} catch (IOException e) {
    Log.e(TAG, "Failed to read file", e);
    showErrorMessageToUser();
}
```

---

## 3. 🪵 Logging and Debugging

- Use **structured logs** (`Log.d`, `Log.i`, `Log.e`) with:
  - **TAGs** specific to the class or feature.
  - **Clear, actionable messages**.
- **Always log exceptions with their stack trace**.
- Sensitive user data (passwords, API tokens) **MUST NEVER** be logged.

---

## 4. 🧹 Refactoring Rules

- **Refactor only the necessary parts** that are related to the change.
- **Do not mass-refactor** unrelated code unless a specific ticket/task exists for it.
- **When refactoring:**
  - Maintain behavior; **no breaking changes** unless approved.
  - Run all relevant checks after refactoring.
- **Prefer code improvement over rewrites**.

---

## 5. 📋 Code Duplication

- **Avoid duplication** of any kind:
  - Common logic must be moved to reusable methods/classes.
  - Use utility classes, base classes, and abstraction appropriately.
- **DRY Principle**: "Don't Repeat Yourself."

---

## 6. 🧠 Logic and Feature Implementation

- **Implement logic simply and clearly**:
  - No unnecessary complexity ("Clever code is bad code").
  - Favor readability over micro-optimizations.
- For features:
  - Always handle **all possible edge cases**.
  - Validate inputs at **entry points**.
  - Fail gracefully wherever possible.
- Always respect **asynchronous operations** (e.g., network calls, file I/O) and manage threading properly (`AsyncTask`, `ExecutorService`, or modern alternatives like Kotlin Coroutines if mixed projects).

---

## 7. 🧨 Destructive Changes Policy

- Any **destructive change** (breaking API, removing features, changing behavior) must:
  - Be flagged and **approved** before proceeding.
  - Provide **migration plans** if data formats, settings, or APIs are modified.
- AI tools MUST ASK/CONFIRM before:
  - Deleting any method/class/resource.
  - Changing any data model/structure (e.g., DB schema).
  - Overwriting any important project-wide logic.

---

## 8. 🏛️ Project Organization

- **Follow the existing project structure** for placing classes, activities, fragments, resources.
- **Modularize** features if applicable:
  - Different features must be organized into packages/modules.
- Follow the **naming conventions**:
  - Packages: lowercase (`com.example.projectname`).
  - Classes: PascalCase (`MainActivity`, `UserProfileManager`).
  - Variables and methods: camelCase (`userName`, `getUserInfo()`).

---

## 9. 🔒 Security and Privacy

- Handle sensitive data securely.
- Always validate external inputs (e.g., from network, user input).
- Avoid exposing internals unnecessarily (prefer `private`/`protected` access).

##  Assume Hardware/OEM Differences:

-  Features heavily reliant on hardware components (Camera2 API, MediaCodec, Sensors, specific file system behaviors) must be designed defensively.

-  Anticipate variations across manufacturers (e.g., Samsung, Google Pixel, Xiaomi) and Android versions. Avoid assuming uniform behavior for hardware-accelerated tasks or low-level APIs.

-  Implement robust fallbacks or alternative strategies where possible if a primary hardware-accelerated approach might fail on certain devices.

##  Prioritize Compatibility for Processing Tasks:

-  When performing post-processing or re-encoding tasks (e.g., watermarking, format conversion) using libraries like FFmpeg that interact with hardware encoders (MediaCodec):

-  Prioritize universally compatible output settings (e.g., H.264 codec, common pixel formats like NV12) over trying to exactly match the user's initial recording settings (like HEVC), especially if the input involves filtering or format changes. This mitigates risks from OEM MediaCodec implementation quirks.

-  The initial recording can respect user settings (e.g., record in HEVC), but subsequent processing that re-encodes should default to safer options if hardware acceleration is used. -codec copy is safe as it doesn't re-encode.

# 🧵 FINAL REMARKS

- All code must be **clean, readable, extensible, and robust**.
- Treat the codebase with **professional discipline**: imagine it will be maintained for 10+ years.
- Always **prioritize stability and clarity over speed**.

---
