# 0003. Public repository

- Status: Accepted
- Date: 2026-10-24

## Context

The spec's "Repository rules" (under CI/CD and updates) say the project lives in a private GitHub
repo. The pipeline depends on two GitHub features: required reviewers on deployment environments
(so either admin approves production deploys, and the protected `release` environment that
holds the APK signing keystore needs approval) and branch protection on `main`. On private
repositories those features are not available on GitHub Free: branch protection needs GitHub
Pro or Team, and required reviewers on environments need GitHub Enterprise. On public
repositories both are free.

The spec also gives a private repo as the reason the APK is served by our own server: a private
repo's GitHub Releases need a login to download.

## Decision

- The repository is public, under the MIT license.
- Nothing personal or deployment-specific is committed: no names, email addresses, the real
  domain, account IDs, IP addresses or keys. Docs and examples use `example.com` placeholders;
  real values live in GitHub Actions variables and secrets and in gitignored local files.
- This is enforced by GitHub secret scanning with push protection, by local git hooks
  (`scripts/setup-hooks.sh`: gitleaks, a private personal-term denylist kept outside the repo,
  and GitHub noreply commit emails), and by a CI gitleaks job in the `ci` workflow (milestone
  1, in the PR that adds `ci.yml`).
- GitHub Actions hardening:
  - workflows never use `pull_request_target`;
  - workflow runs from outside contributors require approval;
  - the AWS OIDC trust policies are scoped to this repository's branches and environments;
  - no self-hosted runners.
- APK distribution stays on our server (`/v1/app/apk/<version>`, device-token authenticated);
  only the reason changes, see Consequences.

## Consequences

- Anyone can read the code. The design never relied on its secrecy: audio is end-to-end
  encrypted, devices enroll only with one-time codes from an admin's phone, and the domain is
  public through Certificate Transparency logs anyway.
- Branch protection, required reviewers on environments, secret scanning and unlimited Actions
  minutes come free. GitHub stays $0 in the cost table.
- GitHub Releases, and the APK attached to them, are public. The APK is useless without
  enrollment, and the Firebase API key inside it is restricted to the app's package name and
  signing certificate.
- APK distribution stays on our server even though the original reason (private releases need
  a login) no longer holds: the app's network security config trusts only ISRG Root X1 and X2,
  so it can only talk to our server and could not download from GitHub. The updater also stays
  device-token authenticated, so there is one update path.
- Every contributor must run `scripts/setup-hooks.sh` once per clone and keep their denylist
  current. History that was committed before this decision must be checked, and rewritten if
  needed, before the repository is made public.
