# Build and release Minimal Browser

The only supported release build path is **GitHub Actions** in [`.github/workflows/build.yml`](.github/workflows/build.yml). Do not use a local Android/Gradle build to publish an APK.

## Publish v1.2.2

1. Confirm `versionCode` and `versionName` in `minimal-browser/app/build.gradle.kts`.
2. Commit and push the repair.
3. Push a new, unused `v*` tag that matches the Android version:

   ```bash
   git tag v1.2.2
   git push origin v1.2.2
   ```

4. Open **Actions** and wait for **Build Minimal Browser** to finish successfully.
5. Download `MinimalBrowser-arm64-v8a-debug.apk` and `SHA256SUMS.txt` from the matching GitHub Release.

The workflow can also be started manually from Actions with a new tag beginning with `v`.

## What GitHub Actions verifies

1. Java 17, Android API 36, and build-tools 36.0.0 are present on the GitHub runner.
2. `assembleDebug` produces the APK.
3. The generated APK has application ID `com.minimal.browser`, version `1.2.2`, ARM64 native code, and is not Android-debuggable.
4. The generated archive contains Gecko's `lib/arm64-v8a/libxul.so` and contains no other ABI directory.
5. The generated manifest requests install-time native-library extraction.
6. The generated package exceeds 80 MB, proving it is the requested bundled-engine APK rather than a small System WebView wrapper.
7. The published APK has a SHA-256 checksum in `SHA256SUMS.txt`.

## Verify a downloaded asset

```bash
sha256sum -c SHA256SUMS.txt
```

The APK is debug-signed for GitHub-only distribution but is explicitly **not Android-debuggable** at runtime, and targets Android 8.0+ (`minSdk 26`). It intentionally carries Mozilla GeckoView's ARM64 native engine. A successful workflow proves build, packaging, and release plumbing; it does **not** prove live-device behavior. Validate Home links, Web navigation/scrolling, tabs, the precise 5-second page-only gesture, one-Back restore, page double-tap restore, downloads, printing, and heat/jank behavior on an Android 14+ ARM64 device after installation.
