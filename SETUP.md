# One-time setup

This repo is ready to push. Two things must exist on GitHub first:

1. A repository (e.g. `MINIMALBROWSER`). It can be empty — no README, no
   licence, no .gitignore, because those would make the first push conflict.
2. A **working** personal access token with `repo` scope (a classic token), or
   a fine-grained token with Contents: read/write and Actions: read/write on
   that one repository.

Then:

```bash
cd repo
git init -b main
git add -A
git commit -m "Minimal browser: Firefox (GeckoView) and Lite (WebView) builds"
git remote add origin https://<TOKEN>@github.com/<USER>/MINIMALBROWSER.git
git push -u origin main

# publish APKs to the Releases page
git tag v1.0.0
git push origin v1.0.0
```

Watch the run under the repo's **Actions** tab. When it goes green, the APKs
are on the **Releases** page.

## Note on the Gradle wrapper

`gradlew` and `gradle-wrapper.properties` are committed, but
`gradle/wrapper/gradle-wrapper.jar` is not (it is a binary). CI therefore uses
`gradle/actions/setup-gradle` and calls `./gradlew` — if your local Gradle
complains about the missing jar, run once with a local Gradle:

```bash
cd lite && gradle wrapper --gradle-version 8.13
```

…or just build in CI and download the APK.
