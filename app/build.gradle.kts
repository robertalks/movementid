plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.movementid.app"
    // Google Play requires new apps and updates to target API 36 (Android 16) from 31 Aug 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.movementid.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "3.0.1"
    }

    /**
     * Signing for CI. The keystore never lives in the repository: the workflow writes it from a
     * secret and passes its location and passwords as environment variables. Locally these are
     * unset, so this block does nothing and Android Studio's own signing dialog still works.
     */
    val ciKeystore = System.getenv("MOVEMENTID_KEYSTORE")

    // Says plainly, in the build log, whether CI signing is being wired up. Without this an
    // unsigned APK looks identical to a signed one until someone tries to install it.
    println(
        if (ciKeystore.isNullOrBlank()) {
            "MovementID: MOVEMENTID_KEYSTORE not set — release builds will be UNSIGNED."
        } else {
            "MovementID: signing releases with keystore at $ciKeystore " +
                "(exists=${file(ciKeystore).exists()}, alias=${System.getenv("MOVEMENTID_KEY_ALIAS")})"
        }
    )

    signingConfigs {
        if (!ciKeystore.isNullOrBlank()) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("MOVEMENTID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MOVEMENTID_KEY_ALIAS")
                keyPassword = System.getenv("MOVEMENTID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (!ciKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Exposes versionName/versionCode so the app can show which build is actually installed.
        buildConfig = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    // Makes the Android 12+ system splash themeable so it can be hidden behind our own.
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Runs the backup on Android's terms: survives app death, honours the WiFi constraint.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
