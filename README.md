# Minimal Browser

**Minimal Browser** is a native landscape Android browser written in Kotlin. It ships Mozilla **GeckoView** and its native **ARM64** browser engine inside the APK; it does **not** delegate page rendering to Android System WebView.

## What this repair includes

- A process-wide Gecko runtime created only when Web/navigation is first needed.
- One deferred, texture-backed visible `GeckoView`; Home, Tabs, Settings, and hidden screens never own a compositor surface.
- Gecko delegates installed before each `GeckoSession` opens, URL-only ordinary-tab restoration, and replacement/reload of a crashed or killed renderer session.
- Working Home quick links including Mail and YouTube, address-bar search/navigation, external URI routing, tab switching, history, downloads, printing, and private tabs.
- A fixed **5-second** hold on the Web rail button for clean page-only immersive mode. It hides app chrome and Android system bars; Android Back or a page double-tap restores them.
- Normal Android system bars on all ordinary screens.
- An ARM64-only release APK that intentionally remains large because it includes `libxul.so` and Gecko companion libraries.

## Release and verification path

Releases are built **only on GitHub Actions** through [`.github/workflows/build.yml`](.github/workflows/build.yml). The workflow:

1. assembles `minimal-browser` on a GitHub runner;
2. validates the generated package ID and version;
3. validates ARM64-only native code, `lib/arm64-v8a/libxul.so`, and install-time native-library extraction in the built APK;
4. confirms the bundled-engine artifact is substantial rather than a tiny System WebView shell;
5. publishes `MinimalBrowser-arm64-v8a-debug.apk` and `SHA256SUMS.txt` to the matching GitHub Release.

See [BUILD.md](BUILD.md) for the tag-based release procedure and [minimal-browser/README.md](minimal-browser/README.md) for project details.

## Device validation

A successful GitHub workflow verifies source compilation, packaging, and release artifacts. It is not a substitute for live-device validation. On an Android 14+ ARM64 device, validate:

1. Home → Mail and YouTube quick links;
2. Web → address-bar URL and search navigation;
3. tab creation, switching, closing, and Android Back;
4. hold Web for exactly five seconds, then Back and page double-tap restore;
5. download and print flows.
