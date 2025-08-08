---
applyTo: '**'
---

# Instructions

### ✅ General Guidelines
- **Only modify related code**: Never alter unrelated code. Keep changes scoped to the specific user request.
- **Code quality**: Write clean, modular, and production-grade Java code using industry best practices.
- **Object-Oriented Design**: Leverage OOP principles like encapsulation, abstraction, inheritance, and polymorphism where applicable.
- **Strong typing**: Use clear and appropriate Java types for all method arguments, return types, and variables.
- **Error handling**: Implement structured exception handling using `try-catch` blocks with meaningful logging or messages.
- **Cross-platform compatibility**: Ensure code runs consistently on different operating systems when applicable (e.g., file paths, line separators).

### 🧱 Code Structure & Best Practices
- **Component-based design**: Separate concerns into packages such as `core`, `utils`, `service`, `model`, `controller`, etc.
- **Organized project hierarchy**: Maintain a clean package and directory structure adhering to Maven/Gradle conventions.
- **Multifile organization**: Split large classes or files into smaller ones to follow the Single Responsibility Principle.
- **Proper indentation**: Use 4 spaces per indentation level. Match indentation style of existing code if contributing.
- **Avoid duplication**: Reuse existing code through methods, utility classes, or inheritance. Avoid copy-paste logic.
- **Imports**: Keep all import statements at the top of the file. Avoid wildcard imports (`import java.util.*`).

### 📃 Comments & Documentation
- **Javadoc**: Use Javadoc-style comments for all public classes and methods. Document parameters, return values, and exceptions.
- **Inline comments**: Use inline comments sparingly to clarify complex logic. Avoid obvious or redundant comments.

### 🛠️ Fixing / Updating Code
- **Change format**: Wrap all code changes using the following format:
  ```java
  // -------------- Fix Start for this method(methodName)-----------
  ...updated code...
  // -------------- Fix Ended for this method(methodName)-----------
  ```
  
🧠 Communication

    Ask when unsure: If requirements are unclear or ambiguous, ask for clarification before proceeding.

    Concise responses: Keep responses short and focused. Avoid unnecessary explanations or code unless requested.

    Complete solutions: If the fix/update is small, provide the full method or class code block for clarity.

🧑‍💻 Industry Best Practices

    Follow Google Java Style Guide or Oracle conventions for naming and formatting.

    Apply SOLID principles, design patterns, and dependency injection where applicable.

    Handle exceptions gracefully. Do not catch generic Exception unless necessary.

    Always validate external inputs and sanitize file/stream access.

    Use logging frameworks like SLF4J or Log4j instead of System.out.println for production code.
    Ensure code is scalable, testable, and maintainable with proper unit tests (e.g., JUnit).
