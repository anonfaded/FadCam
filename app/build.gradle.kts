plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.fadcam"
    compileSdk = 35

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a") // Only include the architectures you need
            isUniversalApk = true // Create a universal APK with all architectures
        }
    }

    defaultConfig {
        applicationId = "com.fadcam"
        minSdk = 28
        targetSdk = 34
        versionCode = 16
        versionName = "1.5.0"

//        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Enable vector drawable support
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            // Enable R8 optimization with custom rules
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            // More aggressive optimizations for release
            isDebuggable = false
            isCrunchPngs = true // Aggressively optimize PNG files
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    
    // Proper resource handling
    android.aaptOptions.noCompress += listOf("xml")
    android.aaptOptions.cruncherEnabled = true // Enable PNG cruncher
    
    // Generate R class for the AppLock library
    android.namespace = "com.fadcam"
    
    // Add the sourceSets for the AppLockLibrary
    sourceSets {
        getByName("main") {
            java.srcDir("libs/AppLockLibrary/src/main/java")
            // Include all resources
            res.srcDir("libs/AppLockLibrary/src/main/res")
        }
        getByName("test").java.srcDirs("none")
        getByName("androidTest").java.srcDirs("none")
    }
    
    // Exclude specific ABIs from the FFmpeg library to reduce size
    packagingOptions {
        // Exclude unnecessary architectures
        jniLibs {
            excludes += listOf("**/x86/**", "**/x86_64/**", "**/mips/**", "**/mips64/**")
        }
        
        // Exclude unnecessary files from the APK
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
                "**/*.proto"
            )
        }
    }
    
    // Enable resource optimization
    androidResources {
        additionalParameters += listOf("--no-version-vectors")
    }
    
    // Enable build config optimization
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Use implementation instead of api for all dependencies to reduce APK size

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.gson)
    
    // CameraX dependencies
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.extensions)

//    testImplementation(libs.junit)
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
    implementation(libs.core.ktx)
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.ui)
    implementation(libs.okhttp)
    implementation(libs.viewpager2)
    implementation(libs.glide)
    annotationProcessor(libs.compiler)
//    implementation(libs.ffmpeg.kit.full.v44lts)
    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.wms)

//    implementation(libs.ffmpeg.kit.full) // ffmpeg-kit got retired, now we need to use  a custom fork
//    implementation("com.github.anonfaded:ffmpeg-kit:main-SNAPSHOT")
    implementation(mapOf("name" to "ffmpeg-kit-full-6.0-2.LTS", "ext" to "aar"))

    // Replace JAR files with Maven dependency
    implementation(libs.smart.exception.java)

    // Keep only AAR files
    implementation(fileTree(mapOf("dir" to "libs/aar", "include" to listOf("*.aar"))))

    implementation(libs.appintro.v631)

    implementation(libs.lottie)
    // Removing AppLockLibrary as a project dependency
    // implementation(project(":app:libs:AppLockLibrary"))
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime)
}