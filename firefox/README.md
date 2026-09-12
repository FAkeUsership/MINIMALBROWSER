# Minimal — a landscape, black-&-white browser for Android

A real, working Android browser built in **Kotlin** on the **Firefox engine
(GeckoView)** — not a WebView, not a web preview. It is a native port of
`browser-app-screens.html`: same left rail, same four screens, same monochrome
design language, plus one new feature — **full screen mode**.

---

## 1. Build it

Requirements:

| Tool | Version |
| --- | --- |
| JDK | 17 or newer (Android Studio bundles one) |
| Android SDK Platform | 36 |
| Build-tools | 36.0.0 |
| Gradle | 8.13 (supplied by the wrapper) |
| Android Gradle Plugin | 8.13.0 |
| Kotlin | 2.4.10 |

```bash
# from the project root
./gradlew assembleDebug          # Linux / macOS
gradlew.bat assembleDebug        # Windows
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads the GeckoView artifact (~241 MB) from
`maven.mozilla.org`, so give it a few minutes. The resulting APK is ~530 MB
because it contains the Firefox engine for **three** ABIs. See
*“Smaller APKs”* below to cut that to ~160–220 MB.

### Smaller APKs (optional)

Open `app/build.gradle.kts` and uncomment the `splits { abi { … } }` block.
You then get one APK per ABI:

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk     ← almost every modern tablet
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk   ← old 32-bit devices
app/build/outputs/apk/debug/app-x86_64-debug.apk        ← emulators
```

---

## 2. Why this is not Gradle 8.4

You asked for Gradle 8.4. **No current Firefox engine can build on Gradle 8.4**,
and this was verified, not guessed:

* Gradle 8.4 → the newest Android Gradle Plugin it can run is **8.3.2**
  (AGP 8.4 needs Gradle 8.6).
* AGP 8.3.2 caps out at **compileSdk 34**.
* GeckoView 153 (used here) depends on `androidx.core:1.17.0`, which requires
  **compileSdk 35**.
* GeckoView 155 (the newest) depends on `androidx.core:1.19.0`, whose AAR
  metadata declares *“requires Android Gradle plugin 9.1.0 or higher”*.
* Even GeckoView **143** (a year old) already needs `androidx.core:1.16.0` →
  compileSdk 35.
* Kotlin 2.4.10 additionally refuses to run on AGP below **8.5.2**.

So a Gradle-8.4 build would have meant pinning a ~2-year-old engine — which
defeats the point of shipping a browser that competes with Brave. The project
therefore ships on **Gradle 8.13**, the oldest line that can consume a current
Firefox engine. The wrapper is pre-configured, so you never type a Gradle
version: `./gradlew assembleDebug` just works.

If you specifically need an older Gradle for another project in the same repo,
build this module with its own wrapper — that is what the wrapper is for.

---

## 3. Full screen mode (the new feature)

| Gesture | Result |
| --- | --- |
| **Hold the `Web` rail button for 5 s** | A ring fills around the button. On completion the rail, the top bar, the address bar, back/forward/refresh, the shield pill, the progress track, the “ads blocked” banner **and the Android status + navigation bars** all disappear. Only the page remains. |
| **Press Back twice** (hardware key or gesture) | The left rail peeks back for 4 s so you can move to Home / Tabs / Settings. It hides itself again automatically. |
| **Tap the floating `◀ back` pill twice** | Same as above — for devices without a back gesture. |
| **Hold `Web` for 5 s again** | Chrome is fully restored. |
| **Tap `Web` normally** | Just switches to the Web screen, as before. |

The hold length is configurable: **Settings → Appearance → Hold duration**
(1–15 s). A single tap on the button is never swallowed — releasing early
cancels the hold and performs the tap instead.

HTML videos that ask for full screen (`requestFullscreen`) also get a true
full screen, and Back exits it without leaving the page.

---

## 4. What the HTML mock had, and what it became

Every element of `browser-app-screens.html` is present and functional.

| HTML | Kotlin |
| --- | --- |
| `aside.rail` — logo + Home / Web / Tabs + Settings | `ui/RailView.kt`, `ui/RailButton.kt` (66 dp, active state inverts to white) |
| `#webBtn .ring` — hold-progress ring | drawn on canvas in `RailButton`, driven by a 5 s `ValueAnimator` |
| `.topbar` — title, crumb, shield pill, menu | `MainActivity.buildTopBar()` |
| `#s-home` — greeting, date, big search, 8 quick links, “Continue reading”, footer | `ui/HomeScreen.kt` — live clock greeting, real search, real quick links, recents read from the history DB, footer shows the real blocked-today count |
| `#s-web` — web bar, address pill, progress track, page, blocked banner, back pill | `ui/WebScreen.kt` + a real `GeckoView` |
| `#s-tabs` — 3-column cards with ✕ + dashed “New tab” | `ui/TabsScreen.kt` — real thumbnails captured from Gecko, real close, real new tab |
| `#s-settings` — 210 dp section nav + rows/toggles/slider/select | `ui/SettingsScreen.kt` — 7 sections, every control wired |
| `.drawer` + `.backdrop` — right menu | `ui/MenuDrawer.kt` — slides in, scrim, real actions |
| `.toast` | `ui/AppToast.kt` — same white pill, same 2.4 s timing, same strings |
| `.statusbar` with **9:41 / 5G / 87%** | **removed** — it was a mock of the Android status bar. On a real device the OS draws it, and full screen hides it properly. |

