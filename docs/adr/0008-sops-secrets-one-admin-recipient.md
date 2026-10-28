# 0008. sops secrets are encrypted to one admin key until the second admin has one

- Status: Accepted
- Date: 2026-10-28

## Context

The spec's "Secrets" table says the FCM service-account key is a sops-encrypted file "encrypted to
both admins' age keys", and the open-questions answer says each admin has an age key for the sops
secrets. `infra/secrets/README.md` and `docs/runbooks/github-setup.md` add the CI key as a third
recipient so deploys can decrypt.

Only one admin has generated an age key so far. Waiting for the second key would hold the FCM key
out of production, and with it every push notification.

## Decision

Until the second admin has an age key, files under `infra/secrets/` are encrypted to the one
existing admin key and the CI key (`SOPS_AGE_KEY`). When the second admin's key exists, every
file is re-encrypted to all three with `sops -r -i --add-age <key> <file>`, and this ADR is marked
superseded.

## Consequences

- Only one admin can decrypt or edit the secrets locally. If that admin's key is lost, the
  secrets can still be recovered from CI's key in the shared vault, or rotated: the FCM key can be
  reissued in the Google Cloud console
  (IAM > Service Accounts > Keys).
- Deploys are unaffected: CI decrypts with its own key.
- Follow-up: the second admin generates an age key and the files are re-encrypted, as above.
