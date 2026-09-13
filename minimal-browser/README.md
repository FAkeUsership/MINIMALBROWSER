# Minimal Browser Android project

This directory contains the Kotlin Android application for **Minimal Browser**.

## Engine and ABI

The app depends on Mozilla's release-channel GeckoView:

```text
org.mozilla.geckoview:geckoview:153.0.20260810162159
```

The Gradle configuration intentionally packages only `arm64-v8a`. The resulting non-debuggable release APK is large because Gecko's native browser engine is included in the app; Android System WebView is not used for browsing.

| Project setting | Value |
| --- | --- |
| Application ID | `com.minimal.browser` |
| Min SDK | 26 (Android 8.0) |
| Target / compile SDK | 36 |
| Browser engine | Mozilla GeckoView, bundled ARM64 |
| Native-library loading | Android install-time extraction |
| Release build location | GitHub Actions only |

## Runtime design

- `BrowserApp` creates one process-wide `GeckoRuntime` lazily at the Web/navigation boundary.
- `TabManager` gives each tab a `GeckoSession`, installs all delegates before `open`, and keeps only one visible surface attached.
- `MainActivity` creates one `GeckoView` only after the Web screen becomes visible, retains GeckoView’s initialized default high-performance `SurfaceView`, and releases the session before hiding/removing the view. It does not redundantly reset that backend.
- Automatic full-frame tab screenshots are intentionally avoided; tab cards use lightweight artwork so switching/loading does not force a GPU readback.
- Shield events are coalesced into short UI/database batches on one low-priority worker; ordinary tab restore stores URLs/titles only. Private tabs are excluded.
- The three-dot drawer is a real right-side menu (not a decorative strip), with normal/private tabs, library actions, Downloads, Find in page, privacy controls, sharing, printing, and Settings.
- A download is confirmed before start, writes GeckoView's original authorized stream to Android Downloads, and becomes an openable library entry only after the completed file is published. Private-tab downloads are not retained in the app list.
- Physical USB/Bluetooth keyboard detection combines configuration and `InputManager` device callbacks. Native text fields opt out of a forced software IME while hardware input is attached; page-field IME requests are dismissed without clearing focus. Enter/numpad Enter, standard browser chords, and mouse Back/Forward side buttons are handled at the Activity boundary, while ordinary Gecko keyboard/mouse input is passed through.
- `onCrash` and `onKill` replace only the affected session and reload its last URL.

## Page-only mode

The Web rail button has one fixed gesture: **hold it for exactly 5 seconds**. Page-only mode leaves the web page as the only visible content and hides Android system bars. Android’s non-sticky default inset behavior and AndroidX predictive Back support ensure one Android Back gesture/button or a double tap on the page restores normal browser controls and normal system bars.

## Build and release

Do not produce release APKs with a local Gradle build. Push a new `v*` tag and let [`.github/workflows/build.yml`](../.github/workflows/build.yml) build and verify the package on GitHub Actions. The release asset is:

- `MinimalBrowser-arm64-v8a-release.apk`
- `SHA256SUMS.txt`
