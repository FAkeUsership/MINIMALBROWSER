# Minimal Browser Android project

This is the Kotlin Android project for **Minimal Browser**. Browsing uses the Android device’s **System WebView** provider rather than a bundled native browser engine.

## Build configuration

| Item | Value |
| --- | --- |
| Application ID | `com.minimal.browser` |
| SDK | min 26 / compile 36 / target 36 |
| Java | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.0 |
| Kotlin | 2.4.10 |
| Browser engine | Android System WebView |
| Release path | GitHub Actions only |

The root GitHub workflow installs Android platform 36 and build-tools 36.0.0 before running the wrapper on a GitHub runner. A pushed `v*` tag creates a matching GitHub Release containing one universal `MinimalBrowser-android-debug.apk` and `SHA256SUMS.txt`.

## System WebView behavior

- WebViews are created lazily per tab and only the active tab is attached to the visible Web screen.
- A missing provider is handled at the browser boundary, leaving non-web app screens usable.
- Android owns System WebView updates. Update Android System WebView or Chrome if the device provider is disabled or out of date.
- The project deliberately avoids old native renderer libraries and ABI-specific APK packaging.

## Page-only mode

Holding the **Web** rail icon for **exactly 5 seconds** opens a true page-only view: the active web page is the only visible app content and Android system bars are hidden immersively. Android **Back** or a double tap on the page restores the regular browser UI and normal Android system bars.
