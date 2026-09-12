# BUILDING MINIMAL BROWSER

How to build both variants and publish the APKs. The version pins in section 3 are not
preferences - each one was reached by hitting a hard wall, and the notes say which.

---

## 1. What the project is

**Minimal Browser** — a landscape-only native Android browser written in Kotlin.
It was converted point-for-point from an HTML design mock into real native code.
There are **two build variants in one repository**, sharing the same UI:

| Folder | Engine | App ID | Size | Status |
|---|---|---|---|---|
| `firefox/` | Mozilla **GeckoView 153** | `com.minimal.browser` | ~530 MB universal / ~160–220 MB per ABI | the main deliverable |
| `lite/` | Android **WebView** | `com.minimal.browser.lite` | ~4.2 MB | verified building, works today |

Different app IDs on purpose so both install side by side.

---

## 2. Where the code is

This repository. Branch `main`. Two independent Gradle projects: `firefox/` and `lite/`.

---

## 3. Exact toolchain — do not substitute versions

These are not preferences. Each was reached by hitting a hard wall; the notes say which.

| Component | Version | Why it is pinned |
|---|---|---|
| JDK | **17** (Temurin) | required by AGP 8.13 |
| Gradle | **8.13** | 8.4 is impossible — see below |
| AGP | **8.13.0** | AGP 8.3.2 caps `compileSdk` at 34; GeckoView needs 35+ |
| Kotlin | **2.4.10** | KGP 2.4.10 refuses AGP < 8.5.2 |
| GeckoView | **`org.mozilla.geckoview:geckoview:153.0.20260810162159`** | see below |
| firefox: compileSdk / targetSdk / minSdk | **36 / 36 / 26** | GeckoView declares minSdk 26 |
| lite: compileSdk / targetSdk / minSdk | **35 / 35 / 24** | |

Maven repos required by `firefox/`: `google()`, `mavenCentral()`, and
`maven { url = uri("https://maven.mozilla.org/maven2") }`. `buildConfig = true` is required.

**Do not use Gradle 8.4.** KGP 2.4.10 rejects AGP below 8.5.2, and GeckoView's transitive
`androidx.core` needs compileSdk 35+, which AGP 8.3.2 cannot provide. It cannot be made to work.

**Do not upgrade GeckoView to 154 or 155.** Those pull `androidx.core:1.19.0`, which hard-requires
AGP 9.1.0 — not available on any stable AGP. Verified against the published `.module` metadata:
143.x→core 1.16.0, 144–149→1.17.0, 150–153→1.18.0, 154–155→1.19.0. **153 is the ceiling.**

---

## 4. Build it

Both modules are independent Gradle projects with committed wrappers.

```bash
git clone https://x-access-token:<TOKEN>@github.com/FAkeUsership/MINIMALBROWSER.git
cd MINIMALBROWSER

# --- Lite first: fast, ~2-4 min, proves the toolchain ---
cd lite && ./gradlew assembleDebug && cd ..

# --- Firefox: the real deliverable. Needs real RAM; allow 30-60 min cold ---
cd firefox && ./gradlew assembleDebug && cd ..
```

**RAM:** the GeckoView build needs a lot. On a 16 GB runner it is fine. On a machine with
~2 GB, set `-Dorg.gradle.jvmargs="-Xmx1100m -XX:MaxMetaspaceSize=384m"` and expect it to be slow.
`e: Daemon compilation failed` means OOM, **not** a code error — lower `-Xmx`, don't chase phantom errors.

**ABI splits** are enabled in `firefox/app/build.gradle.kts` (`arm64-v8a`, `armeabi-v7a`,
`x86_64`, plus `isUniversalApk = true`). That is correct for CI. On a low-RAM box, set
`isEnable = false` to get a single ~530 MB universal APK instead of OOMing while packaging three.

**A ~50–70 MB GeckoView APK is impossible.** `libxul.so` alone is 152 MB (arm64), 117 MB (v7a),
165 MB (x86_64). Per-ABI bottom is ~160–220 MB. Don't try to shrink it below that.

---

## 5. Publish the APKs to a GitHub Release

Release assets allow **2 GB per file**, which is why this is the delivery channel.
(`actions/upload-artifact` counts against the 500 MB free Actions budget — do not use it for Firefox.)

```bash
TAG=v1.0.0
gh release create $TAG \
  firefox/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk \
  firefox/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk \
  firefox/app/build/outputs/apk/debug/app-x86_64-debug.apk \
  firefox/app/build/outputs/apk/debug/app-universal-debug.apk \
  lite/app/build/outputs/apk/debug/app-debug.apk \
  --repo FAkeUsership/MINIMALBROWSER \
  --title "Minimal Browser 1.0.0" \
  --notes "arm64-v8a = recommended for most phones. universal = works everywhere, 530 MB."
```

