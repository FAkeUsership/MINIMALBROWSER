# Repository setup

GitHub Actions is already configured in `.github/workflows/build.yml`.

To publish a build, push a version tag such as `v1.0.4`; the workflow builds **Minimal Browser** and creates the matching GitHub Release. The workflow needs the repository's default `GITHUB_TOKEN` permission `contents: write`, which is declared in the workflow.

If you update the workflow file through the GitHub API or git using a classic Personal Access Token, that token must include both:

- `repo`
- `workflow`

Use a newly generated token, keep it private, and revoke tokens that have been exposed in chat, logs, screenshots, or commits.
