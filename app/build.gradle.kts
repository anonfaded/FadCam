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
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a") // Adjust as needed
            isUniversalApk = true // This is key for your universal APK
        }
    }


    defaultConfig {
        applicationId = "com.fadcam"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.2.1-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    
    // Generate R class for the AppLock library
    android.namespace = "com.fadcam"
    
    // Add the sourceSets for the AppLockLibrary
    sourceSets {
        getByName("main") {
            java.srcDir("libs/AppLockLibrary/src/main/java")
            // Include all resources
            res.srcDir("libs/AppLockLibrary/src/main/res")
        }
    }
}

dependencies {

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

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
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

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs/aar", "include" to listOf("*.aar"))))

    implementation(libs.appintro.v631)

    implementation(libs.lottie)
    // Removing AppLockLibrary as a project dependency
    // implementation(project(":app:libs:AppLockLibrary"))
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime)
}