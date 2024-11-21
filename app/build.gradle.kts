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
        versionCode = 8
        versionName = "1.2.0"

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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
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

    implementation(libs.ffmpeg.kit.video)
}