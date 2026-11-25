# Open source licenses

A screen listing every third-party library in the build, grouped by license, with
the license text available inline. Reached from **Settings → About → Third-party
licenses**.

## What it does

Each license group can be expanded to read the full license text, which is bundled
in the APK under `assets/licenses/`. Nothing is downloaded at runtime.

Most of the dependency tree is Apache-2.0 and the BSD licenses, both of which ask
that their notice accompany the distributed build. Shipping the text with the app
is what satisfies that.

## How it works

```
dependencies
     │
     ▼
license<Variant>Report           (com.jaredsburrows.license plugin)
     │  build/reports/licenses/license<Variant>Report.json
     ▼
prepare<Variant>LicenseAsset     (copies the JSON)
     │  build/generated/licenses/<variant>/open_source_licenses.json
     ▼
LicenseCatalog                   (parse, normalize, group)
     ▼
OpenSourceLicensesFragment       (render)
```

The plugin runs at build time only; there is no runtime dependency and nothing is
fetched at runtime.

### One report per variant

The report is generated for each variant rather than once for the project, so each
APK lists exactly the libraries it contains. Lite does not credit the Full-only
libraries, and a disguised Pro build does not either. Currently:

| Variant | Libraries listed |
|---|---|
| `defaultDebug` | 142 |
| `liteDebug` | 131 |

The difference is the Full-only stack (motion detection, forensics, FadRec). Any
new flavor is picked up automatically because the source directory is registered
through `androidComponents.onVariants`.

Generated files go to `build/generated/licenses/<variant>/`, never
`src/main/assets/`, so a build cannot modify tracked files.

## License name normalization

POM authors disagree. The real dependency graph produced **twelve distinct
strings describing only five licenses**:

| Raw string from POMs | Count |
|---|---|
| `The Apache Software License, Version 2.0` | 115 |
| `Apache-2.0` | 5 |
| `The Apache License, Version 2.0` | 4 |
| `Simplified BSD License` | 4 |
| `Apache 2.0` | 3 |
| `Apache License, Version 2.0` | 3 |
| `Play Core Software Development Kit Terms of Service` | 3 |
| `The 3-Clause BSD License` | 2 |
| `Android Software Development Kit License` | 2 |
| `The BSD 3-Clause License` | 1 |

Rendered raw, that is five separate Apache sections and three BSD ones.
`LicenseCatalog.normalize` collapses them to `Apache License 2.0`, `BSD 3-Clause`,
and so on. The canonical list lives in `ORDER`, which also fixes the display
sequence so the dominant license appears first and `Unknown` sorts last.

## Bundled license texts

`LicenseCatalog.assetFor` maps a normalized name to a bundled file. A license with
no text (Play Core terms, Android SDK License) returns `null` and no expandable
body is shown, because those terms are not redistributable.

Current texts:

| File | Bytes |
|---|---|
| `assets/licenses/apache-2.0.txt` | 11 358 |
| `assets/licenses/gpl-3.0.txt` | 35 149 |
| `assets/licenses/bsd-3-clause.txt` | 1 460 |
| `assets/licenses/bsd-2-clause.txt` | 1 267 |

Use canonical plain text, not a fetched web page. The SPDX license list
(`https://raw.githubusercontent.com/spdx/license-list-data/main/text/<SPDX-ID>.txt`)
is the reliable source; `opensource.org/license/...` returns HTML.

## Tests

- `LicenseCatalogTest` — normalization against the exact strings real POMs emit,
  blank/null handling, that every redistributable license resolves to a bundled
  asset, that the generated asset parses and groups, and that the bundled texts
  are plain text rather than HTML.
- `OpenSourceLicensesFragmentTest` — inflates the screen, asserts the first group
  is Apache-2.0, that a group expands and reveals the bundled text, and that every
  group carries a library count.

## Costs

| | |
|---|---|
| Runtime dependency | none |
| Asset size (uncompressed, default flavor) | ~66 KB JSON + ~49 KB license text |
| Added to APK | ~15–25 KB compressed |
| Build cost | resolves the same configurations the app already resolves |

## Not covered

The list covers Gradle dependencies only. Native libraries shipped outside Gradle,
fonts, icons, images and any vendored source are not included and would need manual
entries; the plugin has an API for extra libraries if that becomes necessary.