Verify afterwards with `apksigner verify --print-certs` and `aapt2 dump badging`.
Expected: `com.minimal.browser` / `com.minimal.browser.lite`, `versionName 1.0.0`,
`screenOrientation=6` (landscape). These are debug-signed; say so in the release notes.

---

## 6. The one blocked step — read this

`.github/workflows/build.yml` exists in the repo's working copy **but was never pushed.**
GitHub rejected it twice:

```
git push   → remote rejected (refusing to allow a Personal Access Token to create or
             update workflow `.github/workflows/build.yml` without `workflow` scope)
REST API   → HTTP 404 (GitHub masks workflow-scope denial as Not Found)
```

Any file under `.github/workflows/` needs the **`workflow`** token scope. The current token has
only `repo`. **To unblock: use a classic PAT with BOTH `repo` and `workflow` ticked**, then:

```bash
curl -X PUT -H "Authorization: Bearer <TOKEN_WITH_WORKFLOW_SCOPE>" \
  https://api.github.com/repos/FAkeUsership/MINIMALBROWSER/contents/.github/workflows/build.yml \
  -d "{\"message\":\"Add CI\",\"content\":\"$(base64 -w0 build.yml)\"}"
```

That workflow already does the whole job unattended: push to `main` builds Lite; pushing a
`v*` tag builds **both** and publishes every APK to a Release via `softprops/action-gh-release@v2`.
So once it lands, tagging `v1.0.0` finishes the task with no further work.

If no `workflow` scope can be obtained, skip CI entirely and use section 4 + 5 manually.

---

## 7. Known traps — each of these already cost a failed build

1. **`WebViewClient.ERROR_UNKNOWN_SCHEME` does not exist.** Neither does `ERROR_UNKNOWN_URL_SCHEME`.
   Verified with `javap -constants` on `android.jar`: the real constant is
   **`ERROR_UNSUPPORTED_SCHEME = -10`**. Never guess framework constant names — `javap` the platform jar.
2. **Changing an Android `namespace` moves generated `R` and `BuildConfig`.** `lite/` was renamed to
   `com.minimal.browser.lite`. This silently breaks `import` lines *and* fully-qualified references
   like `com.minimal.browser.R.drawable.foo`. Grep for the old namespace before trusting a build.
3. **`android.kotlinOptions` was removed in Kotlin 2.4.** Use
   `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }`.
4. **`gradle-wrapper.jar` must be committed.** Both are present (43,705 bytes each) with mode `100755`.
   Do not let a `.gitignore` drop them.
5. **Never filter Gradle output through `grep` while debugging.** It hides real `e:` errors behind a
   bare `FAILURE`. Use `tail -N` or unfiltered output.
6. GeckoView API gaps, confirmed by `javap`: no `setHttpsOnlyMode` (use the pref
   `dom.security.https_only_mode=true` via `GeckoRuntimeSettings.Builder().arguments(...)`);
   `ContentBlockingController` has no `setFilterRules`; `GeckoSessionSettings.setContextId` is
   **private**, so private tabs need reflection.

---

## 8. Product requirements — do not drop these

- **Landscape only.** `screenOrientation=6` in the manifest.
- **Full-screen mode:** hold the Web rail button **5 seconds** → the page goes full screen and every
  back button, top bar, nav and status bar disappears. Controls return **only** after back is pressed
  **twice** (500 ms double-back window). A single back does nothing.
- **No fake status bar.** The original mock drew a fake `9:41` / wifi / `5G` / `87%` battery. It must
  stay removed — the app uses the real system bar.
- **Feature parity with the HTML mock:** rail (66dp, 38dp white `M` logo), 52dp top bar, home screen
  with search + 4×2 quick links + "CONTINUE READING" + blocked-today footer, tab grid (3 columns,
  110dp thumbnails), settings (210dp nav, 7 panels), right-side 330dp drawer, white pill toasts.
- **Competitive with Brave:** ad blocking, private tabs, downloads, find-in-page, desktop UA,
  print, session restore, HTTPS-only, fingerprinting resistance (Firefox build only).

**Disclosed regressions in `lite/`** (WebView cannot do these): no true private session, no
fingerprinting resistance, HTTPS-only maps only to a mixed-content policy, rendering and sandboxing
follow the device WebView, camera/mic page requests are denied. These are engine limits, not bugs.

---

## 9. Definition of done

1. `lite` builds → `app-debug.apk` ~4.2 MB, `com.minimal.browser.lite`.
2. `firefox` builds → per-ABI APKs ~160–220 MB + `app-universal-debug.apk` ~530 MB.
3. All APKs attached to Release **v1.0.0** on `FAkeUsership/MINIMALBROWSER`.
4. `apksigner verify` passes on each; `aapt2 dump badging` shows landscape and the right app ID.
5. The token is revoked.
