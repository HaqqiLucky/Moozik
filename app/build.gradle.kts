plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.liam.moozik"
    compileSdk = 34 // Pakai 34 yang stabil. 36 itu masih preview/beta.

    defaultConfig {
        applicationId = "com.liam.moozik"
        minSdk = 21 // Kita pertahankan ini biar HP lama bisa pakai
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // --- CORE ANDROID ---
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // --- FITUR UTAMA ---

    // 1. ExoPlayer
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")

    // 2. Vosk (Voice Recognition)
    // HAPUS baris 'jna' disini. Vosk akan otomatis download versi yang benar.
    implementation("com.alphacephei:vosk-android:0.3.47")

    // 3. Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.recyclerview:recyclerview:1.3.1")

    implementation("androidx.media:media:1.6.0") // WAJIB ADA
}