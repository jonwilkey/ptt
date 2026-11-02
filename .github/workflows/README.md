# .github/workflows/

GitHub Actions workflows. Milestone 1 adds `ci`, `server-release`, `infra`, and `release-please`;
milestone 6 adds `android-release`, `weekly-maintenance`, and `nightly-security`. Actions are
pinned by commit digest (Renovate keeps them current).

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci` | Every PR and push to `main` | Lint, tests, scans, `tofu plan` summary, Ansible lint, compose smoke test |
| `release-please` | Push to `main` | Release PRs; on a server release, calls `server-release` ([ADR 0005](../../docs/adr/0005-release-please-calls-server-release.md)) |
| `server-release` | Called by `release-please`, tag `server-v*`, or manual (tag input) | Image to GHCR, Trivy, smoke test of the pushed image, SBOM and cosign signature, then `deploy-prod` after approval |
| `deploy-prod` | Called by `server-release` and `rollback-drill` (`workflow_call`) | Deploys an image with Ansible, checks health and runs the smoke test, rolls back automatically on failure, and moves the GHCR tag `deployed` after a successful deploy |
| `rollback-drill` | Manual only, from `main`, input `confirm: break production` | Deploys a deliberately broken build of the running release and passes only if production rolled back ([docs/operations.md](../../docs/operations.md#proving-it-the-rollback-drill)) |
| `infra` | Push to `main` touching `infra/` (or `deploy/compose.yaml`, `deploy/Caddyfile`), or manual | `tofu plan`, `tofu apply` after approval (only with changes), then `site.yml` after approval |

Shared pieces: `.github/actions/docker-daemon-prod` and `.github/actions/compose-smoke`
(the compose smoke test used by `ci` and `server-release`), and `.github/scripts/` (SSH to the
server over Tailscale, the deployed image, the wait for no active sessions, the production
health check and smoke test, the rollback (`rollback-prod.sh` on the runner, `rollback-host.sh` on
the host, `rollback-host-test.sh` for its decisions), masking the Ansible site vars, public-safe `tofu plan` and its
summary).

Repository settings, environments, secrets, and variables: [docs/runbooks/github-setup.md](../../docs/runbooks/github-setup.md).
