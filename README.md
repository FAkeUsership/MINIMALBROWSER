# Minimal Browser

**Minimal Browser** is a native, landscape Android browser built with Kotlin and Mozilla GeckoView. Its visible app name, GitHub Actions job, release titles, and Android project directory all use **Minimal Browser** branding.

## Get an APK

Push a version tag such as `v1.0.4`. GitHub Actions builds the APKs and publishes a GitHub Release automatically:

```bash
git tag v1.0.4
git push origin v1.0.4
```

Download the matching asset from **Releases**:

- `MinimalBrowser-arm64-v8a-debug.apk` — recommended for nearly all current Android phones/tablets.
- `MinimalBrowser-armeabi-v7a-debug.apk` — older 32-bit ARM devices.
- `MinimalBrowser-x86_64-debug.apk` — x86_64 emulators.
- `MinimalBrowser-universal-debug.apk` — all bundled ABIs; much larger.
- `SHA256SUMS.txt` — SHA-256 hashes for exact download verification.

These are debug-signed builds. The application ID is `com.minimal.browser` and each release increments the version code, so newer builds install over prior Minimal Browser builds.

## Android 14+ browser-start repair (v1.0.4)

`v1.0.4` changes the GeckoView lifecycle to install the session `ContentDelegate` before opening the session (the ordering required by GeckoView’s Bug 1758212 workaround), then attaches GeckoView only to the visible Web surface. Crucially, it also forces Android to extract GeckoView’s native libraries at installation time rather than loading them directly from the APK—avoiding the known Gecko native-loader failure path. `SHA256SUMS.txt` on the release lets downloads be verified exactly.

## Page-only full screen

1. Open **Web** normally.
2. **Hold the Web icon for exactly 5 seconds.**
3. The rail, app top bar, address controls, dividers, progress line, badges, toast, and Android system bars disappear. The web page is the only visible content.
4. Press Android **Back** or **double-tap the page** to return to the normal browser controls and normal Android system bars.

Android system bars are not permanently hidden in ordinary screens. While page-only mode is active, edge-revealed bars use Android's transient immersive behavior and hide again automatically.

## Project layout

```text
minimal-browser/       Android Gradle project
.github/workflows/     GitHub build + Release workflow
```

The workflow explicitly installs Android API 36 and keeps `ANDROID_HOME` intact. A previous workflow removed the Android SDK before Gradle ran, which made every GitHub build fail with “SDK location not found.”

See [`minimal-browser/README.md`](minimal-browser/README.md) for technical details.
