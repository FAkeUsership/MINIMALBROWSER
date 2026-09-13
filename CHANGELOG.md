# Changelog

## v1.2.2 — Page-only Back and rendering/CPU optimization

- Fixes the Android 14+ page-only exit path: page-only mode now uses Android’s non-sticky default inset behavior, keeps predictive Back enabled, and gives app page-only state priority over an incidental web-video full-screen state. One Back gesture/button restores browser chrome and normal system bars; the page double-tap exit remains available.
- Replaces GeckoView’s lower-performance `TextureView` backend with its documented high-performance `SurfaceView` backend while preserving the one-visible-surface lifecycle and page-only touch observation.
- Removes automatic full-frame `capturePixels()` tab screenshots on page load, tab switches, and the Tabs screen. Tab cards retain lightweight preview artwork without forcing GPU readback/scaling/allocation work into browsing interactions.
- Coalesces a burst of blocked-resource events into one short-delayed UI update and one transactional database batch, uses one low-priority database worker instead of creating a thread per write, and avoids persisting blocked-request records from private tabs.
- Indexes host-suffix ad/tracker rules and shares normalized URL checks, reducing repeated per-resource rule scans; coalesces browser progress-line layouts to one update per display frame.
- Disables Gecko remote debugging, removes the app-wide keep-screen-on flag, and marks the GitHub-built end-user process non-debuggable; the workflow asserts that generated-APK property in addition to package, version, ARM64 `libxul.so`, extraction, size, and checksum checks.

## v1.2.1 — Bundled GeckoView ARM64 reconstruction

- Restores Mozilla GeckoView as Minimal Browser’s bundled ARM64 browser engine; no Android System WebView renderer is used.
- Rebuilds tab/session ownership around one process-wide lazy runtime, delegates-before-open, and one deferred texture-backed visible GeckoView.
- Releases a session before hiding/removing its surface, avoids competing GeckoDisplay acquisition during thumbnail capture, and recovers only a renderer-crashed/killed session by replacing and reloading it.
- Fixes target-window tab creation to return an unopened GeckoSession as required by GeckoView.
- Fixes short presses on the Web rail button: a normal tap opens Web, while only a completed exact 5-second hold enters clean page-only mode; late progress/shield updates cannot draw chrome over that page-only view.
- Defers background restored-tab network loads until each tab is selected, avoiding a burst of hidden page startups at engine launch.
- Uses Gecko’s `saveAsPdf()` for normal web-page printing and moves PDF stream/write operations off the UI thread.
- Keeps Home links (including Mail and YouTube), navigation/search, tabs, downloads, history, external URI routing, and privacy settings on the bundled-engine path.
- Restores install-time native-library extraction and ARM64-only APK packaging; GitHub Actions validates the generated `libxul.so`, ABI, manifest extraction setting, artifact size, and SHA-256 checksum before publishing.

## v1.2.0 — Withheld before release

- GitHub Actions caught a Kotlin tab-permission delegate construction error before artifact verification or release publishing, so no v1.2.0 APK was released. The binding is corrected in v1.2.1.

## v1.1.0 — System WebView stability rewrite

- Historical release that temporarily replaced the bundled native renderer with Android System WebView.
- Superseded by v1.2.1’s bundled GeckoView reconstruction.

## v1.0.4 — Native packaging attempt

- Changed native-library extraction packaging in an attempt to address previous renderer startup failures.
- Superseded by v1.2.1’s complete bundled-engine reconstruction.

## v1.0.3 — Renderer startup attempt

- Added defensive renderer/session startup ordering and visible-surface handling.
- Superseded by v1.2.1’s complete bundled-engine reconstruction.

## v1.0.2

- Minimal Browser branding, page-only mode, and GitHub Actions APK/release workflow.
