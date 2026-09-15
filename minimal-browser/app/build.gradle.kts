import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minimal.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minimal.browser"
        // Android 8.0+; the bundled Mozilla engine itself is packaged in this APK.
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.2.8"

        // The requested release is intentionally ARM64-only. This makes the
        // APK substantial because it carries Gecko's native ARM64 libraries,
        // rather than delegating page rendering to Android System WebView.
    }

    // Emit exactly one ABI split. This filters dependency native libraries
    // too, so the release cannot silently become a universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            // Keep the already field-proven signing/packaging profile. Gecko
            // remote debugging is disabled explicitly in BrowserApp instead of
            // changing this variant's packaging characteristics.
            isMinifyEnabled = false
        }
        release {
            // Build an actual non-debuggable release variant. The repository has
            // no persistent private signing key, so GitHub uses Android's
            // standard debug key only to make this sideloadable; that certificate
            // does not make the installed process debuggable.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Gecko loads companion libraries dynamically. Keep Android's
            // install-time extraction path instead of direct APK mapping, which
            // avoids the Android 14+ native-loader path behind the old failures.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Mozilla's release-channel GeckoView. Native ARM64 engine code is bundled
    // by this dependency and restricted above to arm64-v8a in the APK.
    implementation("org.mozilla.geckoview:geckoview:153.0.20260810162159")
}
