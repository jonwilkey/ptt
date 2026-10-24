# 0002. AWS KMS as the OpenTofu state-encryption key provider

- Status: Accepted
- Date: 2026-10-24

## Context

The spec's "Cloud resources (OpenTofu)" table requires the S3 backend to have "OpenTofu state
encryption enabled", and the "Secrets" table says AWS access uses "no stored keys" (GitHub
Actions assumes an IAM role through OIDC). The spec does not say where the state-encryption key
comes from.

OpenTofu's built-in alternative, the `pbkdf2` key provider, derives the key from a passphrase.
That passphrase would be a new long-lived secret: stored as a GitHub Actions secret, copied to
each admin's laptop, kept in the password manager, and rotated by hand. Losing it makes the state
unreadable; leaking it defeats the encryption. It also cannot be scoped: anything that can read
the secret can decrypt the state.

The `aws_kms` key provider instead asks AWS KMS for a data key on each run. Access is controlled
by IAM, so the same OIDC roles that already reach the state bucket can be granted (or denied)
`kms:Decrypt`/`kms:GenerateDataKey`, and there is no secret to store or rotate.

The state also has to live somewhere before the bucket and key exist (the bootstrap problem), and
the module that creates the key must not depend on that key to read its own state.

## Decision

- `infra/tofu/bootstrap` creates one symmetric KMS key (`ENCRYPT_DECRYPT`, alias
  `alias/ptt-tofu-state`) next to the state bucket. Its key policy only enables IAM policies for
  the account; the `ptt-tofu-plan` and `ptt-tofu-apply` roles get `kms:Decrypt`, `kms:Encrypt`
  and `kms:GenerateDataKey` on that key and nothing else.
- `infra/tofu/prod` encrypts both state and saved plans with `key_provider "aws_kms"` and
  `method "aes_gcm"`, `enforced = true`, and no fallback method. It refers to the key by alias,
  so no account-specific ARN is committed.
- Automatic key rotation is off: the key only wraps OpenTofu's per-run data keys and its material
  never leaves KMS, so rotation adds cost (each of the first two rotations adds $1/month) for
  little benefit here. It can be turned on later without re-encrypting anything.
- The bootstrap module's own state (`bootstrap/terraform.tfstate`) is **not** encrypted by
  OpenTofu. It is stored in the same private, versioned, TLS-only bucket with S3 server-side
  encryption (SSE-S3), and the CI roles cannot read it. Reasons:
  - It contains no secrets: bucket, key, role and policy definitions only.
  - Encrypting it with the key it manages is circular. If that key were ever disabled, its key
    policy broken, or the key scheduled for deletion, the one stack able to repair it could no
    longer read its own state.
  - Moving existing state into encryption needs a temporary `unencrypted` fallback method and a
    second edit to remove it; skipping that for a one-time, admin-only stack removes a step a
    person could get wrong.
- For the same reason the bucket's default encryption is SSE-S3, not SSE-KMS with this key: the
  S3 layer stays independent of the key.

## Consequences

- No passphrase or key to store, share between the two admins, or rotate. Access to state is
  governed entirely by IAM, consistent with the "no stored keys" rule.
- Running costs rise by about $1/month (one KMS customer-managed key; API calls at this volume
  are within the free tier). The spec's cost table moves from ~$6–7 to ~$7–8 per month.
- Prod state is unreadable without both S3 access and KMS access. Disabling or deleting the KMS
  key makes prod state unreadable; the key has `prevent_destroy` and a 30-day deletion window.
  Prod state is cheap to rebuild by import if it were ever lost, because it holds only a handful
  of resources.
- Admins running `tofu` locally need their own IAM principal to have the same KMS permissions
  (an account administrator already does).
- Bootstrap state relies on S3 access control and SSE-S3 rather than OpenTofu encryption. This is
  a deliberate, documented exception to "OpenTofu state encryption enabled", limited to the
  bootstrap stack.
