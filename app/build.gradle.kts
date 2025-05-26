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
        versionCode = 9
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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.0")
    implementation(libs.gson)
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

    implementation(libs.appintro.v631)

    implementation(libs.lottie)

}