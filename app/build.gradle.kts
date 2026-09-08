import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.fadcam"
    compileSdk = 36

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
        versionCode = 52
        versionName = "4.0.0"
        buildConfigField("boolean", "LITE_EDITION", "false")
        buildConfigField("String", "UPDATE_ORG", "\"anonfaded\"")
        buildConfigField("String", "UPDATE_REPO", "\"FadCam\"")
        buildConfigField("String", "UPDATE_PRO_REPO", "\"FadCamPro\"")
        // Launcher label per variant: defaultConfig is the base, flavors/build types override,
        // onVariants() below sets the per-variant debug labels (Full beta vs Lite beta).
        manifestPlaceholders["appLabel"] = "FadCam"
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
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream().use { stream ->
                stream?.let { props.load(it) }
            }
            val keystoreFile = props.getProperty("KEYSTORE_FILE", "")
            // Only set storeFile if keystore file path is provided and exists
            if (keystoreFile.isNotEmpty() && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = props.getProperty("KEY_ALIAS", "")
                keyPassword = props.getProperty("KEY_PASSWORD", "")
            }
        }
    }
    
    // Helper: check if release signing config is valid
    val releaseSigningConfigValid = signingConfigs.getByName("release").storeFile != null

    buildTypes {
        debug {
            applicationIdSuffix = ".beta"
            isDebuggable = true
            versionNameSuffix = "-beta10.6" // Increment the beta version suffix for each release. Use `beta1` for the first beta release, then `beta2`, etc.
            resValue("string", "app_name", "FadCam Beta")
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
            if (releaseSigningConfigValid) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-Pro"
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
            if (releaseSigningConfigValid) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-Pro+"
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
    // Current values are 0.0.0 / code 1 so the fresh v0.1.0 tags on the Lite repos
    // are detected as updates during testing.
    val liteVersionCode = 1
    val liteVersionName = "0.0.0"
    val liteRepo = "FadCam-Lite"          // Lite (primary/stable) update feed
    val liteProVersionCode = 1
    val liteProVersionName = "0.0.0"
    val liteProRepo = "FadCam-LitePro"    // Lite Pro (secondary) update feed

    // ── Release-time workflow (simple, per-line) ────────────────────────
    // Lite line:  bump `liteVersionCode` + `liteVersionName` (and `litePro*`
    //             for the Lite Pro disguises) right above. One edit per line.
    // Full line:  bump `defaultConfig.versionCode` + `versionName` below.
    // Debug builds share the "-beta10.6" suffix (build-type level, can't differ
    // per flavor); release APKs carry the exact per-line version above.

    productFlavors {
        create("notesPro") {
            dimension = "pro"
            applicationIdSuffix = ".notes"
            resValue("string", "app_name", "Notes")
            manifestPlaceholders["appLabel"] = "Notes"
        }
        create("calcPro") {
            dimension = "pro"
            applicationIdSuffix = ".calc"
            resValue("string", "app_name", "Calculator")
            manifestPlaceholders["appLabel"] = "Calculator"
        }
        create("weatherPro") {
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
                val label = if (variant.name.startsWith("lite")) "FadCam Lite Beta" else "FadCam Beta"
                variant.manifestPlaceholders.put("appLabel", label)
            }
        }
    }

    // Dynamic APK output names: FadCam_<flavor>_v<versionName><suffix>-<abi>.apk
    // (default flavor has no <flavor> part; universal APK gets the literal "-universal")
    applicationVariants.all {
        // Output-name version = the variant's real versionName (per-flavor 0.0.0 /
        // 4.0.0 + buildType suffix). AGP doesn't allow per-flavor debug suffixes, so
        // Lite debug carries the same "-beta10.6" suffix as Full debug.
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
        // Full-only source dir: heavy/native features that Lite must not package
        // (Faditor/ffmpeg, motion detection/OpenCV/TFLite, forensics). Shared by ALL
        // Full flavors; Lite flavors (lite + disguises) never compile this code.
        val fullOnlyDirs = listOf("default", "notesPro", "calcPro", "weatherPro")
        fullOnlyDirs.forEach { flavor ->
            getByName(flavor).java.srcDir("src/full/java")
            getByName(flavor).assets.srcDir("src/full/assets")
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("**/x86/**", "**/x86_64/**", "**/mips/**", "**/mips64/**")
            // OpenCV and ffmpeg-kit both bundle libc++_shared.so. Keep one copy.
            pickFirsts += listOf("**/libc++_shared.so")
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
        noCompress.add("xml")
        additionalParameters.add("--no-version-vectors")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
    }
}

// tensorflow-lite-api is excluded for task-vision on ALL Full flavor configurations
configurations {
    listOf("defaultImplementation", "notesProImplementation", "calcProImplementation", "weatherProImplementation")
        .forEach { name ->
            getByName(name).exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
        }
}

dependencies {
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
    // TensorFlow Lite (AI object detection) + OpenCV (MOG2 motion): FULL flavors only —
    // detectors live in src/full, Lite keeps the pure-Java FrameDiff fallback.
    val fullFlavors = listOf("default", "notesPro", "calcPro", "weatherPro")
    fullFlavors.forEach { flavor ->
        add("${flavor}Implementation", libs.tensorflow.lite)
        add("${flavor}Implementation", libs.tensorflow.lite.task.vision)
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
