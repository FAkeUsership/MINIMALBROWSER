# Minimal Lite — the same browser, 4.2 MB

This is the **same app, same UI, same full-screen gesture** as the Firefox
build — but running on the **Android platform WebView** instead of GeckoView.

| | Minimal (GeckoView) | **Minimal Lite (this one)** |
| --- | --- | --- |
| Engine | Firefox / GeckoView | Android system WebView |
| Debug APK | 506 MB | **4.2 MB** |
| Needs to ship an engine | yes (241 MB download + 3 ABIs) | no — the OS already has one |
| minSdk | 26 | 24 |

**This is the trade you asked for.** A 50–70 MB Firefox-engine browser is not
physically possible: `libxul.so` alone is 152 MB for arm64 (117 MB armv7,
166 MB x86_64), and that is *before* any of your app. Compressing it harder
doesn't help — it's already compressed inside the APK. So to get small, the
bundled engine has to go.

---

## Install it

```bash
adb install -r MinimalBrowser-lite-debug.apk
```

It's a debug-signed build, which installs directly on any device. No Play
Store, no keystore needed.

---

## What still works (identical to the Firefox build)

* Left rail with the monogram, Home / Web / Tabs / Settings
* **Hold `Web` for 5 s → true full screen.** Rail, top bar, address bar,
  back/forward/refresh, shield pill, progress track, blocked banner *and* the
  Android status + navigation bars all disappear.
* **Back twice** (or double-tap the `◀ back` pill) → the rail peeks for 4 s.
* **Hold `Web` again** → chrome restored. Hold length configurable in Settings.
* Multi-tab with real thumbnails, close, new tab, private tab
* Ad/tracker blocking — `assets/blocklist.txt` evaluated in **both**
  `shouldOverrideUrlLoading` and `shouldInterceptRequest`, so sub-resources
  (scripts, images, iframes, pixels) are stopped too, not just top-level
  navigations. Counters drive the pill, the banner and the home footer.
* Downloads via the system DownloadManager (land in your Files app)
* Print via `createPrintDocumentAdapter` → the normal system print dialog
* Find in page, share, bookmark, history, JSON export/import
* Clear browsing data (history + cookies + cache + blocked log)
* Monochrome error pages, landscape lock, HTML-video full screen
* Text size 80–140 %, JavaScript toggle, desktop-UA toggle, search engine and
  homepage pickers

## What is weaker than the Firefox build

Be honest about these — they are the real cost of dropping the engine:

* **Private tabs** don't get an isolated cookie jar for their lifetime the way
  a real private window does. They never write to history and the cookie
  switch is flipped while one is open, but it is not a true private session.
* **Fingerprinting resistance** and **HTTPS-only upgrades** are Firefox
  features. Here "Upgrade to HTTPS" maps to WebView's mixed-content policy,
  which is much weaker.
* **Site isolation / sandboxing** is whatever the system WebView provides,
  which varies by device and by how up to date the user's WebView is.
* **Rendering** is Chromium via the OS component, so page support tracks the
  device's WebView rather than a version you control.
* **Camera/mic** requests from web pages are denied rather than prompted
  (the file picker *is* wired up).

If any of those matter, build the GeckoView variant — the source for it is in
`MinimalBrowser.zip`, and it produces the 506 MB APK.

---

## Rebuild it yourself

```bash
cd MinimalBrowserLite
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

JDK 17+, Android SDK 35, Gradle 8.13 (the wrapper handles Gradle).

To make a release build, add a signing config under `buildTypes.release` —
right now release is unsigned on purpose.

---

## Verified

`./gradlew clean :app:assembleDebug` → **BUILD SUCCESSFUL**
`apksigner verify` → signature OK
`aapt2 dump xmltree` → `screenOrientation=6` (sensorLandscape) present in the
merged binary manifest, `minSdk 24 / targetSdk 35`
`assets/blocklist.txt` present in the APK, zero Gecko artifacts.

Not verified: runtime behaviour on a real device (no emulator in the build
environment).
