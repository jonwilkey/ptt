# 0012. Weekly maintenance deploys through an environment without reviewers

- Status: Accepted
- Date: 2026-11-02

## Context

The spec's `weekly-maintenance` workflow rebuilds the server image every Monday at 03:00 Pacific
and "auto-promotes to production if the CI smoke tests pass". Production deploys today go
through the `production` GitHub environment, whose required reviewers are the approval gate
("a person only merges pull requests and approves production deploys"). A 03:00 run cannot wait
for an approval, and environment secrets (Tailscale OAuth client, deploy SSH key, domain, CI age
key) are readable only by jobs in that environment.

Options:

1. Move the deploy secrets to repository secrets. Every workflow on every branch could then
   read them, including a pull request's workflow from a branch in this repository.
2. Remove the reviewers from `production`. Every deploy would then go out unapproved.
3. A second environment for this one workflow.

## Decision

Both admins accepted this on 2026-11-02.

Option 3. A `maintenance` environment with **no required reviewers**, deployments allowed only from
the `main` branch, holding its own copies of `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`,
`DEPLOY_SSH_KEY`, `PTT_DOMAIN` and `SOPS_AGE_KEY`. Only `weekly-maintenance.yml` names it. The
weekly run rebuilds the release production already runs (the version label of the `deployed`
image, its `+maint` suffix dropped) with refreshed base-image digests, through the same
gates (Trivy, smoke test, signature) and the same automatic rollback as any deploy. Details:
`docs/operations.md`, "weekly-maintenance".

## Consequences

- The spec's auto-promotion works, and base-image fixes reach production within a week without
  a person.
- The unapproved path can only ship code a person already approved: it rebuilds the release that
  is running, never the newest `server-v*` tag, which may still await its approval or may have
  been rolled back. Code changes still need a `production` approval.
- The Android release keeps both of its approvals (`release` to sign, `production` to publish);
  only this weekly server rebuild skips one.
- Anyone who can change a workflow on `main` can deploy without approval. Only the two admins
  can, and every change to `main` is a reviewed pull request; the `production` gate still
  covers every other deploy.
- The five deploy secrets live in two environments, so each rotation updates both
  (`docs/runbooks/github-setup.md` must say so).
- A weekly deploy that fails rolls back and alerts both admins through the `ptt-weekly`
  Healthchecks.io check.
