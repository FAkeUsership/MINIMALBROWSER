# Build and release Minimal Browser

The only supported release build path is **GitHub Actions** in [`.github/workflows/build.yml`](.github/workflows/build.yml). Do not use a local Android/Gradle build to publish an APK.

## Publish v1.2.4

1. Confirm `versionCode` and `versionName` in `minimal-browser/app/build.gradle.kts`.
2. Commit and push the repair.
3. Push a new, unused `v*` tag that matches the Android version:

   ```bash
   git tag v1.2.4
   git push origin v1.2.4
   ```

4. Open **Actions** and wait for **Build Minimal Browser** to finish successfully.
5. Download `MinimalBrowser-arm64-v8a-release.apk` and `SHA256SUMS.txt` from the matching GitHub Release.

The workflow can also be started manually from Actions with a new tag beginning with `v`. Leave **Publish release assets** enabled to publish; disable it for a GitHub-hosted build/verification-only validation run.

## What GitHub Actions verifies

1. Java 17, Android API 36, and build-tools 36.0.0 are present on the GitHub runner.
2. `assembleRelease` produces a signed APK.
3. The generated APK has application ID `com.minimal.browser`, version `1.2.4`, ARM64 native code, and no `application-debuggable` marker.
4. The generated archive contains Gecko's `lib/arm64-v8a/libxul.so` and contains no other ABI directory.
5. The generated manifest requests install-time native-library extraction.
6. The generated package exceeds 80 MB, proving it is the requested bundled-engine APK rather than a small System WebView wrapper.
7. The published APK has a SHA-256 checksum in `SHA256SUMS.txt`.

## Verify a downloaded asset

```bash
sha256sum -c SHA256SUMS.txt
```

The APK is an `assembleRelease` non-debuggable process, but is signed with Android’s standard debug certificate on the disposable GitHub runner because this repository has no persistent private release keystore. The generated certificate is not a durable update identity, so Android may require uninstalling an older differently signed Minimal Browser build before installation; export any data first. It targets Android 8.0+ (`minSdk 26`) and intentionally carries Mozilla GeckoView's ARM64 native engine. A stable private signing key stored as GitHub secrets is required for seamless updates across releases. A successful workflow proves build, packaging, signing, and release plumbing; it does **not** prove live-device behavior. Validate Home links; Web navigation/scrolling; a Google/Arena login or consent handoff, including any prompt and foreground tab; tabs; the precise 5-second page-only gesture; one-Back and page-double-tap restore; downloads; printing; and heat/jank behavior on an Android 14+ ARM64 device after installation.
