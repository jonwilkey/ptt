# 0001. Record architecture decisions

- Status: Accepted
- Date: 2026-10-24

## Context

The project follows a written spec (`docs/spec.md`) whose "Decisions already made" table fixes
the main architectural choices. Implementation happens in many small PRs, often by different
contributors (including AI agents), so the reasons behind any change to those choices must be
written down where reviewers and future maintainers will find them.

## Decision

We record architecture decisions as short ADRs in `docs/adr/`, using the format in
`docs/adr/README.md` and `0000-template.md`. Any deviation from the spec's decisions table
requires an Accepted ADR before the deviating change is merged.

## Consequences

- Reviewers reject PRs that deviate from the decisions table without an ADR.
- Decision history stays in the repository next to the code, versioned with it.
- Writing an ADR adds a small cost to each significant change, which is intended.
