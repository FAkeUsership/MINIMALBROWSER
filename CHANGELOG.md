# Changelog

## v1.1.0 — System WebView stability rewrite

- Replaces the bundled native browser renderer with Android System WebView throughout the tab, navigation, download, print, data-clearing, lifecycle, and recovery paths.
- Creates WebViews lazily at tab creation, so Home, Tabs, and Settings remain usable if the device WebView provider is unavailable; failures are contained rather than crashing the Activity.
- Preserves a real WebView per tab, attaches only the active tab to the Web screen, and prevents background page callbacks from attaching the wrong page.
- Handles a killed WebView renderer by replacing and reloading only its affected tab.
- Keeps Home quick links and ordinary web navigation inside the browser, while correctly routing `mailto:`, `tel:`, `geo:`, `market:`, and `intent:` links to Android handlers/fallbacks.
- Preserves the required page-only flow: hold **Web** for exactly five seconds; Android Back or a page double-tap restores browser controls and system bars.
- Removes native-engine dependencies, extraction settings, ABI split assumptions, and split-APK release assets. GitHub Actions now publishes one universal System WebView APK plus `SHA256SUMS.txt`.

## v1.0.4 — Native packaging attempt

- Changed native-library extraction packaging in an attempt to address the previous renderer startup failures.
- Superseded by v1.1.0’s System WebView migration.

## v1.0.3 — Renderer startup attempt

- Added defensive renderer/session startup ordering and visible-surface handling.
- Superseded by v1.1.0’s System WebView migration.

## v1.0.2

- Minimal Browser branding, page-only mode, and GitHub Actions APK/release workflow.
