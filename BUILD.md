# GitHub build and release

The repository contains one Android project: [`minimal-browser/`](minimal-browser/). The GitHub Actions workflow is the supported build path.

## Release build

Push a `v*` tag:

```bash
git tag v1.0.2
git push origin v1.0.2
```

The workflow will:

1. install Java 17 and Android API 36/build-tools 36.0.0;
2. keep the Android SDK directory intact (do **not** delete `$ANDROID_HOME`);
3. run `./gradlew assembleDebug` inside `minimal-browser/`;
4. verify package metadata with `aapt2`;
5. create/update the matching GitHub Release and upload arm64-v8a, armeabi-v7a, x86_64, and universal debug APKs.

Use the `workflow_dispatch` option in Actions if you prefer to enter a release tag manually.

## Expected release assets

- `MinimalBrowser-arm64-v8a-debug.apk`
- `MinimalBrowser-armeabi-v7a-debug.apk`
- `MinimalBrowser-x86_64-debug.apk`
- `MinimalBrowser-universal-debug.apk`

Install the arm64-v8a build on nearly every modern Android device. The APKs are debug-signed and have app ID `com.minimal.browser`.
