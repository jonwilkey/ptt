# 0005. release-please calls server-release directly instead of relying on the tag push

- Status: Accepted
- Date: 2026-10-24

## Context

The spec's Workflows table says `server-release` runs on the tag `server-v*`, and that
release-please creates those tags. release-please runs with the workflow's `GITHUB_TOKEN`, and
GitHub does not start workflows for events caused by that token (other than `workflow_dispatch`
and `repository_dispatch`): a tag or release it creates triggers nothing. Read literally, the
pipeline would stop after the GitHub release is created.

The usual fixes are a personal access token or a GitHub App token for release-please. A personal
token is tied to one admin's account, has broad scope, and needs rotating; an App is another
credential (app ID and private key) for two admins to manage.

The same limitation means release PRs, which release-please opens and updates with
`GITHUB_TOKEN`, do not start the `ci` workflow on their own.

## Decision

- `release-please.yml` runs on every push to `main`. When it creates a server release, a second
  job in the same run calls `server-release.yml` as a reusable workflow (`workflow_call`) with the
  new tag. No extra credential is needed.
- `server-release.yml` keeps `push: tags: ['server-v*']` (for a tag pushed by an admin) and adds
  `workflow_dispatch` with a `tag` input (to rebuild and redeploy an existing release). All three
  entry points run the same jobs; the job checks out the tag in every case.
- The image signature identity therefore differs by entry point
  (`.../server-release.yml@refs/heads/main` for the release-please and manual runs,
  `.../server-release.yml@refs/tags/server-v...` for a pushed tag). Verification accepts exactly
  those two forms.
- Release PRs: before merging one, an admin closes and reopens it (an event by a person), which
  starts `ci` so the required checks report. This is documented in
  `docs/runbooks/github-setup.md`.

## Consequences

- No personal access token or GitHub App to create, store, or rotate.
- The `production` environment must allow deployments from `main` (release-please and manual
  runs) and from `server-v*` tags (pushed tags).
- The calling job in `release-please.yml` must grant every permission `server-release.yml` jobs
  use (`packages: write`, `id-token: write`), and passes secrets with `secrets: inherit`.
- Closing and reopening each release PR is a small manual step. If it becomes annoying, a GitHub
  App token for release-please removes it; then the tag push would start `server-release` by
  itself and the `workflow_call` job must be removed in the same change, or every release would
  run twice.
