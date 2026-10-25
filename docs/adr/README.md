# Architecture Decision Records

An ADR is a short Markdown note recording one significant decision: the context that forced it,
what we decided, and what follows from it. ADRs are never deleted; a changed decision gets a new
ADR that supersedes the old one.

## Rules

- **Any deviation from the "Decisions already made" table in [docs/spec.md](../spec.md) needs an
  ADR before the change is made.** The ADR lands in its own PR (or first in the PR that makes the
  change) and must be Accepted before the deviating code merges.
- Other choices worth explaining to a future reader (a library, a protocol detail, a trade-off)
  may also get an ADR.

## Format

- File name: `NNNN-kebab-title.md`, numbered sequentially from `0001`
  (for example `0002-use-sqlite-wal-mode.md`). Copy [0000-template.md](0000-template.md).
- Status is one of:
  - **Proposed** — under discussion in a PR.
  - **Accepted** — in force.
  - **Superseded by [NNNN](NNNN-title.md)** — replaced; kept for history.
- When superseding, update the old ADR's Status line and link both ways.

## Index

- [0001 — Record architecture decisions](0001-record-architecture-decisions.md)
- [0002 — AWS KMS as the OpenTofu state-encryption key provider](0002-kms-for-opentofu-state-encryption.md)
- [0003 — Public repository](0003-public-repository.md)
- [0004 — The AAAA record follows the instance's IPv6 address (no reserved IPv6)](0004-ipv6-not-reserved.md)
- [0005 — release-please calls server-release directly instead of relying on the tag push](0005-release-please-calls-server-release.md)
- [0006 — Trust GitHub's immutable OIDC subject claims](0006-immutable-oidc-subject-claims.md)
