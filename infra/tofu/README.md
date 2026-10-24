# infra/tofu/

OpenTofu configuration for the project's cloud resources. Two root modules:

| Module | What it manages | Who runs it | State |
| --- | --- | --- | --- |
| [`bootstrap/`](bootstrap/) | S3 state bucket, KMS key for state encryption, GitHub Actions OIDC provider, IAM roles `ptt-tofu-plan` and `ptt-tofu-apply` | An admin, from a laptop, once (and for rare changes) | `s3://<bucket>/bootstrap/terraform.tfstate`, SSE-S3, not OpenTofu-encrypted ([ADR 0002](../../docs/adr/0002-kms-for-opentofu-state-encryption.md)) |
| [`prod/`](prod/) | Vultr instance (Seattle, Debian stable, IPv4 + IPv6, daily backups), firewall group (TCP 80/443 only), reserved IPv4, first-boot cloud-init (admin users, SSH lockdown, Tailscale), Route 53 A/AAAA records for the server | CI: plan on PRs, apply after approval | `s3://<bucket>/prod/terraform.tfstate`, encrypted by OpenTofu with the KMS key |

First-time setup is in [docs/runbooks/infra-bootstrap.md](../../docs/runbooks/infra-bootstrap.md).

## Deployment-specific values

This repository is public, so nothing identifying is committed: no domain, bucket name, account
ID, repository slug, or user names. Those values have no defaults and come from:

