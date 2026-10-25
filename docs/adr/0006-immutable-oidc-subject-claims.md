# 0006. Trust GitHub's immutable OIDC subject claims

- Status: Accepted
- Date: 2026-10-25

## Context

The spec's decisions row "AWS access" (GitHub Actions assumes an IAM role through OIDC, no stored
keys) still holds; this ADR changes only how the roles' trust policies identify the repository.

GitHub repositories can use immutable OIDC subject claims, which add the numeric owner and
repository IDs to the token's `sub`: `repo:<owner>@<owner-id>/<repo>@<repo-id>:pull_request`
instead of `repo:<owner>/<repo>:pull_request`. This repository does, so the bootstrap module's
name-only trust conditions never match and every assume-role call fails. Name-only subjects are
also weaker: anyone who later registers the same owner/name, after a deletion or a rename, would
match them.

## Decision

The plan and apply roles' `sub` conditions match the full immutable subject, built from a new
bootstrap variable, `github_oidc_subject_prefix` (`repo:<owner>@<owner-id>/<repo>@<repo-id>`, as
returned by the repository's `actions/oidc/customization/sub` API). Validations require the
immutable form and that it names the same repository as `github_repository`.

The IDs are identifying, so the prefix lives only in the git-ignored `terraform.tfvars`, like the
other deployment values ([ADR 0003](0003-public-repository.md)).

## Consequences

- Recreating or transferring the repository changes its IDs and cuts CI's AWS access until
  bootstrap is re-applied with the new prefix. That is the intended protection.
- Renaming the owner or the repository also changes the subject and needs the same re-apply.
- Bootstrap has one more value to fill in.
- An existing bootstrap needs one local `tofu apply` in `infra/tofu/bootstrap`, which updates the
  two roles' trust policies and nothing else.
