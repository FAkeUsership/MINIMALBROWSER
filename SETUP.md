# Minimal Browser repository setup

GitHub Actions is configured in [`.github/workflows/build.yml`](.github/workflows/build.yml) to build and publish Minimal Browser releases.

## Release permission

The workflow declares `contents: write`, allowing the repository’s default `GITHUB_TOKEN` to create or update a GitHub Release and upload its APK assets. In repository settings, ensure Actions is allowed **Read and write permissions** for workflow tokens.

## Publishing

Push a new version tag such as `v1.1.0`. The workflow builds the APK on GitHub, verifies the artifact, and creates the matching release. Existing version tags/releases must not be overwritten for a new release.

If changing the workflow through the GitHub API or with a classic personal access token, the token needs both:

- `repo`
- `workflow`

Generate tokens only when needed, keep them out of chat, logs, screenshots, commits, and files, and revoke any token that may have been exposed.
