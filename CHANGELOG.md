# Changelog

## v1.2.0 — Bundled GeckoView ARM64 reconstruction

- Restores Mozilla GeckoView as Minimal Browser’s bundled ARM64 browser engine; no Android System WebView renderer is used.
- Rebuilds tab/session ownership around one process-wide lazy runtime, delegates-before-open, and one deferred texture-backed visible GeckoView.
- Releases a session before hiding/removing its surface, avoids competing GeckoDisplay acquisition during thumbnail capture, and recovers only a renderer-crashed/killed session by replacing and reloading it.
- Fixes target-window tab creation to return an unopened GeckoSession as required by GeckoView.
- Fixes short presses on the Web rail button: a normal tap opens Web, while only a completed exact 5-second hold enters clean page-only mode; late progress/shield updates cannot draw chrome over that page-only view.
- Defers background restored-tab network loads until each tab is selected, avoiding a burst of hidden page startups at engine launch.
- Uses Gecko’s `saveAsPdf()` for normal web-page printing and moves PDF stream/write operations off the UI thread.
- Keeps Home links (including Mail and YouTube), navigation/search, tabs, downloads, history, external URI routing, and privacy settings on the bundled-engine path.
- Restores install-time native-library extraction and ARM64-only APK packaging; GitHub Actions validates the generated `libxul.so`, ABI, manifest extraction setting, artifact size, and SHA-256 checksum before publishing.

## v1.1.0 — System WebView stability rewrite

- Historical release that temporarily replaced the bundled native renderer with Android System WebView.
- Superseded by v1.2.0’s bundled GeckoView reconstruction.

## v1.0.4 — Native packaging attempt

- Changed native-library extraction packaging in an attempt to address previous renderer startup failures.
- Superseded by v1.2.0’s complete bundled-engine reconstruction.

## v1.0.3 — Renderer startup attempt

- Added defensive renderer/session startup ordering and visible-surface handling.
- Superseded by v1.2.0’s complete bundled-engine reconstruction.

## v1.0.2

- Minimal Browser branding, page-only mode, and GitHub Actions APK/release workflow.
