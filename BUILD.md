# Build and release Minimal Browser

The supported build path is **GitHub Actions** in [`.github/workflows/build.yml`](.github/workflows/build.yml). Do not rely on a local Android build for a release.

## Publish a release

1. Confirm the Android version in `minimal-browser/app/build.gradle.kts`.
2. Commit and push the repair.
3. Push a new, unused `v*` tag:

   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

4. Open the repository’s **Actions** page and wait for **Build Minimal Browser** to succeed.
5. Open the matching GitHub Release and download `MinimalBrowser-android-debug.apk`.

You can also start the workflow manually from Actions and enter a new tag beginning with `v`.

## What the workflow does

1. checks out the tagged source;
2. installs Java 17, Android API 36, and build-tools 36.0.0;
3. retains the Android SDK rather than deleting `$ANDROID_HOME`;
4. runs `assembleDebug` on the GitHub runner;
5. uses `aapt2` to verify the `com.minimal.browser` package in the generated APK;
6. verifies the APK does not contain the retired bundled native browser library;
7. uploads one universal APK and `SHA256SUMS.txt` to the corresponding GitHub Release.

## Expected release assets

- `MinimalBrowser-android-debug.apk`
- `SHA256SUMS.txt`

Verify a downloaded asset with:

```bash
sha256sum -c SHA256SUMS.txt
```

The APK is debug-signed and targets Android 8.0+ (`minSdk 26`). It uses the device’s Android System WebView provider, so a device with a disabled or obsolete provider should update Android System WebView or Chrome before browsing.

A successful workflow verifies the generated artifact and release plumbing. It does **not** prove real-device behavior; validate Web, Home links, tabs, the 5-second page-only gesture, double-tap restore, and Android Back on the target Android device after installation.
