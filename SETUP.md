# Minimal Browser repository setup

GitHub Actions in [`.github/workflows/build.yml`](.github/workflows/build.yml) builds and publishes **Minimal Browser** releases. It is the designated build path for the bundled ARM64 GeckoView APK.

## Release permission

The workflow declares `contents: write`, allowing the repository’s default `GITHUB_TOKEN` to create or update a GitHub Release and upload APK assets. In repository settings, ensure Actions workflow tokens have **Read and write permissions**.

## Publishing

Push a new matching version tag, for example `v1.2.6`. The workflow builds a signed non-debuggable release variant of the bundled GeckoView ARM64 APK on GitHub, verifies package contents, signature, and checksum, and creates the matching release. Do not overwrite a prior version tag/release for a new version.

If changing the workflow through the GitHub API or with a classic personal access token, the token needs both:

- `repo`
- `workflow`

Generate tokens only when needed, keep them out of chat, logs, screenshots, commits, and files, and revoke any token that may have been exposed.
