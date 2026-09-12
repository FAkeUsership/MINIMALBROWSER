# Minimal — a landscape, black-&-white Android browser

One repo, two builds of the same app. Same UI, same left rail, same
**hold-Web-for-5-seconds full screen**. They differ only in the engine.

| | `firefox/` | `lite/` |
| --- | --- | --- |
| Engine | **Firefox / GeckoView** (open source, MPL 2.0) | Android system WebView |
| Universal APK | ~530 MB | **~4 MB** |
| Per-ABI APK | ~160–220 MB | n/a |
| `applicationId` | `com.minimal.browser` | `com.minimal.browser.lite` |
| Private tabs | real isolated session | history suppressed only |
| Fingerprinting resistance | yes | no |
| HTTPS-only upgrades | yes | mixed-content policy only |
| minSdk | 26 | 24 |

They have different application IDs, so **both can be installed side by side**.

---

## Get an APK

Push a tag and CI publishes to the Releases page:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Then download from **repo → Releases**. A tag builds:

* `MinimalBrowser-lite-debug.apk` — ~4 MB
* `app-arm64-v8a-debug.apk` — Firefox engine, for modern tablets ← install this one
* `app-armeabi-v7a-debug.apk` — Firefox engine, old 32-bit devices
* `app-x86_64-debug.apk` — Firefox engine, emulators
* `app-universal-debug.apk` — Firefox engine, ~530 MB, every ABI

Install:

```bash
adb install -r app-arm64-v8a-debug.apk
```

These are debug-signed, so they install directly with no keystore.

A plain `git push` to `main` builds only the Lite variant and attaches it to
the workflow run as an artifact (small enough to be free-tier friendly).

---

## Why CI instead of building locally

The Firefox build needs more machine than most people expect: the GeckoView
artifact is ~241 MB and the packaged APK is ~530 MB. `ubuntu-latest` gives you
16 GB RAM / 14 GB disk, which is comfortable. A 1 GB container will get
OOM-killed during `packageDebug` — that is a memory ceiling, not a bug.

Release assets are used rather than `upload-artifact` for the Firefox APK on
purpose: workflow artifacts count against the Actions storage budget
(500 MB free), while a single Release asset may be up to 2 GB.

---

## Full screen mode

| Gesture | Result |
| --- | --- |
| **Hold the `Web` rail button 5 s** | A ring fills. On completion the rail, top bar, address bar, back/forward, shield pill, progress track, blocked banner **and the Android status + navigation bars** all disappear. Only the page stays. |
| **Press Back twice** | The rail peeks back for 4 s so you can move around. |
| **Double-tap the `◀ back` pill** | Same, for devices without a back gesture. |
| **Hold `Web` again** | Chrome fully restored. |
| **Tap `Web`** | Just switches to the Web screen. |

Hold length is configurable: Settings → Appearance → Hold duration (1–15 s).
A normal tap is never swallowed — releasing early cancels the hold.

---

## Features

Multi-tab with real thumbnails and session restore · private tabs · two-layer
ad/tracker blocking (engine protection **plus** the request-level rules in
`app/src/main/assets/blocklist.txt`, evaluated for sub-resources too) ·
downloads to your Files app · print · find in page · bookmarks · history ·
JSON export/import · clear browsing data · monochrome error pages · landscape
lock · HTML-video full screen.

---

## Build locally

Each folder is a standalone Gradle project with its own wrapper.

```bash
cd lite     && ./gradlew assembleDebug   # ~4 MB, quick
cd firefox  && ./gradlew assembleDebug   # ~530 MB, needs RAM
```

JDK 17+. The Firefox module uses Gradle 8.13 / AGP 8.13.0 / Kotlin 2.4.10 —
see `firefox/README.md` for why it cannot go lower.

---

## Licences

App code: yours. The bundled engine is GeckoView, Mozilla Public License 2.0.
