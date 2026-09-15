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
- Physical USB/Bluetooth keyboard detection combines configuration and `InputManager` device callbacks. Native text fields and Gecko page fields follow the same mobile-IME policy: attached hardware always suppresses it, and **Settings → Appearance → Show mobile keyboard** lets a person hide or restore it manually without clearing the focused page field. Enter/numpad Enter uses both native editor handling and an Activity-level focused-field fallback; standard browser chords and mouse Back/Forward side buttons are handled at the Activity boundary, while ordinary Gecko keyboard/mouse input is passed through.
- `onCrash` and `onKill` replace only the affected session and reload its last URL.

## Page-only mode

The large black Web/breadcrumb header and the Web-rail hold gesture are intentionally absent. A compact top-right corners toggle explicitly enters page-only mode; it remains in the same place as a compact close control so leaving does not depend on an accidental Back action. Page-only mode hides normal browser chrome and Android system bars. Android Back and a page double tap remain supplementary exits. Normal Web screens use light system-bar surfaces with dark icons and disable Android contrast bands so a landscape cutout or bottom navigation area does not appear as an opaque black strip.

## Build and release

Do not produce release APKs with a local Gradle build. Push a new `v*` tag and let [`.github/workflows/build.yml`](../.github/workflows/build.yml) build and verify the package on GitHub Actions. The release asset is:

- `MinimalBrowser-arm64-v8a-release.apk`
- `SHA256SUMS.txt`
