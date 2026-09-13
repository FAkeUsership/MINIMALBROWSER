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
        minSdk = 26          // GeckoView declares minSdkVersion 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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

    /**
     * GeckoView carries native code for three ABIs (~150 MB each), so the
     * universal APK is ~530 MB. Splitting also emits one APK per ABI
     * (~160–220 MB) — install `arm64-v8a` on essentially any modern tablet.
     * CI has the RAM for this; a 1 GB machine may not.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ---- Firefox / GeckoView engine (open source, prebuilt, MPL 2.0) ----
    implementation("org.mozilla.geckoview:geckoview:153.0.20260810162159")
}