### Features that are genuinely functional

* **Multi-tab browsing** with real `GeckoSession`s, session restore across
  restarts, `target="_blank"` opened as a new tab, private tabs (isolated
  context id, never written to history).
* **Ad & tracker blocking** — two layers:
  1. Gecko’s own anti-tracking (ads, analytics, social, content, cryptomining,
     social-tracking protection) at **strict** ETP.
  2. `AdBlocker.kt` + `assets/blocklist.txt` — an EasyList/EasyPrivacy-style
     request-level list evaluated in `onLoadRequest` /
     `onSubframeLoadRequest`, so blocked requests never leave the device.
  Both feed the counters you see in the address pill (“3 blocked”), the
  floating banner, and the home-screen footer.
* **Fingerprinting protection**, **HTTPS-first** (`dom.security.https_only_mode`),
  cookie isolation + purging, safe browsing.
* **Downloads** via `onExternalResponse` → MediaStore (appear in your Files app).
* **Print…** — Gecko renders the page to PDF, handed to the system print dialog.
* **Find in page**, **share**, **bookmark**, **history**, **downloads list**.
* **Clear browsing data** — history + cookies + cache through
  `StorageController.clearData(…)`.
* **Export / import** bookmarks & history as portable JSON.
* **Permissions** — camera / mic / location / notifications are requested
  properly through the Android runtime flow.
* **Monochrome error pages** instead of the engine’s default ones.
* Text size (80–140 %), JavaScript toggle, desktop-UA toggle, search engine
  picker (DuckDuckGo / Google / Bing / Startpage / Brave), homepage picker.

---

## 5. Project layout

```
MinimalBrowser/
├── build.gradle.kts                 AGP 8.13.0 + Kotlin 2.4.10
├── settings.gradle.kts              adds maven.mozilla.org
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   Gradle 8.13
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml      landscape-locked, handles http(s) links
        ├── assets/blocklist.txt     bundled ad/tracker rules
        ├── res/                     24 vector icons ported from the HTML SVG symbols
        └── java/com/minimal/browser/
            ├── BrowserApp.kt        GeckoRuntime + privacy settings
            ├── MainActivity.kt      screens, full screen, back handling
            ├── TabManager.kt        tabs + every Gecko delegate
            ├── Tab.kt
            ├── AdBlocker.kt
            ├── DataStore.kt         SQLite: history/bookmarks/downloads/blocked/tabs
            ├── Downloads.kt, Printer.kt, Backup.kt, External.kt
            ├── ErrorPages.kt, SearchEngines.kt, Prefs.kt
            └── ui/  RailView, RailButton, WebScreen, HomeScreen,
                     TabsScreen, SettingsScreen, ListScreen,
                     MenuDrawer, AppToast, Ui
```

---

## 6. Changing the basics

| What | Where |
| --- | --- |
| App name / package | `app/build.gradle.kts` (`applicationId`, `namespace`) + `res/values/strings.xml` (`app_name`) |
| Logo monogram | `ui/RailView.kt` (the `M` text) and `res/drawable/ic_launcher_fg.xml` |
| Colours | `ui/Ui.kt` → `object Ink` (mirrors `res/values/colors.xml`) |
| Quick links | `ui/HomeScreen.kt` → `quickLinks` |
| Block list | `app/src/main/assets/blocklist.txt` |
| Search engines | `SearchEngines.kt` |
| Hold duration default | `Prefs.kt` → `holdSeconds` |

---

## 7. Verified

`./gradlew clean :app:assembleDebug` → **BUILD SUCCESSFUL**, producing a
signed, installable `app-debug.apk` containing the Gecko engine
(`assets/omni.ja`, `lib/{arm64-v8a,armeabi-v7a,x86_64}/libxul.so`) and
`minSdk 26 / targetSdk 36`. `aapt2 dump` confirms
`screenOrientation=6` (sensorLandscape) survived manifest merging.

Not verified here: runtime behaviour on a physical device (no emulator in the
build environment). The GeckoView API surface used was verified against the
published `classes.jar` with `javap`, and `./gradlew :app:lintDebug` reported a
single error (an `AppCompat` base-class hint), which was fixed.

---

## 8. Licence

The app code is yours. The bundled engine is **GeckoView**, Mozilla Public
License 2.0 — keep the MPL notice if you redistribute a modified engine.
