# Runbook: GitHub settings for CI/CD

The repository settings, secrets, and variables that the `ci`, `release-please`,
`server-release`, and `infra` workflows need, and the order of the first deploy. Values come from
[infra-bootstrap.md](infra-bootstrap.md) and the steps below; keep each one in the shared
password-manager vault, never in the repository.

The repository and its Actions logs are public, so every identifying value is a **secret**
(GitHub masks secret values in logs), even ones that are not confidential (bucket name, domain,
user names). Only the two on/off switches are variables.

## 1. Repository settings

**Settings > General > Pull Requests**: allow squash merging (Conventional Commit PR titles
become the changelog).

**Settings > Branches** (or a ruleset) for `main`:

- Require a pull request before merging.
- Require status checks to pass: `server`, `server-image`, `actionlint`, `osv-scanner`,
  `gitleaks`, `OpenTofu fmt and validate`, `Ansible (syntax check, ansible-lint)`,
  `compose-smoke`. (`OpenTofu plan (prod)` is conditional; do not require it.)
- Do not allow force pushes or deletion.

**Settings > Actions > General**:

- Actions permissions: allow actions and reusable workflows (every third-party action is pinned
  by commit SHA).
- Workflow permissions: **Read repository contents and packages permissions** (the default for
  `GITHUB_TOKEN`; each job asks for what it needs).
- **Allow GitHub Actions to create and approve pull requests: on.** release-please opens its
  release PRs with `GITHUB_TOKEN`.
- Fork pull request workflows: **Require approval for all external contributors**.

**Settings > Rules > Rulesets > New tag ruleset** (optional): target `server-v*` and
`android-v*`, restrict updates and deletions, and add the repository admin role to the bypass
list. A pushed `server-v*` tag starts a release; with only admins having write access this is
already limited to admins. Do not restrict creations unless release-please (acting as GitHub
Actions) can still create tags, or releases stop.

## 2. The `production` environment

**Settings > Environments > New environment** `production` (if
[infra-bootstrap.md](infra-bootstrap.md) step 7 already created it, edit it):

