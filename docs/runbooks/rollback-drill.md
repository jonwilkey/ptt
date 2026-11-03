# Runbook: rollback drill

Proves that a deliberately broken release rolls back by itself (milestone 6 exit check, then once
a year with the [restore drill](restore-drill.md)). The drill deploys a build of the release
production already runs, plus one extra migration and a `/healthz` that answers `503`. The
deploy must migrate, fail health, restore the pre-migration snapshot, and put the previous image
back. Design and rules: [docs/operations.md](../operations.md#proving-it-the-rollback-drill).

Production is **down for a few minutes**, so pick a quiet time and tell the family first. Both
admins get a `ptt-uptime` "down" email and then an "up" email: that is expected, and it proves the
alert path ([healthchecks-setup.md](healthchecks-setup.md)). Do not pause the check.

## Before you start

- A release has been deployed through `deploy-prod` (so the GHCR tag `pttd:deployed` exists) and
  that release **contains the drill files** (`rollback-drill.yml`, the `drill` build tag). A
  release cut before they merged does not; the drill then fails at once with "deploy a release
  that contains the drill build first", before touching production. If so, merge the pending
  server release PR and approve its deploy first.
- No phone is mid-session (`pttd admin status` shows `active sessions: 0`):

  ```sh
  ssh <admin>@ptt-prod
  sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin status
  ```

- Write down the current state to compare afterwards:
  `version` and `schema version` from the command above (the drill ends with the same values).
- Nothing else is deploying (the `deploy-production` concurrency group queues, but do not stack
  an `infra` run or a release on top of the drill).

## Run it

1. **Actions > rollback-drill > Run workflow**, branch `main`, input `confirm` =
   `break production`. From a terminal:

   ```sh
   gh workflow run rollback-drill.yml --ref main -f confirm='break production'
   gh run list --workflow rollback-drill.yml --limit 1
   ```

2. The `build` job builds the drill image (a few minutes). Then the deploy job waits for an
   approval of the `production` environment: approve it (**Review deployments**) when the family
   has been told.
3. Watch the deploy job (`gh run watch <run-id>`). It waits for no active sessions, runs Ansible
   with the drill image (the new `pttd` migrates to schema 99999 and answers `503`), fails
   health within 2 minutes, and rolls back: restores the snapshot taken at the start of this
   deploy and redeploys the previous image. Allow 10 to 15 minutes in all; Ansible waits up to
   5 minutes for the unhealthy container.

## What success looks like

All of these:

- The run's overall result is **success**. In this mode the deploy job passes only if the
  rollback happened, so a green run means: the rollback ran, a snapshot taken at or after the
  deploy's start was restored, and production is healthy on the previous image with its
  previous schema version. A red run is a failed drill, whatever the cause: read the job
  summary.
- The job summary (the run's page) names what failed, the image and snapshot it restored and
  where the failed database is kept. It carries no `::warning::` in capitals about a missing
  snapshot.
- Production answers from the outside, and the version and schema are what you wrote down:

  ```sh
  curl -fsS https://<server-name>/healthz
  ssh <admin>@ptt-prod 'sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin status'
  ```

  `database: ok`, `schema version` equal to the value before the drill (not `99999`), `version`
  the same.
- On the host, the restore left its marks:

  ```sh
  ssh <admin>@ptt-prod 'sudo ls -l /data/snapshots | grep restored-; sudo ls -d /data/rolled-back-*'
  ```

  One `restored-pre-migrate-*` file (the snapshot, renamed so it can never be reused) and one
  `/data/rolled-back-<timestamp>/` directory (the failed database, kept for inspection).
- Both admins received the `ptt-uptime` "down" and then "up" emails. If only one did, fix that
  admin's Healthchecks.io integration before calling the drill done.
- An enrolled phone still works afterwards (send a page; or Test alert in its setup checklist).

## Afterwards

- Delete the `drill-<run id>` image tag from the GHCR package (`pttd`) if you like; it is never
  deployed again.
- Delete `/data/rolled-back-<timestamp>/` when you have looked at it, or leave it: the next
  rollback replaces it.
- Record the date and result in the restore drill log
  ([restore-drill.md](restore-drill.md#drill-log)).

## If the drill fails

| Symptom | Meaning and next step |
| --- | --- |
| Fails in the first step | `confirm` was not exactly `break production`, or the run is not on `main`, or the `deployed` tag is missing or older than the drill: see "Before you start" |
| Failed, production healthy on the previous image | The rollback ran but a check (restored snapshot, schema version) did not hold. Read the summary; production is fine, the drill is not proven |
| Production still down after the run | The rollback itself failed: follow "Server down, VPS reachable" in [operations.md](operations.md#server-down-vps-reachable) |
| `schema version` is `99999` or `newer than binary's` | The restore did not happen. Treat as an incident: restore the newest `pre-migrate-*` snapshot by hand ([operations.md](operations.md#a-bad-release-or-a-bad-migration)) and tell the other admin |
