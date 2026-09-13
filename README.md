# Minimal Browser

**Minimal Browser** is a native landscape Android browser written in Kotlin. It ships Mozilla **GeckoView** and its native **ARM64** browser engine inside the APK; it does **not** delegate page rendering to Android System WebView.

## What this repair includes

- A process-wide Gecko runtime created only when Web/navigation is first needed.
- One deferred, initialized-default high-performance `SurfaceView`-backed visible `GeckoView`; Home, Tabs, Settings, and hidden screens never own a compositor surface.
- Gecko delegates installed before each `GeckoSession` opens, URL-only ordinary-tab restoration, and replacement/reload of a crashed or killed renderer session.
- A working three-dot side menu with New tab, **New private (incognito) tab**, Tabs, History, bookmarks, Downloads, Find in page, shields, theme, sharing, printing, and Settings. OAuth/`target=_blank` child windows are surfaced as foreground tabs, and JavaScript/FedCM/WebAuthn/redirect prompts are explicitly presented rather than silently dismissed.
- Real download confirmation and handling: a Gecko-provided file stream is copied to Android Downloads, shown as an in-progress/complete entry, and a completed entry can be opened in a matching app. A download is listed only after it has actually been published; private-tab downloads are saved by Android but not retained in the browser list.
- A responsive native start surface for an empty Web tab rather than a blank white Gecko page. The omnibox starts empty and preserves the full URL when edited.
- USB/Bluetooth keyboard-aware input: native fields do not force the Android software keyboard while hardware input is attached; physical Enter and numpad Enter submit Home/omnibox entries; standard browser shortcuts work without swallowing ordinary page typing. A focused Gecko page field keeps its physical-keyboard focus when the shell dismisses an unwanted software IME.
- Normal mouse click, context-click, and scroll-wheel delivery stays with Gecko, while mouse Back/Forward side buttons navigate browser history.
- A fixed **5-second** hold on the Web rail button for clean page-only mode. It hides app chrome and Android system bars; one Android Back gesture/button or a page double-tap restores them.
- Normal Android system bars on ordinary screens by default; **Settings → Appearance → Hide Android status bar** optionally hides only the top system bar. Predictive Back is enabled on current Android versions.
- An ARM64-only release APK that intentionally remains large because it includes `libxul.so` and Gecko companion libraries.

## Release and verification path

Releases are built **only on GitHub Actions** through [`.github/workflows/build.yml`](.github/workflows/build.yml). The workflow:

1. assembles the signed, non-debuggable `release` variant on a GitHub runner;
2. validates the generated APK signature, package ID, version, and lack of Android's debug-process marker;
3. validates ARM64-only native code, `lib/arm64-v8a/libxul.so`, and install-time native-library extraction in the built APK;
4. confirms the bundled-engine artifact is substantial rather than a tiny System WebView shell;
5. publishes `MinimalBrowser-arm64-v8a-release.apk` and `SHA256SUMS.txt` to the matching GitHub Release.

See [BUILD.md](BUILD.md) for the tag-based release procedure and [minimal-browser/README.md](minimal-browser/README.md) for project details.

## Device validation

A successful GitHub workflow verifies source compilation, packaging, and release artifacts. It is not a substitute for live-device validation. On an Android 14+ ARM64 device, validate:

1. Open the three-dot menu: verify its full right-side panel, New private tab, History, Bookmarks, Downloads, Find in page, and Settings actions;
2. Home → Mail and YouTube quick links, plus the blank-tab start/search surface;
3. Web → address-bar URL and search navigation;
4. real USB and Bluetooth keyboard/mouse behavior: no Android software keyboard after focusing a native or page field, physical typing still reaches that field, Enter/numpad Enter submit Home/omnibox once, shortcut chords work, and mouse click/context-click/wheel/Back/Forward work naturally;
5. a Google/Arena sign-in or consent flow, including any prompt and new foreground tab;
6. tab creation, switching, closing, private-tab indicator, and Android Back;
7. a real download: approve its confirmation, verify it appears in Android Downloads and the app list, then open it from the app list;
8. hold Web for exactly five seconds, then verify one Back and page double-tap restore;
9. Settings → Appearance → Hide Android status bar, then restore it;
10. scrolling, navigation, tab switching, and device heat/stutter behavior on ordinary and tracker-heavy pages;
11. print flow.