- **Required reviewers:** both admins. Any one approval releases the job (spec: "either admin's
  approval").
- **Prevent self-review: off.** With it on, the admin who merged a release PR could not approve
  its deploy, so a deploy would always need the other admin. Turn it on if you want two-person
  approval and accept that one admin alone cannot ship.
- **Deployment branches and tags: Selected branches and tags**, with two rules:
  - Branch `main`: release-please's calls of `server-release`, manual `server-release` runs
    (they run from `main`), and the `infra` workflow.
  - Tag `server-v*`: `server-release` runs started by pushing a tag.

Jobs that use it: `server-release` / `deploy`, `infra` / `apply`, `infra` / `ansible`. Each
waits for an approval before it starts.

## 3. Secrets and variables

Create repository secrets under **Settings > Secrets and variables > Actions > Secrets**, and
environment secrets under **Settings > Environments > production > Environment secrets**.
Environment secrets are readable only by jobs that passed the approval gate.

| Name | Scope | Used by | Value comes from |
| --- | --- | --- | --- |
| `VULTR_API_KEY_PLAN` | Repository secret | `ci` tofu-plan, `infra` plan | infra-bootstrap.md step 1.3 (plan key) |
| `AWS_TOFU_PLAN_ROLE_ARN` | Repository secret | `ci` tofu-plan, `infra` plan | `plan_role_arn` from the bootstrap `tofu output` |
| `TOFU_STATE_BUCKET` | Repository secret | `ci` tofu-plan, `infra` plan and apply | The state bucket name |
| `TOFU_ZONE_NAME` | Repository secret | `ci` tofu-plan, `infra` plan and apply | The Route 53 zone, e.g. `example.com` |
| `TOFU_RECORD_NAME` | Repository secret | `ci` tofu-plan, `infra` plan and apply | The server's name, e.g. `ptt.example.com` |
| `TOFU_ADMIN_USERS` | Repository secret | `ci` tofu-plan, `infra` plan and apply | JSON list, infra-bootstrap.md step 7 |
| `VULTR_API_KEY` | `production` secret | `infra` apply | infra-bootstrap.md step 1.2 (apply key) |
| `AWS_TOFU_APPLY_ROLE_ARN` | `production` secret | `infra` apply | `apply_role_arn` from the bootstrap `tofu output` |
| `TS_SERVER_AUTH_KEY` | `production` secret, **optional** | `infra` apply (`TF_VAR_tailscale_auth_key`) | A one-time `tag:ptt-server` auth key (infra-bootstrap.md step 6). Create it only just before an apply that creates or replaces the server, and delete it afterwards. |
| `TS_OAUTH_CLIENT_ID` | `production` secret | `server-release` deploy, `infra` ansible | Section 4 |
| `TS_OAUTH_SECRET` | `production` secret | `server-release` deploy, `infra` ansible | Section 4 |
| `DEPLOY_SSH_KEY` | `production` secret | `server-release` deploy, `infra` ansible | Section 5 (private half) |
| `PTT_DOMAIN` | `production` secret | `server-release` deploy (Ansible `ptt_domain`, `/healthz`, smoke test), `infra` ansible (`/healthz`, smoke test) | The server's name, the same value as `TOFU_RECORD_NAME` |
| `ANSIBLE_SITE_VARS` | `production` secret | `infra` ansible | The full YAML content of `infra/ansible/inventory/group_vars/prod/site.yml` (section 6) |
| `SOPS_AGE_KEY` | `production` secret, **required** (prod `app_secrets` lists the FCM key) | `server-release` deploy, `infra` ansible (only the `ansible-playbook` step) | The CI age identity (section 6, "The CI age key") |

Repository **variables** (**Settings > Secrets and variables > Actions > Variables**):

| Name | Value | Effect |
| --- | --- | --- |
| `TOFU_ENABLED` | `true` after infra-bootstrap.md step 11 | Turns on `tofu plan` in `ci` and the `infra` plan/apply jobs |
| `ANSIBLE_ENABLED` | `true` after the first manual `site.yml` run (section 7) | Turns on the `infra` workflow's Ansible job |

`GITHUB_TOKEN` is automatic. The deploy jobs pass it to Ansible as `GHCR_TOKEN` (with
`packages: read`) so the server can pull the image while the job runs.

## 4. Tailscale OAuth client for CI

CI joins the tailnet as an ephemeral node tagged `tag:ci`, which the tailnet policy from
infra-bootstrap.md step 5 lets reach only `tag:ptt-server:22`.

1. Check that the policy file has `tag:ci` under `tagOwners` and the `tag:ci` →
   `tag:ptt-server:22` rule (infra-bootstrap.md step 5).
2. Tailscale admin console, **Settings > OAuth clients > Generate OAuth client** (on newer
   consoles, **Settings > Trust credentials**):
   - Description: `ptt GitHub Actions`
   - Scopes: **Keys > Auth Keys: Write** only.
   - Tags: **`tag:ci`** only.
3. Save the client ID as `TS_OAUTH_CLIENT_ID` and the secret as `TS_OAUTH_SECRET`
   (`production` environment secrets). The secret is shown once.

The `tailscale/github-action` step mints a pre-authorized, ephemeral auth key with this client
for each job; the node leaves the tailnet when the job ends. Rotate the client yearly (generate a
new one, update both secrets, revoke the old one).

## 5. Deploy SSH key

CI logs in to the server as the `deploy` user with a key used for nothing else. On a trusted
laptop:

```sh
cd "$(mktemp -d)"
ssh-keygen -t ed25519 -N '' -C ptt-ci-deploy -f ptt-deploy
cat ptt-deploy.pub    # public half
cat ptt-deploy        # private half
```

- **Public half** (`ptt-deploy.pub`): into `base_deploy_ssh_keys` in your local
  `infra/ansible/inventory/group_vars/prod/site.yml`, and so also into `ANSIBLE_SITE_VARS`.
  The `base` role installs it for `deploy`, usable only from Tailscale addresses.
- **Private half** (`ptt-deploy`, including the `BEGIN`/`END` lines): the `production`
  environment secret `DEPLOY_SSH_KEY`, and the password-manager vault.

Then delete both files (`rm ptt-deploy ptt-deploy.pub`). To rotate: generate a new pair, add the
new public key next to the old one, run `site.yml`, replace `DEPLOY_SSH_KEY`, then remove the old
public key and run `site.yml` again.

## 6. `ANSIBLE_SITE_VARS`

The exact content of your local `infra/ansible/inventory/group_vars/prod/site.yml` (copied from
`site.yml.example`): `ptt_domain`, `base_admin_users` with every admin's keys, and
`base_deploy_ssh_keys`. Paste the whole YAML file as the secret. The `infra` workflow writes it
to a mode-0600 file, passes it with `-e @file`, masks every value in it, and deletes it at the end
of the job.

Whenever you change your local `site.yml`, update the secret in the same sitting, or the next
`infra` run puts the old values back (for example, a removed admin key).

### The CI age key (`SOPS_AGE_KEY`)

The Ansible `app` role decrypts the sops-encrypted files in `app_secrets` (the FCM key, see
[firebase-setup.md](firebase-setup.md)) on the runner, so CI needs its own age identity. Both
workflows install a pinned, checksum-verified `sops` and pass `SOPS_AGE_KEY` to the
`ansible-playbook` step only. Prod `app_secrets` lists the FCM key, so deploys fail without the
secret.

1. On your workstation, create a dedicated key (not one of the admins' keys):

   ```sh
   age-keygen -o ci-age.key        # prints "Public key: age1..."
   ```

2. Save the whole file in the shared password-manager vault. Its `age1...` public key is a
   **recipient**: every file in `infra/secrets/` must be encrypted to both admins' keys **and**
   this one (the `--age a,b,c` list in [firebase-setup.md](firebase-setup.md)). Re-encrypt
   existing files after adding it: `sops -r -i --add-age <key> <file>` (`sops updatekeys` would
   need a `.sops.yaml`, and none is committed).
3. **Settings > Environments > production > Environment secrets**: add `SOPS_AGE_KEY` with the
   full contents of `ci-age.key` (the `# created` / `# public key` comment lines and the
   `AGE-SECRET-KEY-...` line). It is an environment secret, so only jobs past the approval gate
   can read it.
4. Delete `ci-age.key` from the workstation.

To rotate it, generate a new key, re-encrypt every secret with the new recipient list, replace
the environment secret, and merge.

## 7. GHCR package

`server-release` pushes `ghcr.io/<owner>/<repo>/pttd:<version>` (lowercased). The first push
creates the package, linked to this repository.

After the first release, open the package (**your profile or organization > Packages > pttd >
Package settings**) and choose its visibility:

- **Public** (recommended): the image holds nothing secret, anyone can verify its signature, and
  an admin can re-pull it on the server without a token.
- **Private**: also works; every job that pulls (scan, smoke, SBOM, deploy) logs in with its own
  `GITHUB_TOKEN`, and the package's **Manage Actions access** must list this repository (it does
  when the package was created by this repository's workflow). An admin running Ansible from a
  laptop then needs `GHCR_TOKEN` set to a token with `read:packages` if the image has to be pulled.

Either way the deploy pulls with the deploy job's token, which expires when the job ends.

## 8. Release PRs

release-please opens one release PR per component (`server`, `android`) and keeps it up to date.
Because it uses `GITHUB_TOKEN`, GitHub does not run `ci` on it by itself (ADR 0005): **before
merging a release PR, close it and reopen it** (or push an empty commit to its branch). That
event comes from you, so `ci` runs and the required checks report. Then merge it.

Every time release-please updates the PR (each push to `main` with a releasable change rewrites
its branch), the new head has no checks again: close and reopen it once more right before you
merge.

Merging the server release PR makes release-please tag `server-vX.Y.Z`, create the GitHub
release, and call `server-release` in the same run.

To rebuild and redeploy an existing release (for example after a failed deploy, or to go back to
an older release): **Actions > server-release > Run workflow**, branch `main`, tag
`server-vX.Y.Z`. It rebuilds and redeploys the tag as-is, with the same pinned base images from
that tag's Dockerfile; base-image updates ship as a new release (Renovate PRs; the
`weekly-maintenance` workflow in milestone 6). The rebuilt image has a new digest, and the
version tag in GHCR moves to it.

Redeploying an **older** tag also copies that tag's `deploy/compose.yaml` and `deploy/Caddyfile`
to the server, undoing later runtime changes until the next release or `infra` run (which uses
`main`'s files).

## 9. First deploy

In order:

1. [infra-bootstrap.md](infra-bootstrap.md) steps 1–11: state bucket, roles, the server (first
   `tofu apply` from a laptop), DNS, and `TOFU_ENABLED=true`.
2. Sections 1–6 here: repository settings, the `production` environment, the secrets, the
   Tailscale OAuth client, and the deploy key.
3. First `site.yml` run from a laptop, as yourself (infra/ansible/README.md, "First run"). It
   creates the `deploy` user with the deploy key and writes `/opt/ptt`, but starts nothing (no
   release yet). Check `ssh -i <private key> deploy@ptt-prod true` works from a tailnet machine.
4. Set the variable `ANSIBLE_ENABLED=true`.
5. First `infra` run: **Actions > infra > Run workflow** on `main`. The plan job should show no
   changes (so apply is skipped); approve the `ansible` job. It must finish with no failures,
   which proves Tailscale OAuth, the deploy key, and `ANSIBLE_SITE_VARS`.
6. First release: merge a `feat:` or `fix:` change touching `server/`, wait for release-please's
   server release PR, close and reopen it, let `ci` pass, and merge it.
7. The `release-please` run calls `server-release`: build, Trivy, smoke test, SBOM and signature.
   Approve the `deploy (production)` job when it asks.
8. The deploy job skips the session wait (nothing is deployed yet), runs `deploy.yml`, waits for
   `https://<domain>/healthz`, and runs `deploy/smoke.sh` against production. Its job summary
   shows the version, the digest, and the `cosign verify` command.
9. Section 7: set the package visibility.

That is the milestone 1 exit check: a merged change passed the CI smoke tests and deployed to
production after approval.

## Notes

- `server-release`'s deploy job and `infra`'s Ansible job share the concurrency group
  `deploy-production`, so they never change the host at the same time. GitHub keeps at most one
  waiting job per group: if a third arrives while one runs and one waits, the waiting one is
  cancelled. Re-run it if it was the one you wanted.
- A failed production health check or smoke test leaves the new release running and prints the
  manual rollback command (the previous digest) in the job summary. Automatic rollback is
  milestone 6.
- The deploy job, and the `infra` Ansible job when a release is deployed, wait up to 10 minutes
  for active sessions to end and fail without changing the host if they do not; re-run the job
  later. A refused connection to pttd's internal port (pttd not running) does not block them.
- After `site.yml`, the `infra` Ansible job also checks `/healthz` and runs the production smoke
  test, once a release has been deployed.
