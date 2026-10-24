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
