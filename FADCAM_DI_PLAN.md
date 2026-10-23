# FadCam — Dependency Injection Migration Plan (HEADS-UP / DEFERRED)

**Status:** Summary only. Research + codebase audit comes LATER, before any adoption.
**Constraint:** Open-source options only (no proprietary). Project is pure Java today (Room via `annotationProcessor`), single `:app` module.

## Why (driver)
- Shared-code seams are growing (Lite/Full split): `FeatureRegistry`, `FullFeatures`, `BatchFfmpegOps`, services, repositories. A framework formalizes wiring, testability, and per-build scoping.

## Candidate options (open source)
- **Dagger 2** — Java-first, compile-time, works with existing `annotationProcessor` setup. No new language.
- **Hilt** (Google, built on Dagger) — Android standard; needs Kotlin plugin or Java annotationProcessor support check.
- **Koin** — Kotlin-DSL only (requires adding Kotlin to the build).
- **Toothpick** — Android/Java DI, lighter than Dagger.
- **Manual DI** (constructor injection + composition root) — zero deps, already partially in place via interfaces.

## Research TODO (later session)
- [ ] Audit current manual wiring: FeatureRegistry seams, services, SharedPreferencesManager singleton usage, Room repos
- [ ] Compare Dagger 2 vs Hilt vs Toothpick for THIS codebase (Java, minSdk 24, AGP 8.13.1, R8 + full variant matrix incl. Lite flavors)
- [ ] Decide incremental retrofit path: new seams first (services/batch ops/repos), no big-bang rewrite
- [ ] Verify impact on: build time, APK size (Dagger/Hilt add little; Koin adds runtime lib), R8/proguard rules, ALL 10 variants incl. Lite
- [ ] Keep Lite philosophy: DI framework must not add meaningful size to Lite (Hilt/Dagger ≈ few hundred KB, codegen only in Full paths where possible)

## Guardrails
- No framework adoption during active Lite size-refactor phases
- Full variants must build byte-equivalent behavior after any wiring change
- Prefer compile-time (Dagger/Hilt) over runtime reflection for release-build safety
