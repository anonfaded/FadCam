import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.fadcam"
    compileSdk = 36

    val isBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    val isProBuild = gradle.startParameter.taskNames.any { it.lowercase().contains("pro") }

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
        minSdk = 28
        targetSdk = 36
        versionCode = 31
        versionName = "3.0.1"
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
            versionNameSuffix = "-beta"
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
        }
    }

    flavorDimensions += "pro"

    productFlavors {
        create("notesPro") {
            dimension = "pro"
            applicationIdSuffix = ".notes"
            resValue("string", "app_name", "Notes")
        }
        create("calcPro") {
            dimension = "pro"
            applicationIdSuffix = ".calc"
            resValue("string", "app_name", "Calculator")
        }
        create("weatherPro") {
            dimension = "pro"
            applicationIdSuffix = ".weather"
            resValue("string", "app_name", "Weather")
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

    // Variant filter: only build specific variants
    variantFilter {
        val isPreBuiltFlavor = name.contains("notesPro") || name.contains("calcPro") || name.contains("weatherPro")
        val isDefaultFlavor = name.contains("default")
        
        if (isPreBuiltFlavor) {
            // Pre-built flavors: only 'release' build type
            if (!name.endsWith("Release")) {
                ignore = true
            }
        } else if (isDefaultFlavor) {
            // Default flavor: allow 'debug', 'release', and 'proPlus' build types
            if (name.endsWith("Pro") && !name.endsWith("ProPlus")) {
                ignore = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
        getByName("test").java.setSrcDirs(emptyList<String>())
        getByName("androidTest").java.setSrcDirs(emptyList<String>())
        
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
    }

    packaging {
        jniLibs {
            excludes += listOf("**/x86/**", "**/x86_64/**", "**/mips/**", "**/mips64/**")
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

dependencies {
    implementation(libs.activity)
    implementation(libs.appintro.v631)
    implementation(libs.appcompat)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.core)
    implementation(libs.camerax.extensions)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    // Media3 ExoPlayer for playback (replacing deprecated exoplayer2)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    // AndroidX Media for MediaStyle notifications
    implementation(libs.media)
    implementation(libs.glide)
    implementation(libs.gson)
    implementation(libs.lottie)
    implementation(libs.material)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.okhttp)
    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.wms)
    implementation(libs.swiperefreshlayout)
    implementation(libs.viewpager2)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime)
    implementation(libs.core.splashscreen)
    implementation(libs.documentfile)
    implementation(libs.localbroadcastmanager)
    
    // Media3 for fragmented MP4 muxing (patched for live streaming via composite build)
    implementation(libs.media3.muxer)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    
    // NanoHTTPD for HTTP streaming server
    implementation(libs.nanohttpd.core)
    
    // MP4Parser for reliable MP4 box structure parsing
    implementation("com.googlecode.mp4parser:isoparser:1.1.22")

    annotationProcessor(libs.compiler)

    implementation(mapOf("name" to "ffmpeg-kit-full-6.0-2.LTS", "ext" to "aar"))
    implementation(libs.smart.exception.java)
    implementation(fileTree(mapOf("dir" to "libs/aar", "include" to listOf("*.aar"))))
}
