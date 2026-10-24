# infra/secrets/

Production secrets encrypted with sops using age keys (one per admin). Only encrypted files are
ever committed here — **never commit plaintext**. Decrypted working copies (`*.dec.*`,
`*.plain.*`) are git-ignored.

Filled in milestone 1 (with the FCM service-account key added in milestone 2).
