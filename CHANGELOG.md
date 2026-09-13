# Changelog

## v1.2.3 — Surface lifecycle black-screen correction

- Corrects the v1.2.2 black-screen regression. GeckoView 153 already creates and listens to its default high-performance `SurfaceView`; calling `setViewBackend(BACKEND_SURFACE_VIEW)` again replaced that initialized surface without re-registering its holder callback. v1.2.3 retains the initialized default SurfaceView instead, so Gecko receives the compositor surface callback.
- Restores the field-proven debug APK packaging profile so the build does not make an unrelated large packaging transition. Gecko remote debugging remains explicitly disabled.
- Retains the Android 14+ page-only Back fix: non-sticky default inset behavior, predictive Back support, and app page-only priority over incidental web-video full screen. One Back gesture/button restores controls and normal bars; page double-tap remains available.
- Retains the performance work that does not alter compositor initialization: no automatic `capturePixels()` tab screenshots, batched shield UI/database work on one worker, compact blocked-count storage, indexed host rules, frame-coalesced progress updates, and no app-wide keep-screen-on flag.

## v1.2.2 — Withdrawn after device validation

- Published briefly, then found on a physical Android device to present a black browser surface. Do not install this version; use v1.2.3 or later.
- The bundled ARM64 Gecko engine was present and GitHub packaging checks passed, but the redundant SurfaceView backend reset was a runtime compositor-lifecycle defect that artifact checks alone could not detect.

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
