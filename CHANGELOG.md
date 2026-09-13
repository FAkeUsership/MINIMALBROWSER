# Changelog

## v1.0.3 — Android 14+ GeckoView startup repair

- Configures every `GeckoSession` delegate, including `ContentDelegate`, **before** calling `GeckoSession.open()`. This follows GeckoView’s documented workaround for Bug 1758212 and replaces the unsafe open-then-bind order.
- Uses the safe visible-surface sequence: configure session → open session → attach the visible `GeckoView`. The renderer is released while Home, Tabs, and Settings are shown rather than remaining attached to a hidden view.
- Contains session-open, session-restore, session-load, and compositor-release failures so an engine problem leaves the Minimal Browser UI usable instead of crashing the Activity.
- Keeps the requested page-only behavior: hold **Web** for exactly five seconds; Android Back or a page double-tap restores normal controls and system bars.

## v1.0.2

- Minimal Browser branding, page-only mode, and GitHub Actions APK/release workflow.
