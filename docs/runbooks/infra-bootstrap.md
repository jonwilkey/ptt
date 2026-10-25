# Runbook: first-time infrastructure bootstrap

Do this once, in order, to go from nothing to a running, tailnet-only server with DNS. It takes
about an hour. Afterwards, CI plans every infra PR and applies after approval.

Placeholders used below (write your real values down in the shared password-manager vault; never
commit them):

| Placeholder | Example | Meaning |
| --- | --- | --- |
| `<owner>/<repo>` | `owner/repo` | The GitHub repository |
| `<your-domain>` | `example.com` | Existing Route 53 public hosted zone |
| `<server-name>` | `ptt.example.com` | Public name of the server, inside `<your-domain>` |
| `<state-bucket>` | `example-ptt-tofu-state` | New, globally unique S3 bucket name |
| `<aws-profile>` | `admin` | Your AWS CLI profile with admin rights in the account |

You need: OpenTofu 1.13.x, the AWS CLI v2, `dig`, `nc`, a Tailscale admin login, admin access to
the GitHub repository, and admin access to the AWS account that hosts `<your-domain>`.

## 1. Vultr account and API keys

Two API users, so the key exposed to PR jobs can do as little as possible.

1. Create the Vultr account (or sign in), enable two-factor authentication, and add a payment
   method.
2. **Apply key.** **Account > Users > Add User**, name `ptt-opentofu-apply`, API enabled, and
   grant only what is needed to manage instances, reserved IPs and firewalls (in the classic ACL
   list: `subscriptions_view`, `subscriptions`, `provisioning`, `firewall`). Do not grant
   billing, user management, DNS, object storage or support. If your account uses Vultr's newer
   organization roles/policies instead, create the equivalent minimal policy. If the first
   `tofu apply` (step 9) fails with a permission error, add only the permission it names. Save
   its API key in the vault as "Vultr API key (OpenTofu apply)".
3. **Plan key.** Add a second user, `ptt-opentofu-plan`, API enabled, with the minimum needed to
   refresh state: `subscriptions_view` (instances, reserved IPs, OS list) and `firewall`. Vultr
   has no read-only firewall permission, so this key can also change firewall groups; that is
   the residual risk of the plan key. Grant nothing else. If the first CI plan (step 11) fails
   with a permission error, add only what it names. Save its key as "Vultr API key (OpenTofu
   plan)".
4. **Access control (allowed IPs):** GitHub-hosted runners use a large, changing set of
   addresses, so neither CI key can be IP-restricted in practice; allow all IPv4 and IPv6 for
   them. For laptop use, create a separate key restricted to your home IP and keep it out of
   GitHub.

## 2. Check the AWS account

```sh
aws sso login --profile <aws-profile>     # or however you normally sign in
export AWS_PROFILE=<aws-profile>
aws sts get-caller-identity
aws route53 list-hosted-zones-by-name --dns-name <your-domain> --max-items 1
aws iam list-open-id-connect-providers
```

- The hosted-zone command must show `<your-domain>.` as a public zone.
- If `list-open-id-connect-providers` already shows `token.actions.githubusercontent.com`, set
  `create_github_oidc_provider = false` in step 3.

## 3. Bootstrap apply (local state)

From the repository root:

```sh
cd infra/tofu
cp backend.hcl.example backend.hcl
#   edit backend.hcl: bucket = "<state-bucket>"
cd bootstrap
cp terraform.tfvars.example terraform.tfvars
#   edit terraform.tfvars: github_repository = "<owner>/<repo>", github_oidc_subject_prefix
#   (see below), state_bucket_name = "<state-bucket>", zone_name = "<your-domain>",
#   record_name = "<server-name>"
cp local_backend_override.tf.example local_backend_override.tf
tofu init
tofu apply
```

Get `github_oidc_subject_prefix` with
`gh api repos/<owner>/<repo>/actions/oidc/customization/sub --jq .sub_claim_prefix`; it looks like
`repo:<owner>@<owner-id>/<repo>@<repo-id>` (see
[ADR 0006](../adr/0006-immutable-oidc-subject-claims.md)). The IDs are identifying, so the value
goes only in the git-ignored `terraform.tfvars`, never in the repository.

Read the plan before typing `yes`: it creates one S3 bucket and its settings, one KMS key and
alias, (optionally) the OIDC provider, and two IAM roles with inline policies. Nothing else.

## 4. Move bootstrap state into S3

Still in `infra/tofu/bootstrap`, the two commands:

```sh
rm local_backend_override.tf
tofu init -migrate-state -backend-config=../backend.hcl
```

Answer `yes` when asked to copy the existing state to the new "s3" backend. Then check and clean
up:

```sh
tofu plan                                # must say "No changes"
aws s3 ls s3://<state-bucket>/bootstrap/ # shows terraform.tfstate
rm -f terraform.tfstate terraform.tfstate.backup
tofu output                              # keep this open for step 7
```

The bootstrap state stays unencrypted by OpenTofu on purpose (SSE-S3 in a private bucket only
admins can read); see [ADR 0002](../adr/0002-kms-for-opentofu-state-encryption.md). Do not add an
`encryption` block to the bootstrap module.

## 5. Tailscale policy

In the Tailscale admin console, **Access controls**, add the tags and rules below to the tailnet
policy file (merge with what is already there; replace the two emails with the admins' Tailscale
logins):

