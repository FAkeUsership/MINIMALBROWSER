# Minimal Browser

**Minimal Browser** is a native, landscape Android browser written in Kotlin. It uses the Android device’s maintained **System WebView** renderer rather than shipping a second native browser engine, keeping the app small and avoiding the bundled-renderer startup path that failed on the affected Android 14+ device.

## What v1.1.0 repairs

- Web, address-bar navigation and search, Home quick links (including Mail and YouTube), tabs, restores, downloads, printing, history, and private tabs now use Android System WebView end to end.
- WebViews are created only when a tab needs one. A missing or disabled provider is contained with a clear in-app message instead of crashing Home, Tabs, or Settings.
- Background tab callbacks cannot replace the visible tab. A renderer process loss is handled by replacing and reloading only the affected tab.
- `mailto:`, `tel:`, `geo:`, `market:`, and Android `intent:` links are handed to matching Android apps; ordinary `http`/`https` navigation remains inside Minimal Browser.
- The Gradle project, manifest, and GitHub release workflow no longer package the old native engine or ABI-split APKs. Each release is one universal System WebView APK.

## Get an APK

Releases are built **only by GitHub Actions**. Push a new version tag, for example:

```bash
git tag v1.1.0
git push origin v1.1.0
```

The workflow builds the Android project on GitHub and creates/updates the matching GitHub Release with:

- `MinimalBrowser-android-debug.apk` — one universal APK for Android 8.0+ devices.
- `SHA256SUMS.txt` — SHA-256 hash for verifying that exact APK download.

The APK is debug-signed. Its application ID is `com.minimal.browser`; the release version code must increase for Android to install it over an earlier Minimal Browser build.

GitHub Actions verifies generated package metadata and that no old bundled native browser library is present. That confirms the published artifact reflects this repair, but it is not a substitute for installing it and exercising the flow on a physical device.

## Page-only full screen

1. Open **Web** normally.
2. **Hold the Web rail icon for exactly 5 seconds.**
3. The rail, app top bar, address controls, dividers, progress line, badges, toast, and Android system bars disappear. Only the web page remains.
4. Press Android **Back** or **double-tap the page** to restore normal browser controls and normal Android system bars.

Ordinary Home, Web, Tabs, and Settings screens retain normal Android system bars. During page-only mode, Android uses transient immersive bars if the user deliberately swipes at an edge.

## Project layout

```text
minimal-browser/       Minimal Browser Android Gradle project
.github/workflows/     GitHub Actions build and Release workflow
```

The workflow installs Java 17 and Android API 36/build-tools 36.0.0, and deliberately keeps `ANDROID_HOME` available for Gradle.

See [`BUILD.md`](BUILD.md) for the GitHub release path and [`minimal-browser/README.md`](minimal-browser/README.md) for Android project notes.
