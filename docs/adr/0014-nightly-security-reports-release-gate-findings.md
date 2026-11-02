# 0014. nightly-security opens issues only for findings a release gate would fail

- Status: Accepted
- Date: 2026-11-02

## Context

The spec's `nightly-security` workflow "rescans the deployed image and lockfiles for newly
published vulnerabilities; opens an issue on any finding". Trivy, OSV-Scanner and `govulncheck`
report every severity. The deployed image's Debian base alone carries a steady stream of low and
medium advisories, most with no fix, and every one would become an issue. The release gates
(spec, "Supply chain") already draw the line that matters: high or critical findings fail the
build.

## Decision

`nightly-security` opens an issue only for a finding that would fail a release gate today: Trivy
HIGH or CRITICAL, OSV-Scanner CVSS 7.0 or higher or unscored outside Go, and every `govulncheck`
finding. It reads the same waiver files as the gates (`.trivyignore.yaml`, `osv-scanner.toml`),
so a finding a person already waived opens no issue until its waiver expires. Details:
`docs/operations.md`, "nightly-security".

## Consequences

- An issue means the same thing as a red release build: the next release or weekly rebuild would
  fail on it, so someone has to act. Issues stay rare enough to be read.
- A newly published medium or low vulnerability in shipped code opens no issue. It still shows in
  the run's log and job summary, and becomes an issue if its score is raised to high.
- One rule decides both "blocks a release" and "opens an issue"; changing a gate's threshold
  changes both, which is intended.