```jsonc
{
  "groups": {
    "group:ptt-admins": ["admin1@example.com", "admin2@example.com"],
  },
  "tagOwners": {
    "tag:ptt-server": ["group:ptt-admins"],
    "tag:ci":         ["group:ptt-admins"],
  },
  "acls": [
    // Admins: SSH and metrics on the server.
    {"action": "accept", "src": ["group:ptt-admins"], "dst": ["tag:ptt-server:22", "tag:ptt-server:9090"]},
    // CI (ephemeral tag:ci nodes): SSH for Ansible and deploys only.
    {"action": "accept", "src": ["tag:ci"], "dst": ["tag:ptt-server:22"]},
  ],
}
```

If the policy still contains the default allow-all rule (`"src": ["*"], "dst": ["*:*"]`), remove
it, or these restrictions have no effect. Save; the console rejects invalid policies.

## 6. One-time Tailscale auth key for the server

**Settings > Keys > Generate auth key**:

- Description: `ptt-prod first boot`
- Reusable: **off**
- Expiration: **1 day** (generate it just before step 9)
- Ephemeral: **off**
- Pre-approved: **on** (only shown if device approval is enabled)
- Tags: **`tag:ptt-server`**

Copy the `tskey-auth-...` value. It is used once, at the server's first boot, and lands in the
encrypted prod state. Tagged nodes have key expiry disabled, so the server stays on the tailnet.

## 7. GitHub repository settings

**Settings > Environments > New environment** `production`:

- Required reviewers: both admins. Prevent self-review: off (see
  [github-setup.md](github-setup.md#2-the-production-environment)).
- Deployment branches and tags: **Selected branches and tags**: branch `main` and tag
  `server-v*`.

**Settings > Secrets and variables > Actions**:

The repository and its Actions logs are public, so every identifying value is a **secret**
(GitHub masks secrets in logs), even the ones that are not confidential. Do not create them as
variables.

| Kind | Name | Value |
| --- | --- | --- |
| Repository secret | `VULTR_API_KEY_PLAN` | Plan key from step 1.3 |
| Repository secret | `AWS_TOFU_PLAN_ROLE_ARN` | `plan_role_arn` from `tofu output` |
| Repository secret | `TOFU_STATE_BUCKET` | `<state-bucket>` |
| Repository secret | `TOFU_ZONE_NAME` | `<your-domain>` |
| Repository secret | `TOFU_RECORD_NAME` | `<server-name>` |
| Repository secret | `TOFU_ADMIN_USERS` | JSON list, see below |
| `production` environment secret | `VULTR_API_KEY` | Apply key from step 1.2 (used only by the `infra` apply job) |
| `production` environment secret | `AWS_TOFU_APPLY_ROLE_ARN` | `apply_role_arn` from `tofu output` |

The deploy-related secrets (`TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, `DEPLOY_SSH_KEY`,
`PTT_DOMAIN`, `ANSIBLE_SITE_VARS`) and the other repository settings are in
[github-setup.md](github-setup.md), which also has the full list.

`TOFU_ADMIN_USERS` (public keys only; one entry per admin; one line):

```json
[{"name":"admin1","ssh_public_keys":["ssh-ed25519 AAAA... admin1@laptop"]},{"name":"admin2","ssh_public_keys":["ssh-ed25519 AAAA... admin2@laptop"]}]
```

Do not set `TOFU_ENABLED` yet.

## 8. Prepare the prod module locally

From `infra/tofu/bootstrap`, where step 4 left you:

```sh
cd ../prod
cp terraform.tfvars.example terraform.tfvars
#   edit: zone_name = "<your-domain>", record_name = "<server-name>", admin_users = (same as TOFU_ADMIN_USERS)
tofu init -backend-config=../backend.hcl
```

## 9. First prod apply

```sh
export AWS_PROFILE=<aws-profile>
read -rs VULTR_API_KEY && export VULTR_API_KEY              # paste the Vultr apply key, then Enter
read -rs TF_VAR_tailscale_auth_key && export TF_VAR_tailscale_auth_key   # paste the tskey-auth-... key
tofu plan -out=prod.tfplan
```

The plan must create exactly: 1 firewall group, 4 firewall rules (TCP 80 and 443, v4 and v6),
1 reserved IPv4, 1 instance, 2 Route 53 records (`<server-name>` A and AAAA). Then:

```sh
tofu apply prod.tfplan
tofu output
rm -f prod.tfplan
unset TF_VAR_tailscale_auth_key VULTR_API_KEY
```

## 10. Verify

Allow 3–5 minutes for first boot (package upgrade, Tailscale install).

```sh
dig +short A <server-name>          # = reserved_ipv4 output
dig +short AAAA <server-name>       # = ipv6 output
nc -vz -w 5 <reserved_ipv4> 22      # must time out: SSH is not public
tailscale status | grep ptt-prod    # the server is on the tailnet, tagged tag:ptt-server
ssh admin1@ptt-prod 'sudo -n true && echo ok'   # over Tailscale (MagicDNS name)
```

In the Tailscale console, check that the machine `ptt-prod` shows the tag `tag:ptt-server`,
and revoke the auth key if it is still listed. In the Vultr console, check that the instance
shows automatic backups enabled and the firewall group attached.

If SSH over Tailscale fails, use the Vultr web console (root password is on the instance's page
in the Vultr console) to read `/var/log/cloud-init-output.log`.

## 11. Turn on CI plans

Add the repository **variable** (the only one) `TOFU_ENABLED` = `true`. Open a trivial PR
touching `infra/tofu/` (for example a comment) and check that the `OpenTofu plan (prod)` job
posts a "No changes" summary comment (counts and resource addresses only; the full plan is never
printed because the logs are public). Close the PR.

From now on, infrastructure changes go through PRs; leave `TF_VAR_tailscale_auth_key` unset
unless a plan creates or replaces the instance. Next: run the Ansible playbook (see
`infra/ansible/`), then continue with the first deploy in
[github-setup.md](github-setup.md#9-first-deploy).
