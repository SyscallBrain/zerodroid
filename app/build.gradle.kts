plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

// Release signing comes from environment variables (CI) or from ~/.gradle/gradle.properties
// (local builds). Without them the release APK is built unsigned.
fun signingValue(env: String, property: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(property)).orNull

val releaseStoreFile = signingValue("ZERODROID_KEYSTORE", "zerodroid.release.storeFile")

android {
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("ZERODROID_KEYSTORE_PASSWORD", "zerodroid.release.storePassword")
                keyAlias = signingValue("ZERODROID_KEY_ALIAS", "zerodroid.release.keyAlias")
                keyPassword = signingValue("ZERODROID_KEY_PASSWORD", "zerodroid.release.keyPassword")
            }
        }
    }

    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "pt.tiago.zerodroid"
        namespace = "pt.tiago.zerodroid"
        minSdk = 29
        targetSdk = 36
        versionCode = 60
        versionName = "0.6.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PERF", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "PERF", "false")
        }
        // Release build for performance measurement: R8, not debuggable, library baseline
        // profiles installed, signed with the debug key so it installs over the debug build.
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PERF", "true")
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
            )
        }
    }
}
dependencies {
    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")

    // Compose BOM — brings ui, material3, foundation, activity-compose
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui-android")
    implementation("androidx.compose.material3:material3-android")
    implementation("androidx.compose.foundation:foundation-android")

    // QR scanning through Google Play services (no camera permission, UI provided by GMS)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // Security: Bouncy Castle for CSR, DataStore for encrypted token
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    // Network: OkHttp for WebSocket and enrollment
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.browser:browser:1.10.0")

    // Kotlinx coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // Installs the baseline profiles shipped by Compose and other AndroidX libraries (non-debuggable builds).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.21")
}