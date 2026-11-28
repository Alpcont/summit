plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alp.summit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alp.summit"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "5.1"
    }

    // Fixed key so every new APK installs as an update over the previous one.
    // Never change or regenerate this keystore.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Sync between devices switches on once the Firebase project file is in this folder.
// Without it the app still builds and simply has no sync.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// The only libraries: Firebase (sign-in and the cloud database) and Google's sign-in sheet.
// Everything else uses Android platform APIs. Versions are pinned to ones that fit Kotlin 2.0.
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
