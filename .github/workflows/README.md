# .github/workflows/

GitHub Actions workflows. Milestone 1 adds `ci`, `server-release`, `infra`, and `release-please`;
milestone 6 adds `android-release`, `weekly-maintenance`, and `nightly-security`. Actions are
pinned by commit digest (Renovate keeps them current).

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci` | Every PR and push to `main` | Lint, tests, scans, `tofu plan` summary, Ansible lint, compose smoke test |
| `release-please` | Push to `main` | Release PRs; on a server release, calls `server-release` ([ADR 0005](../../docs/adr/0005-release-please-calls-server-release.md)) |
| `server-release` | Called by `release-please`, tag `server-v*`, or manual (tag input) | Image to GHCR, Trivy, smoke test of the pushed image, SBOM and cosign signature, then `deploy-prod` after approval |
| `android-release` | Called by `release-please`, tag `android-v*`, or manual (tag input, `allow_older`) | `build` (environment `release`: the keystore, Firebase values and repository variable `ANDROID_SIGNING_CERT_SHA256`) signs `assembleRelease` and checks the signer and version code; `attach` puts `ptt-<v>.apk` and its `.sha256` on the GitHub Release; `publish` (environment `production`, group `deploy-production`) copies the APK to the host with `publish-apk.sh` and checks `app_latest`. Two approvals ([ADR 0011](../../docs/adr/0011-apk-published-over-the-deploy-path.md)) |
| `deploy-prod` | Called by `server-release` and `rollback-drill` (`workflow_call`) | Deploys an image with Ansible, checks health and runs the smoke test, rolls back automatically on failure, and moves the GHCR tag `deployed` after a successful deploy |
| `rollback-drill` | Manual only, from `main`, input `confirm: break production` | Deploys a deliberately broken build of the running release and passes only if production rolled back ([docs/operations.md](../../docs/operations.md#proving-it-the-rollback-drill)) |
| `weekly-maintenance` | Mondays 03:00 America/Los_Angeles (two UTC crons plus a DST check), or manual from `main` | Rebuilds the running release (the `deployed` image's version, `+maint` dropped) with refreshed base-image digests, same gates as a release, deploys through `deploy-prod` in the reviewer-free `maintenance` environment ([ADR 0012](../../docs/adr/0012-weekly-maintenance-deploys-without-approval.md)), pings `ptt-weekly` |
| `nightly-security` | Daily 13:00 UTC, or manual | Trivy on the `deployed` image, OSV-Scanner and `govulncheck` on `main`; opens, closes and reopens `security` issues for findings a release gate would fail ([ADR 0014](../../docs/adr/0014-nightly-security-reports-release-gate-findings.md)); pings `ptt-security` |
| `infra` | Push to `main` touching `infra/` (or `deploy/compose.yaml`, `deploy/Caddyfile`), or manual | `tofu plan`, `tofu apply` after approval (only with changes), then `site.yml` after approval |

Shared pieces: `.github/actions/docker-daemon-prod` and `.github/actions/compose-smoke`
(the compose smoke test used by `ci` and `server-release`), and `.github/scripts/` (SSH to the
server over Tailscale, the deployed image, the wait for no active sessions, the production
health check and smoke test, the rollback (`rollback-prod.sh` on the runner, `rollback-host.sh` on
the host, `rollback-host-test.sh` for its decisions), `publish-apk.sh` (the APK publish step, host mode tested by `publish-apk-test.sh`), masking the Ansible site vars, public-safe `tofu plan` and its
summary). The scheduled workflows add `maintenance-gate.sh` (the 03:00 Pacific check),
`deployed-release.sh`, `refresh-base-digests.sh`, `hc-ping.sh` (Healthchecks.io pings) and
`nightly-security.py` (findings and issues); `scheduled-test.sh` tests them.

`weekly-maintenance`'s `build`, `scan`, `smoke` and `sign` jobs are copies of `server-release`'s.
When one changes (a Trivy flag, the smoke steps, the signing commands), change the other in the
same pull request; only what is built (the tag's tree with refreshed base digests, version
`<T>+maint.<YYYYMMDD>`) and the signing identity differ.

Repository settings, environments, secrets, and variables: [docs/runbooks/github-setup.md](../../docs/runbooks/github-setup.md).
