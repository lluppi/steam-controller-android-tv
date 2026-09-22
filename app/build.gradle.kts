import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// NOTE: ktlint is deliberately NOT wired into the build.
//
// It was tried: the upstream codebase violates ktlint's *standard* ruleset throughout (max line
// length, multiline-if-else, statement-wrapping, no-semi, trailing-comma-on-call-site,
// function-literal) in files this fork never touched, so `ktlintFormat` rewrote ~16 unrelated
// files and had to be reverted, and a `ktlintCheck` in CI would fail on day one. Adopting ktlint is
// a whole-codebase decision - one dedicated formatting commit, reviewed with `git diff -w` - not a
// cleanup step. `.editorconfig` declares the intended style (`android_studio`) for editors that
// honour it.

// Load signing config from keystore.properties (gitignored) if present.
// Falls back to env vars for CI builds. If neither is set, release builds are unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "com.steamcontroller.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.steamcontroller.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1-shield"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
            val storePass = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            val alias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            val keyPass = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")

            if (storeFilePath != null && storePass != null && alias != null && keyPass != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            // Install side by side with any existing build instead of replacing it. A debug
            // build is signed with a different key, so an in-place upgrade is impossible and
            // adb would have to uninstall first - which would take the user's calibrations,
            // mappings and named profiles with it. Release builds keep the real application
            // id, so this only affects debug installs.
            applicationIdSuffix = ".debug"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val cfg = signingConfigs.getByName("release")
            // Only attach the signing config if it was actually populated above.
            if (cfg.storeFile != null) {
                signingConfig = cfg
            }
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
