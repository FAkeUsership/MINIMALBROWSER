# Changelog

## v1.2.4 — Signed non-debuggable release variant and chrome I/O pass

- Publishes the first `assembleRelease` ARM64 APK: the installed process is non-debuggable and JNI-debugging is off. GitHub creates a standard Android debug certificate only in its disposable runner so the release variant remains sideloadable; its new per-run certificate may require uninstalling an older differently signed build. A persistent private release certificate is still needed for seamless update signing.
- GitHub Actions now verifies the generated APK signature and asserts that `application-debuggable` is absent, in addition to package/version, ARM64-only native code, `libxul.so`, native extraction, archive integrity, size, and SHA-256.
- Keeps GeckoView 153’s initialized default `SurfaceView` rather than resetting its backend, preserving the v1.2.3 black-screen correction and direct-surface rendering path.
- Removes synchronous SQLite bookmark checks from browser/navigation chrome and replaces ad-hoc chrome threads with one low-priority reusable worker. The drawer uses a request-checked cache so stale page callbacks cannot overwrite its bookmark state.
- Makes page-only Back deterministic: page-only now takes precedence over any stale drawer state, and an exit asks Gecko to leave content full-screen even if its asynchronous callback has not reached the shell yet. This keeps one Android Back focused on restoring browser controls and ordinary system bars.
- Repairs opaque web sign-in/consent handoffs. A `target=_blank`/`window.open` child session is now foregrounded only after Gecko opens it; the old code created the child but left it in the background. A small PromptDelegate now presents JavaScript confirmation/input, form-choice, safe redirect/popup, WebAuthn-related-origin, and FedCM provider/account/consent prompts instead of GeckoView’s default silent dismissal.
- Uses GeckoView’s default ETP level rather than the more breakage-prone strict content list; local ad/tracker filtering, safe browsing, cookie isolation, cryptomining/fingerprinting protection, and the shields switch remain. Cross-site sign-in storage is now explicitly approved or blocked by the user, including a chance to retry a denial inherited from earlier builds.
- Avoids parsing every blocked resource URL merely for an unused per-resource host field and removes unused blocked-total callbacks; shield persistence now uses the current page host once per short batch. Existing screenshot removal, rule indexing, progress coalescing, remote-debug disablement, and inactive-tab suspension remain in place.

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