| Value | Local runs | CI |
| --- | --- | --- |
| State bucket | `infra/tofu/backend.hcl` (from `backend.hcl.example`), passed with `-backend-config=../backend.hcl` | Repository secret `TOFU_STATE_BUCKET`, passed with `-backend-config="bucket=..."` |
| Module variables | `terraform.tfvars` in the module (from `terraform.tfvars.example`) | Repository secrets `TOFU_ZONE_NAME`, `TOFU_RECORD_NAME`, `TOFU_ADMIN_USERS`, exported as `TF_VAR_*` |
| IAM role ARNs | Not needed (admin's own credentials) | Repository secret `AWS_TOFU_PLAN_ROLE_ARN`; `production` environment secret `AWS_TOFU_APPLY_ROLE_ARN` |
| Vultr API key | `VULTR_API_KEY` environment variable | Plan: repository secret `VULTR_API_KEY_PLAN` (minimal sub-user). Apply: `production` environment secret `VULTR_API_KEY` |
| AWS credentials | The admin's own profile (`AWS_PROFILE`, SSO) | OIDC: `ptt-tofu-plan` / `ptt-tofu-apply` roles; no stored keys |
| Tailscale auth key | `TF_VAR_tailscale_auth_key`, only when creating or replacing the server | A `production` environment secret, only when creating or replacing the server (added with the `infra` workflow) |

These are **secrets, not variables**, even where the value is not secret (bucket name, domain,
user names, account ID inside the role ARNs): the repository and its Actions logs are public, and
GitHub masks secret values in logs. `TOFU_ENABLED` is the only Actions variable. The CI Tailscale
credentials for deploys (`TS_OAUTH_CLIENT_ID` / `TS_OAUTH_SECRET`) are added with the `infra`
workflow.

`*.tfvars`, `backend.hcl`, `*.tfplan`, `*_override.tf` and all state files are git-ignored.
Commit only the `.example` files and the `.terraform.lock.hcl` lock files.

## Design notes

- **State and locking.** S3 backend with S3-native locking (`use_lockfile = true`, a
  `.tflock` object next to the state; no DynamoDB). The bucket is private, versioned
  (noncurrent versions expire after 90 days), TLS-only, bucket-owner-enforced, SSE-S3.
- **State encryption.** `prod` encrypts state and saved plans with `aes_gcm`, keyed by the KMS
  alias `alias/ptt-tofu-state`, `enforced = true`, no fallback. Losing access to that key means
  losing access to prod state; the key has `prevent_destroy` and a 30-day deletion window.
- **Least privilege.** `ptt-tofu-plan` (only OIDC subjects `repo:<owner>/<repo>:pull_request`
  and `repo:<owner>/<repo>:ref:refs/heads/main`; fork PRs never receive a token) can read prod
  state, write only the lock object, use the KMS key (`GenerateDataKey`/`Decrypt`, encryption
  context `stack = prod` only), and read the hosted zone. `ptt-tofu-apply` (only
  jobs in the `production` environment) can additionally write prod state and change only the A
  and AAAA records of the one server name (`route53:ChangeResourceRecordSetsNormalizedRecordNames`
  and `...RecordTypes` conditions). Neither role can read the bootstrap state or touch anything
  else in the account.
- **Firewall.** The Vultr firewall group allows inbound TCP 80 and 443 on IPv4 and IPv6 and
  drops everything else. Port 22 is never open: SSH and Ansible go over Tailscale. Ansible adds
  the host-level nftables firewall.
- **Addresses.** The reserved IPv4 is the instance's main IP and survives rebuilds, so the A
  record never changes. IPv6 is not reserved (a reserved /64 would need manual OS network
  configuration): the AAAA record follows the instance's own IPv6 and OpenTofu updates it on a
  rebuild ([ADR 0004](../../docs/adr/0004-ipv6-not-reserved.md)).
- **First boot only.** `user_data` (cloud-init) creates the admin users, disables root and
  password SSH, installs Tailscale from its apt repository and joins the tailnet as
  `tag:ptt-server`. The instance ignores later `user_data` changes, so rotating the auth key or
  editing `admin_users` never rebuilds the server; Ansible owns host configuration afterwards.
- **Backups.** Vultr automatic backups, daily at 10:00 UTC (`backup_hour_utc`): 03:00 PDT, after
  the 02:00 Pacific SQLite snapshot; in winter (PST) it is the same hour as the snapshot, so a
  backup may contain the previous night's snapshot.

## How CI runs it

The `ci` workflow, on every PR and push:

1. `tofu fmt -check -recursive` over `infra/tofu`.
2. `tofu init -backend=false` and `tofu validate` for both modules (no credentials needed).
3. When the repository variable `TOFU_ENABLED` is `true` and a PR from this repository touches
   `infra/tofu/**`: assume `ptt-tofu-plan` through OIDC, `tofu init` with the real backend,
   `tofu plan -lock=false -out=...` for `prod`, then `tofu show -json` and post only a summary
   (counts of create/update/delete/replace and one `<actions> <address>` line per changed
   resource, plus a link to the run) as a single sticky PR comment. The human-readable plan is
   never printed, in the comment or the log, because both are public.

The `infra` workflow (added in a later PR) runs `tofu plan` and then, after an admin approves the
`production` environment, `tofu apply` with `ptt-tofu-apply`.

## Running locally (admins)

Prerequisites: OpenTofu 1.13.x, AWS credentials for the account (for example
`aws sso login --profile <profile>` and `export AWS_PROFILE=<profile>`), and for `prod` a Vultr
API key.

```sh
cd infra/tofu
cp backend.hcl.example backend.hcl            # once: set the real bucket name

# prod
cd prod
cp terraform.tfvars.example terraform.tfvars  # once: fill in real values
export VULTR_API_KEY=...                      # from the password manager; never in a file
tofu init -backend-config=../backend.hcl
tofu plan
```

Prefer letting CI apply. If you must apply locally, coordinate with the other admin, run
`tofu plan -out=prod.tfplan`, review it, then `tofu apply prod.tfplan` (the saved plan is
encrypted with the same KMS key; `*.tfplan` is git-ignored). Set `TF_VAR_tailscale_auth_key` only when the plan creates or replaces the
instance.

`bootstrap` works the same way (`tofu -chdir=bootstrap init -backend-config=../backend.hcl`)
and needs only AWS credentials.

## Updating providers

Provider versions are pinned with `~>` in each module's `versions.tf`, resolved from the OpenTofu
registry (`registry.opentofu.org`), and locked in `.terraform.lock.hcl` for linux_amd64,
linux_arm64 and darwin_arm64. After changing a constraint, regenerate the lock file:

```sh
tofu -chdir=prod init -backend=false -upgrade
tofu -chdir=prod providers lock -platform=linux_amd64 -platform=darwin_arm64 -platform=linux_arm64
```

Renovate opens provider update PRs; they are merged by a person, not auto-merged.
