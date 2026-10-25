# infra/secrets/

Production secrets encrypted with sops using age keys (one per admin). Only encrypted files are
ever committed here — **never commit plaintext**. Decrypted working copies (`*.dec.*`,
`*.plain.*`) are git-ignored.

Planned contents: the FCM service-account key, `fcm-service-account.sops.json`, created by
[docs/runbooks/firebase-setup.md](../../docs/runbooks/firebase-setup.md). Each file is listed in
`app_secrets` (see `infra/ansible/inventory/group_vars/prod/main.yml`) to be deployed.

Encrypt with `sops --encrypt --age <admin1>,<admin2>,<ci> --output <name>.sops.json <plaintext>`:
the age public key of each admin plus the CI key (docs/runbooks/github-setup.md, "The CI age
key"), because CI decrypts these files during deploys. No `.sops.yaml` is committed, so no real
recipient appears in this public repository; always pass `--age` explicitly. Edit later with `sops <name>.sops.json`. Name decrypted working copies
`*.dec.*` or `*.plain.*` so git ignores them, and delete them when done.
