import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.licenseReport)
}

android {
    namespace = "com.fadcam"
    compileSdk = 36

    /**
     * Version suffix for the Full line's debug/beta channel. Lite does NOT inherit it:
     * Lite's beta number lives inside liteVersionName (e.g. "1.0.0-beta1") because Lite's
     * releases themselves are the betas. Appending this to a Lite debug build produced
     * "1.0.0-beta1-beta10.7.2", which the in-app updater cannot parse — it fell through
     * to the plain-version pattern and read the app as stable 1.0.0, so beta updates
     * were never offered. Stripped per-variant in androidComponents below.
     */
    val DEBUG_VERSION_SUFFIX = "-beta10.7.2"

    val isBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    // arm64-only ABI policy applies to Full Pro-tier builds only ('pro'/'proPlus' task names).
    // The LITE line ships UNIVERSAL (all ABIs + universal APK) so every device can install —
    // Lite has ~no native code, so universal costs ~0 MB extra.
    val isProBuild = gradle.startParameter.taskNames.any {
        val t = it.lowercase()
        t.contains("pro") && !t.contains("lite")
    }

    splits {
        abi {
            // For pro builds: enable splits but only arm64-v8a (no universal)
            // For main builds: arm64-v8a + armeabi-v7a with universal APK
            isEnable = !isBundle
            reset()
            if (isProBuild) {
                include("arm64-v8a")
                isUniversalApk = false
            } else {
                include("armeabi-v7a", "arm64-v8a")
                isUniversalApk = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.fadcam"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 55
        versionName = "4.0.0"
        buildConfigField("boolean", "LITE_EDITION", "false")
        // Free-only weekly Pro reminder. PRO/PRO_PLUS variants compile the flag as false and
        // disable the reminder receivers, so ownership builds never run the feature paths.
        buildConfigField("boolean", "ENABLE_PRO_REMINDER", "true")
        // Which tier this exact APK IS (not what it can upgrade to). Drives the Pro screen:
        // FREE shows the upgrade pitch, PRO / PRO_PLUS show the owner view of the same table.
        // Overridden per flavor/build type below — keep the two in sync with the variant filter.
        buildConfigField("String", "TIER", "\"FREE\"")
        buildConfigField("String", "UPDATE_ORG", "\"anonfaded\"")
        buildConfigField("String", "UPDATE_REPO", "\"FadCam\"")
        buildConfigField("String", "UPDATE_PRO_REPO", "\"FadCamPro\"")
        // Launcher label per variant: defaultConfig is the base, flavors/build types override,
        // onVariants() below sets the per-variant debug labels (Full beta vs Lite beta).
        manifestPlaceholders["appLabel"] = "FadCam"
        manifestPlaceholders["proReminderEnabled"] = "true"
        vectorDrawables.useSupportLibrary = true
        
        // Fix 16KB native library alignment for Android 15
        // Generate full native debug symbols so they can be uploaded to Play Console
        ndk {
            debugSymbolLevel = "FULL"
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("local.properties")
            require(propsFile.exists()) {
                "local.properties is missing. It must define KEYSTORE_FILE, " +
                    "KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD."
            }
            propsFile.inputStream().use { props.load(it) }
            val keystoreFile = props.getProperty("KEYSTORE_FILE", "")
            require(keystoreFile.isNotEmpty() && file(keystoreFile).exists()) {
                "KEYSTORE_FILE in local.properties is missing or points at a file that " +
                    "does not exist. Release and debug builds are signed with this key."
            }
            storeFile = file(keystoreFile)
            storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = props.getProperty("KEY_ALIAS", "")
            keyPassword = props.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".beta"
            isDebuggable = true
            versionNameSuffix = DEBUG_VERSION_SUFFIX // Increment the beta version suffix for each release. Use `beta1` for the first beta release, then `beta2`, etc.
            resValue("string", "app_name", "FadCam Beta")
            // Debug APKs are signed with the release key too, so a beta install can be
            // updated in place by a release build. No fallback: see signingConfigs.
            signingConfig = signingConfigs.getByName("release")
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        
        create("pro") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".pro"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            versionNameSuffix = "-Pro"
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
        }
        
        create("proPlus") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".proplus"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            versionNameSuffix = "-Pro+"
            buildConfigField("String", "TIER", "\"PRO_PLUS\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            // Custom app name via gradle property
            val customAppName = project.findProperty("customAppName")?.toString() ?: "FadCam Pro+"
            resValue("string", "app_name", customAppName)
            manifestPlaceholders["appLabel"] = customAppName
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Product structure (ONE flavor dimension "pro"; single shared codebase):
    //
    //   FULL line (main FadCam app):
    //     default         → com.fadcam              Free (debug/release; proPlus build type → com.fadcam.proplus)
    //     notesPro/calcPro/weatherPro → com.fadcam.notes/.calc/.weather
    //                       discreet whitelabel disguises (release only)
    //
    //   LITE line (same main code — Lite reskin/UI + size work lands in src/lite* later):
    //     lite            → com.fadcam.lite          FadCam Lite Free (debug gets .beta → com.fadcam.lite.beta)
    //     liteNotes/liteCalc/liteWeather → com.fadcam.lite.notes/.lite.calc/.lite.weather
    //                       Lite Pro discreet disguises: SAME launcher icon + app name as the
    //                       Full disguises (icons reused from src/notesPro|calcPro|weatherPro/res)
    //
    //   The 'pro' / 'proPlus' BUILD TYPES belong to the Full line only — Lite tiers are their
    //   own flavors. The variant filter below decides which (flavor × build type) combos exist.
    // ──────────────────────────────────────────────────────────────────────────
    flavorDimensions += "pro"

    // ── Lite versioning (single place to edit) ──────────────────────────
    // Lite Free only ever builds the 'lite' flavor; Lite Pro builds the three
    // disguise flavors (liteNotes/liteCalc/liteWeather) which share one track.
    //
    // Update-check repo mapping (by design — mirrors Full):
    //   Full  -> UPDATE_REPO=FadCam (stable+beta), UPDATE_PRO_REPO=FadCamPro (pro)
    //   Lite Free -> UPDATE_REPO=FadCam-Lite (stable+beta), UPDATE_PRO_REPO=FadCam-LitePro (pro)
    //   Lite Pro  -> UPDATE_REPO=FadCam-LitePro, UPDATE_PRO_REPO=FadCam-LitePro (own repo)
    // Each build reads ITS line's repos; the home sidebar renders a card per
    // available update (Lite stable / Lite beta / Lite Pro from the respective repo).
    // Current values: Lite free and Lite Pro both ship as v1.0.0. The version is
    // intentionally independent from the Full line (4.x) — each app has its own
    // applicationId, update repo and tag prefix (`lite-v*` for Lite tags).
    val liteVersionCode = 1
    val liteVersionName = "1.0.0-beta1"
    val liteRepo = "FadCam-Lite"          // Lite (primary/stable) update feed
    val liteProVersionCode = 2
    val liteProVersionName = "1.0.0"
    val liteProRepo = "FadCam-LitePro"    // Lite Pro (secondary) update feed

    // ── Release-time workflow (simple, per-line) ────────────────────────
    // Lite line:  bump `liteVersionCode` + `liteVersionName` (and `litePro*`
    //             for the Lite Pro disguises) right above. One edit per line.
    // Full line:  bump `defaultConfig.versionCode` + `versionName` below.
    // Debug builds share the "-beta10.6" suffix (build-type level, can't differ
    // per flavor); release APKs carry the exact per-line version above.

    productFlavors {
        create("notesPro") {
            buildConfigField("String", "TIER", "\"PRO\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            dimension = "pro"
            applicationIdSuffix = ".notes"
            resValue("string", "app_name", "Notes")
            manifestPlaceholders["appLabel"] = "Notes"
        }
        create("calcPro") {
            buildConfigField("String", "TIER", "\"PRO\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            dimension = "pro"
            applicationIdSuffix = ".calc"
            resValue("string", "app_name", "Calculator")
            manifestPlaceholders["appLabel"] = "Calculator"
        }
        create("weatherPro") {
            buildConfigField("String", "TIER", "\"PRO\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            dimension = "pro"
            applicationIdSuffix = ".weather"
            resValue("string", "app_name", "Weather")
            manifestPlaceholders["appLabel"] = "Weather"
        }
        // Lite line: Free = debug + release; Lite Pro disguises = release only.
        // Lite tiers are flavors of their own — Full's 'pro'/'proPlus' build types
        // stay on the default flavor and do not apply to the Lite line.
        create("lite") {
            dimension = "pro"
            versionCode = liteVersionCode
            versionName = liteVersionName
            buildConfigField("String", "UPDATE_REPO", "\"$liteRepo\"")
            buildConfigField("String", "UPDATE_PRO_REPO", "\"$liteProRepo\"")
            applicationIdSuffix = ".lite"
            resValue("string", "app_name", "FadCam Lite")
            manifestPlaceholders["appLabel"] = "FadCam Lite"
            buildConfigField("boolean", "LITE_EDITION", "true")
        }
        // Lite Pro discreet disguises (same icon/app name as the Full disguises, see sourceSets)
        create("liteNotes") {
            buildConfigField("String", "TIER", "\"PRO\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            dimension = "pro"
            versionCode = liteProVersionCode
            versionName = liteProVersionName
            buildConfigField("String", "UPDATE_REPO", "\"$liteProRepo\"")
            buildConfigField("String", "UPDATE_PRO_REPO", "\"$liteProRepo\"")
            applicationIdSuffix = ".lite.notes"
            resValue("string", "app_name", "Notes")
            manifestPlaceholders["appLabel"] = "Notes"
            buildConfigField("boolean", "LITE_EDITION", "true")
        }
        create("liteCalc") {
            buildConfigField("String", "TIER", "\"PRO\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            dimension = "pro"
            versionCode = liteProVersionCode
            versionName = liteProVersionName
            buildConfigField("String", "UPDATE_REPO", "\"$liteProRepo\"")
            buildConfigField("String", "UPDATE_PRO_REPO", "\"$liteProRepo\"")
            applicationIdSuffix = ".lite.calc"
            resValue("string", "app_name", "Calculator")
            manifestPlaceholders["appLabel"] = "Calculator"
            buildConfigField("boolean", "LITE_EDITION", "true")
        }
        create("liteWeather") {
            buildConfigField("String", "TIER", "\"PRO\"")
            buildConfigField("boolean", "ENABLE_PRO_REMINDER", "false")
            manifestPlaceholders["proReminderEnabled"] = "false"
            dimension = "pro"
            versionCode = liteProVersionCode
            versionName = liteProVersionName
            buildConfigField("String", "UPDATE_REPO", "\"$liteProRepo\"")
            buildConfigField("String", "UPDATE_PRO_REPO", "\"$liteProRepo\"")
            applicationIdSuffix = ".lite.weather"
            resValue("string", "app_name", "Weather")
            manifestPlaceholders["appLabel"] = "Weather"
            buildConfigField("boolean", "LITE_EDITION", "true")
        }
        create("default") {
            dimension = "pro"
            // Default for proPlus builds
        }
    }

// ./gradlew assembleNotesProRelease - Notes Pro variant
// ./gradlew assembleCalcProRelease - Calculator Pro variant
// ./gradlew assembleWeatherProRelease - Weather Pro variant
// ./gradlew assembleDefaultProPlusRelease -PcustomAppName="Custom Name" - Pro+ custom build (standalone)
// ./gradlew installLiteDebug - FadCam Lite Free (debug beta, com.fadcam.lite.beta)
// ./gradlew assembleLiteRelease - FadCam Lite Free (com.fadcam.lite)
// ./gradlew assembleLiteNotesRelease - Lite Pro disguise: Notes (com.fadcam.lite.notes)
// ./gradlew assembleLiteCalcRelease - Lite Pro disguise: Calculator (com.fadcam.lite.calc)
// ./gradlew assembleLiteWeatherRelease - Lite Pro disguise: Weather (com.fadcam.lite.weather)

    // Variant filter: only build specific variants (modern API — the old
    // variantFilter{} is deprecated since AGP 8.x).
    androidComponents {
        beforeVariants { variant ->
            val isPreBuiltFlavor = variant.name.contains("notesPro") || variant.name.contains("calcPro") || variant.name.contains("weatherPro")
            val isDefaultFlavor = variant.name.contains("default")
            val isLiteFlavor = variant.name.startsWith("lite")

            if (isPreBuiltFlavor) {
                // Pre-built flavors: only 'release' build type
                if (!variant.name.endsWith("Release")) {
                    variant.enable = false
                }
            } else if (isDefaultFlavor) {
                // Default flavor: allow 'debug', 'release', and 'proPlus' build types
                if (variant.name.endsWith("Pro") && !variant.name.endsWith("ProPlus")) {
                    variant.enable = false
                }
            } else if (isLiteFlavor) {
                // Lite line: Free = debug + release; Lite Pro disguises = release only
                val allowed = setOf("liteDebug", "liteRelease", "liteNotesRelease", "liteCalcRelease", "liteWeatherRelease")
                if (variant.name !in allowed) {
                    variant.enable = false
                }
            }
        }

        // Per-variant launcher label for the debug channel: Full debug = "FadCam Beta",
        // Lite debug = "FadCam Lite Beta". Runs after flavor/buildType merges, so it only
        // touches the debug variants — all release variants keep their flavor appLabel.
        onVariants { variant ->
            if (variant.buildType == "debug") {
                val isLite = variant.name.startsWith("lite")
                val label = if (isLite) "FadCam Lite Beta" else "FadCam Beta"
                variant.manifestPlaceholders.put("appLabel", label)
                // The "-betaN" debug suffix belongs to the Full line's beta channel. Lite
                // ships its beta number inside liteVersionName itself (1.0.0-beta1), so
                // inheriting the suffix would produce "1.0.0-beta1-beta10.7.2" — a string
                // the in-app updater cannot parse, which makes it read as stable 1.0.0.
                if (isLite) {
                    variant.outputs.forEach { output ->
                        output.versionName.set(
                            output.versionName.get().removeSuffix(DEBUG_VERSION_SUFFIX))
                    }
                }
            }
        }
    }

    // Dynamic APK output names: FadCam_<flavor>_v<versionName><suffix>-<abi>.apk
    // (default flavor has no <flavor> part; universal APK gets the literal "-universal")
    applicationVariants.all {
        // Output-name version = the variant's real versionName. Lite debug has no
        // "-beta10.7.2" suffix (stripped per-variant above); its beta number is already
        // part of liteVersionName.
        val displayVersion = versionName
        val flavor = if (flavorName != "default") "${flavorName}_" else ""
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abiType = output.filters
                .firstOrNull { it.filterType == "ABI" }
                ?.identifier
                ?: "universal"
            output.outputFileName =
                "FadCam_${flavor}v${displayVersion}-${abiType}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Core library desugaring: rewrites newer JDK APIs (java.time, java.util.stream,
        // java.util.function, Optional…) into implementations that run on the old API levels we
        // still support (minSdk 24). Without it, any java.time call throws NoClassDefFoundError
        // on Android 7.x even though it compiles fine. Charges a few KB after R8 shrinking.
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            // Robolectric provides Android framework classes on the JVM.
            isIncludeAndroidResources = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    sourceSets {
        getByName("main") {
            java.srcDir("libs/AppLockLibrary/src/main/java")
            res.srcDir("libs/AppLockLibrary/src/main/res")
        }
        // NOTE: Removed setSrcDirs(emptyList()) to enable test source detection
        // getByName("test").java.setSrcDirs(emptyList<String>())
        // getByName("androidTest").java.setSrcDirs(emptyList<String>())
        
        // Flavor-specific resources (icons override main icons)
        getByName("notesPro") {
            res.srcDir("src/notesPro/res")
        }
        getByName("calcPro") {
            res.srcDir("src/calcPro/res")
        }
        getByName("weatherPro") {
            res.srcDir("src/weatherPro/res")
        }
        // Lite Pro disguises reuse the SAME launcher icons as their Full counterparts
        getByName("liteNotes") {
            res.srcDir("src/notesPro/res")
            manifest.srcFile("src/lite/AndroidManifest.xml")
        }
        getByName("liteCalc") {
            res.srcDir("src/calcPro/res")
            manifest.srcFile("src/lite/AndroidManifest.xml")
        }
        getByName("liteWeather") {
            res.srcDir("src/weatherPro/res")
            manifest.srcFile("src/lite/AndroidManifest.xml")
        }
        // Lite free: same component removals as the disguise flavors
        getByName("lite") {
            manifest.srcFile("src/lite/AndroidManifest.xml")
        }
        // Product copy belongs to every Lite tier; keep the free launcher artwork separate
        // so the paid Notes / Calculator / Weather flavors retain their own icons.
        listOf("lite", "liteNotes", "liteCalc", "liteWeather").forEach { flavor ->
            getByName(flavor).res.srcDir("src/liteCommon/res")
        }
        // Full-only source dir: heavy/native features that Lite must not package
        // (Faditor/ffmpeg, motion detection, object detection, forensics). Shared by ALL
        // Full flavors; Lite flavors (lite + disguises) never compile this code.
        val fullOnlyDirs = listOf("default", "notesPro", "calcPro", "weatherPro")
        fullOnlyDirs.forEach { flavor ->
            getByName(flavor).java.srcDir("src/full/java")
            getByName(flavor).assets.srcDir("src/full/assets")
        }
    }

    configurations.configureEach {
        // LiteRT declares Play Core's ai-delivery as a hard compile dependency, which
        // drags Play Services Tasks and Basement into every Full build. The detector
        // uses only the local interpreter and GPU delegate, never Play-delivered
        // models, so the Play stack is excluded rather than shipped.
        exclude(group = "com.google.android.play")
    }

    packaging {
        jniLibs {
            excludes += listOf("**/x86/**", "**/x86_64/**", "**/mips/**", "**/mips64/**")
            // OpenCV and ffmpeg-kit both bundle libc++_shared.so. Keep one copy.
            //
            // pickFirsts must be dropped for the spike build: pickFirsts wins over
            // excludes for the same path, so leaving it in would silently keep
            // libc++_shared.so in the APK and make the load test prove nothing.
            if (!project.hasProperty("spikeNoBundledNative")) {
                pickFirsts += listOf("**/libc++_shared.so")
            }
            // TEST HOOK, not a shipping path. Builds a Full APK with the libraries we
            // intend to move to downloadable assets left out, so AssetLoadSpikeTest can
            // prove they load from filesDir with no bundled copy to fall back on:
            //
            //   ./gradlew :app:assembleDefaultDebug -PspikeNoBundledNative=true
            //
            // Without the flag nothing changes. Removed by the PR that actually deletes
            // these libraries from the APK.
            if (project.hasProperty("spikeNoBundledNative")) {
                excludes += listOf(
                    "**/libopencv_java5.so",
                    "**/libavcodec.so",
                    "**/libavformat.so",
                    "**/libavfilter.so",
                    "**/libavdevice.so",
                    "**/libavutil.so",
                    "**/libswscale.so",
                    "**/libswresample.so",
                    "**/libffmpegkit.so",
                    "**/libffmpegkit_abidetect.so",
                    // Nothing else in the APK depends on it (LiteRT does not) and both
                    // OpenCV and ffmpeg do, so it has to ship inside the asset bundles.
                    "**/libc++_shared.so",
                )
            }
            // Enable 16KB page size alignment for Android 15 compatibility
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "**/*.kotlin_metadata",
                "**/*.kotlin_builtins",
                "**/*.proto",
                "assets/PSDs/**"  // Exclude PSD source files from release APK
            )
        }
    }

    androidResources {
        // Resource XML is compiled into resources.arsc / binary XML and never read as
        // raw files at runtime, so it can be deflated like everything else. The old
        // noCompress("xml") kept every compiled XML uncompressed and, because the same
        // list applies APK-wide, bloated res/ by ~1 MB. Only keep extensions that are
        // genuinely memory-mapped (none here: assets are plain opens).
        additionalParameters.add("--no-version-vectors")
        // Ship only the locales this app actually translates. Without this, the merged
        // resources drag in ~80 extra translations that come from libraries (ExoPlayer
        // player controls, AppCompat action-mode labels, Material badge descriptions).
        // Our own UI has no values-* dirs for those languages, so those users already
        // fall back to English; this just stops bundling strings they cannot reach.
        // `resourceConfigurations` was removed in AGP 8.8; `localeFilters` replaces it.
        localeFilters.addAll(listOf(
            "en", "ar", "de", "el", "es", "et", "fr", "in", "it", "ps", "ru", "tr", "zh-rCN"
        ))
    }

    buildFeatures {
        buildConfig = true
    }

    licenseReport {
        // Reports are build artifacts. The plugin otherwise copies an HTML report
        // into src/main/assets/, which dirties tracked source on every build.
        copyHtmlReportToAssets = false
        copyJsonReportToAssets = false
        copyCsvReportToAssets = false
        copyTextReportToAssets = false
    }


    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
    }
}

dependencies {
    // Backports for newer JDK APIs on old API levels — pairs with isCoreLibraryDesugaringEnabled.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.activity)
    implementation(libs.appintro.v631)
    implementation(libs.appcompat)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.core)
    implementation(libs.camerax.extensions)
    implementation(libs.camerax.view)
    implementation(libs.zxing.android.embedded)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.constraintlayout)
    implementation(libs.gridlayout)
    implementation(libs.core.ktx)
    // Media3 ExoPlayer for playback (replacing deprecated exoplayer2)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Media3 Transformer + Effect for Faditor Mini video editing
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    // AndroidX Media for MediaStyle notifications
    implementation(libs.media)
    implementation(libs.glide)
    implementation(libs.gson)
    implementation(libs.lottie)
    implementation(libs.material)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.okhttp)
    // On-device object detection (RF-DETR Nano via LiteRT) + OpenCV (MOG2).
    // FULL flavors only: Lite neither arms motion nor collects evidence.
    //
    // Model source (Apache-2.0):
    //   https://huggingface.co/litert-community/RF-DETR-Nano-LiteRT
    //   https://github.com/roboflow/rf-detr
    // The two .tflite graphs are committed under app/src/full/assets/models/.
    val fullFlavors = listOf("default", "notesPro", "calcPro", "weatherPro")
    fullFlavors.forEach { flavor ->
        // RF-DETR needs the LiteRT CompiledModel GPU path; the legacy TFLite GPU
        // delegate cannot execute this graph.
        // LiteRT declares Play Core's ai-delivery as a hard compile dependency, which
        // drags Play Services Tasks and Basement into every Full build. The detector
        // uses only the local interpreter and GPU delegate, never Play-delivered
        // models, so the Play stack is excluded rather than shipped.
        add("${flavor}Implementation", libs.litert)
        add("${flavor}Implementation", libs.litert.api)
        add("${flavor}Implementation", libs.litert.gpu)
        add("${flavor}Implementation", libs.opencv.android)
    }
    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.wms)
    implementation(libs.swiperefreshlayout)
    implementation(libs.viewpager2)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.core.splashscreen)
    implementation(libs.documentfile)
    implementation(libs.localbroadcastmanager)
    implementation(libs.room.runtime)
    
    // Media3 for fragmented MP4 muxing (patched for live streaming via composite build)
    implementation(libs.media3.muxer)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    
    // NanoHTTPD for HTTP streaming server
    implementation(libs.nanohttpd.core)

    annotationProcessor(libs.compiler)
    annotationProcessor(libs.room.compiler)

    // ffmpeg-kit: FULL flavors only (src/full code: Faditor, remux, batch export/merge,
    // duration probe, fix-video). Lite flavors never compile ffmpeg code, so the AAR is
    // scoped per Full flavor instead of global — this is the biggest Lite APK size win.
    val ffmpegAar = mapOf("name" to "ffmpeg-kit-full-6.0-2.LTS", "ext" to "aar")
    add("defaultImplementation", ffmpegAar)
    add("notesProImplementation", ffmpegAar)
    add("calcProImplementation", ffmpegAar)
    add("weatherProImplementation", ffmpegAar)
    implementation(libs.smart.exception.java)
    implementation(fileTree(mapOf("dir" to "libs/aar", "include" to listOf("*.aar"))))

    // Unit Testing Dependencies (Local JVM tests - fast, no device needed)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.json:json:20240303")

    // Android Instrumented Testing (runs on device/emulator)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// ---------------------------------------------------------------------------
// Open source licenses
// ---------------------------------------------------------------------------
// Each APK must list exactly the libraries it actually contains, so the report is
// generated per variant rather than once for the whole project: a Lite build must
// not credit the Full-only libraries, and a disguised Pro build must not either.
//
// Output goes to build/, never src/main/assets/, so building cannot dirty tracked
// source.
abstract class PrepareLicenseAsset : DefaultTask() {

    @get:InputFile
    abstract val report: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.mkdirs()
        report.get().asFile.copyTo(File(out, "open_source_licenses.json"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        // The plugin writes every variant's report into one shared directory by
        // default. That makes the variants clash over the same output location, which
        // Gradle rejects as an implicit dependency, so each variant is redirected to
        // its own directory.
        val reportDir = layout.buildDirectory.dir("reports/licenses/${variant.name}")
        // Matched by name and configured reflectively: the plugin keeps its task type
        // internal. outputDir is the only property needed, and it is public on the
        // instance.
        tasks.matching { it.name == "license${cap}Report" }.configureEach {
            try {
                javaClass.getMethod("setOutputDir", File::class.java)
                    .invoke(this, reportDir.get().asFile)
            } catch (e: NoSuchMethodException) {
                logger.warn("License report task has no setOutputDir; reports may collide")
            }
        }

        val prepare = tasks.register<PrepareLicenseAsset>("prepare${cap}LicenseAsset") {
            group = "reporting"
            description = "Copies the license report for the ${variant.name} variant onto its asset path."
            dependsOn("license${cap}Report")
            report.set(reportDir.map { it.file("license${cap}Report.json") })
            outputDir.set(layout.buildDirectory.dir("generated/licenses/${variant.name}"))
        }
        // Registers the directory for this variant only; AGP wires the dependency.
        variant.sources.assets?.addGeneratedSourceDirectory(prepare) { it.outputDir }
    }
}
