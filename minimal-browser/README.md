# Minimal Browser Android project

This is the Android project for **Minimal Browser**, a Kotlin browser shell using Mozilla GeckoView.

## Build configuration

| Item | Value |
| --- | --- |
| Application ID | `com.minimal.browser` |
| SDK | min 26 / compile 36 / target 36 |
| Java | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.0 |
| Kotlin | 2.4.10 |
| Browser engine | Mozilla GeckoView 153 |

The top-level GitHub workflow installs Android platform 36 and build-tools 36.0.0 before running the wrapper. It creates ABI-specific debug APKs plus a universal APK and attaches them to a GitHub Release when a `v*` tag is pushed.

## Local build (optional)

```bash
./gradlew assembleDebug
```

The GeckoView dependency and universal APK are large, so GitHub Actions is recommended when a local machine has limited RAM or disk.

## Page-only mode

Holding the **Web** rail icon for **5 seconds** opens a true page-only view: GeckoView is the only visible app content, and Android status/navigation bars are hidden transiently. Android **Back** or a double tap on the page returns to the regular browser UI. Normal screens restore the real Android system bars.
