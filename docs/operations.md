# Operations — milestone 6 design

How the system keeps itself running: monitoring and alerts, the nightly snapshot, automatic
deploy rollback, APK publishing, the scheduled workflows, the security gates, and the backup
restore drill. [`spec.md`](spec.md) ("CI/CD and updates", "Operations") stays authoritative; this
document fills in what the spec leaves open, and the implementation PRs of milestone 6 build
against it. The wire format of `check_in` and the app-update endpoints is in
[`protocol.md`](protocol.md) §9 and §14.

The rule for every alert: it reaches **both admins**, and only when a person has to act.

Names marked *(new)* do not exist in the code yet; the milestone 6 PRs add them under exactly
these names.

## Monitoring

### Healthchecks.io

One Healthchecks.io project (free Hobbyist plan: 20 checks, 100 log entries per check) holds
every check below. Its notification integrations are **two email integrations, one per admin**,
attached to every check. Healthchecks.io has no SMS on the free plan (SMS and WhatsApp credits
start with the Business plan, <https://healthchecks.io/pricing/>), so alerts are email only
([ADR 0013](adr/0013-healthchecks-carries-every-alert.md); both admins accepted dropping SMS on
2026-11-02). The account login lives in the shared password-manager vault. Both admins are also
members of the project's team, each with their own Healthchecks.io account, so that each gets
the daily reminders of [Re-alerting](#re-alerting).

Checks are addressed by **slug** under the project's ping key,
`https://hc-ping.com/<ping-key>/<slug>[/fail]`
(<https://healthchecks.io/docs/http_api/>). The slugs are fixed in code; the checks are created
by hand in the setup runbook with the period and grace below (auto-provisioning with `?create=1`
is not used, because it would create checks with default timing).

| Slug | Pinged by | Schedule | Grace | `/fail` when |
| --- | --- | --- | --- | --- |
| `ptt-uptime` | `pttd`, every 5 min | period 5 min | 5 min | `/healthz` would fail (database unavailable or schema behind); body: the reason |
| `ptt-fcm` | `pttd`, every 6 h (first run 1 min after start) | period 6 h | 1 h | the FCM validate-only send fails, or FCM is not configured; body: the error class |
| `ptt-phones` | `pttd`, every hour (first run 5 min after start) | period 1 h | 30 min | a phone needs attention ([Phone heartbeat](#phone-heartbeat)); body: the devices |
| `ptt-snapshot` | host systemd timer | cron `0 2 * * *`, America/Los_Angeles | 1 h | the nightly snapshot failed; body: the last lines of its output |
| `ptt-weekly` | `weekly-maintenance` workflow | cron `0 3 * * 1`, America/Los_Angeles | 3 h | the run failed (build, scan, smoke, deploy, or rolled back) |
| `ptt-security` | `nightly-security` workflow | cron `0 5 * * *`, America/Los_Angeles | 3 h | the scan itself failed, or there is no `deployed` tag (not a finding: findings open issues) |

The two workflow checks exist because GitHub emails a failed scheduled run only to the person
who last edited its cron line, which is one admin.

A missed ping (late beyond the grace) and a `/fail` both email both admins. The email includes
the last ping's body: the alert email template shows the last 10 000 bytes of it
(`templates/emails/alert-body-html.html` in the healthchecks repository,
<https://github.com/healthchecks/healthchecks>), and each ping stores up to 100 kB
(<https://healthchecks.io/docs/http_api/>). So the body is how an alert says what is wrong.
Healthchecks.io emails on a state change only (up to down, down to up); see
[Re-alerting](#re-alerting) for how a still-failing check speaks up again.

**One outage, several emails.** When the server or its host is down, every check `pttd` pings goes
down on its own timer: `ptt-uptime` first (within 10 minutes), then `ptt-phones` (within
1.5 hours) and `ptt-fcm` (within 7 hours), so an outage that lasts long enough sends up to three
"down" emails, plus `ptt-snapshot` if it spans 02:00 Pacific, and as many "up" emails afterwards.
Accepted: they arrive hours apart, each names its check, and grouping them would need a
dependency feature Healthchecks.io does not have. The first one is the one that matters.

Pings are best effort: a `POST` with a 10-second timeout, failures logged at `warn`
(without the URL) and never retried within a cycle; serving never depends on them. Healthchecks
asks for at most 5 pings per minute per check, which these schedules stay far below.

### Configuration

The ping key lets anyone who holds it mark our checks up or down, so it is a secret like the FCM
key: a sops file in `infra/secrets/` *(new: `healthchecks-ping-key.sops.txt`, a bare value that
sops stores in its binary format; `infra/secrets/README.md` describes the naming)*, listed in prod
`app_secrets` with `dest: healthchecks-ping-key`, decrypted by Ansible's `app` role to
`/opt/ptt/secrets/healthchecks-ping-key` (0600, uid 65532). Its decrypted content is the bare key.

| Variable *(new)* | Default | Meaning |
| --- | --- | --- |
| `PTTD_HEALTHCHECKS_PING_KEY_FILE` | unset | File holding the project ping key (read with `config.ReadSecret`; setting `PTTD_HEALTHCHECKS_PING_KEY` directly is an error). Unset or empty: `pttd` logs one `info` line and sends no pings |
| `PTTD_HEALTHCHECKS_URL` | `https://hc-ping.com` | Ping base URL (a plain setting, so tests can point it at a local fake) |

The `app` role writes `PTTD_HEALTHCHECKS_PING_KEY_FILE=/run/secrets/ptt/healthchecks-ping-key` to
`/opt/ptt/.env` when `app_secrets` lists the key, as it does for the FCM key, and `compose.yaml`
passes it through with an empty default. CI (`compose.ci.yaml`, the `server-image` job) and local
runs leave it unset and run without monitoring. The scheduled workflows read the same key from a
repository secret `HC_PING_KEY` *(new)*; the setup runbook keeps both copies in step.

### Uptime

Every 5 minutes `pttd` runs the same check as `/healthz` (`App.Health`) and pings `ptt-uptime`
on success, or `ptt-uptime/fail` with the reason (`database_unavailable`, `schema_outdated`). A
dead process, a dead host, or a broken network sends nothing, and the check goes down 10 minutes
after the last ping at most. The ping proves `pttd` is alive and can reach the internet; it does
not prove that Caddy and TLS answer from outside. The deploy and `infra` workflows check that
path (`check-prod.sh`) after each change, and Caddy renews certificates by itself.

### FCM key

Every 6 hours `pttd` calls `Sender.ValidateOnly` (it exists in `server/internal/push` but nothing
calls it yet) and pings `ptt-fcm`, or `ptt-fcm/fail` with the error class. With pushes disabled
(no `PTTD_FCM_CREDENTIALS_FILE`) it pings `/fail` with `fcm not configured` when the ping key is
set: a production server without FCM pages nobody. With `PTTD_FCM_FAKE` it pings success.

### Phone heartbeat

The spec: "A phone silent for 48 hours, or with a red checklist item, triggers an email to both
admins naming the device." The server has no mail setup, so the `ptt-phones` check is the mailer
([ADR 0013](adr/0013-healthchecks-carries-every-alert.md)).

Every hour `pttd` evaluates every device with `revoked_at IS NULL` and `is_test = 0` (test
devices are excluded) and finds the ones that need attention:

| Reason | Condition |
| --- | --- |
| silent | `last_seen_at` is more than 48 h ago (see the restart rule below) |
| red checklist | `checklist_status` has a `false` item (the same test as `GET /v1/contacts`, `checklistFailing`) |
| no push token | `fcm_token` is null (never registered, or cleared because FCM reported it unregistered) |

**Restart rule.** After the server itself was down, every phone looks silent. So at startup
`pttd` takes the newest `last_seen_at` of all non-test devices; if it is more than 24 h old (no
phone reached the server for a day, so the outage was the server's), silence is measured from
the process start instead of from `last_seen_at` until each device is seen again. Phones reach
the server at least daily (`check_in` reply, update check), so real silence is still reported
within about 48 h of the restart. A normal restart (a deploy) changes nothing.

If no device needs attention, `pttd` pings `ptt-phones` with an empty body. Otherwise it pings
`ptt-phones/fail` with a plain-text body, one line per device, sorted by person then device name,
at most 2 kB (longer bodies end with `... and N more`):

```text
3 phones need attention (pttd 0.6.0, 2026-11-02 18:00 UTC):
Alex / Alex's phone (0b7e4c1d-9a2f-4e68-b3c5-71d0a9e2f845): silent since 2026-10-31 09:12 UTC (56 h); app 0.5.0
Sam / Pixel 10a (6f1d...): red checklist: battery, full_screen; app 0.6.0
Sam / old Pixel (9c2e...): no push token; app 0.4.0
Run `pttd admin devices` over SSH for details; the app's setup checklist shows how to fix each item.
```

Each line has the person's display name, the device name, the device id (for
`pttd admin test-push <device>` and `revoke`), the reasons, red checklist keys by their wire name
(protocol §6), and the reported `app_version`. Nothing about pages, sessions, or who talked to
whom. The names do leave the server for Healthchecks.io and the admins' mailboxes; that is what
"an email naming the device" requires.

#### Re-alerting

Healthchecks.io emails when a check changes state, not on every `/fail`. Two things make a
still-failing check speak up again:

- **Daily reminders, for every check.** Healthchecks.io "can send hourly or daily email reminders
  if any check is down in any of your projects" (Account Settings, Email Reports;
  <https://healthchecks.io/docs/configuring_notifications/>). Reminders go to each team member's
  own account email, not to the integrations, which is why both admins are team members. Each
  admin turns on **daily** reminders in the setup runbook. A reminder lists the checks that are
  down (`templates/emails/nag-summary-html.html`) but not their ping bodies; the check's page has
  the current body. This is the quiet choice: one email a day while anything is down, no fake
  "up" emails, and no code.
- **A new problem, for `ptt-phones` only.** A reminder does not say that a second phone joined
  the list. So `pttd` keeps, in memory, the set of (device, reason) pairs it last reported:
  - the set becomes non-empty: `/fail` (one "down" email);
  - the set gains a pair it did not have while the check is already down: a success ping with
    the body `re-alert follows; not resolved`, then `/fail` with the new body. Both admins get
    an "up" email immediately followed by a "down" email with the current list; that only
    happens when there is something new to read;
  - the set changes otherwise but stays non-empty: `/fail` with the new body (no email; the
    check page and the next reminder cover it);
  - the set becomes empty: success (one "up" email: everything is fine again).

After a restart the in-memory set is empty, and the first evaluation just pings `/fail` with the
current list when it is non-empty. If the check was already down, that sends no email: a pair
that appeared while `pttd` was restarting is not announced as new. Accepted; the daily reminder
and the check page still show it. No state is persisted for this.

## Nightly snapshot

Spec: nightly at 02:00 Pacific, `VACUUM INTO` a consistent local copy, 7 kept, pinging its own
check. It is Ansible's `snapshot` role *(new)*.

**How it runs.** The `pttd` image is distroless (no shell, no `sqlite3`), and installing `sqlite3`
on the host would put a second SQLite version next to the one `pttd` links. So the snapshot is a
new `pttd` subcommand run inside the running container:

```sh
docker compose -f /opt/ptt/compose.yaml exec -T pttd /pttd admin snapshot
```

`pttd admin snapshot` *(new)* opens `PTTD_DB_PATH` (existing database only), writes
`$PTTD_SNAPSHOT_DIR/nightly-<UTC timestamp>-v<schema version>.db` with `db.Snapshot`
(`VACUUM INTO`, the same timestamp format as the pre-migration snapshots), opens the copy and runs
`PRAGMA quick_check`, deletes the copy and fails unless it says `ok`, then deletes all but the 7
newest `nightly-*` files. It prints the file name and size. Pre-migration snapshots are pruned
separately (`PrunePreMigrateSnapshots` ignores other names) and nightly pruning ignores them, so
neither job can delete the other's files. `exec` runs as the container user (65532), so the files
have the right owner, and SQLite's WAL lets the copy run while `pttd` serves.

**The role** installs:

- `/usr/local/sbin/ptt-nightly-snapshot` (root, 0755): runs the command above, captures its
  output, and pings `https://hc-ping.com/<key>/ptt-snapshot/<exit code>` with the last 50 lines as
  the body (`curl -fsS -m 10 --retry 3 --data-binary @-`). The key is read from
  `/opt/ptt/secrets/healthchecks-ping-key`; if that file is absent the script skips the ping and
  still exits with the snapshot's status. It installs `curl` if the base image lacks it.
- `ptt-snapshot.service` (`Type=oneshot`, runs the script) and `ptt-snapshot.timer`
  (`OnCalendar=*-*-* 02:00:00 America/Los_Angeles`, `Persistent=true`, so a snapshot missed while
  the host was off runs at boot).

If `pttd` is not running at 02:00 (a deploy in progress, a crash), `exec` fails, the check gets
`/fail`, and both admins hear about it. The role belongs in `site.yml`, after `app`.

**Interaction with Vultr's backup.** The Vultr daily backup is set to 10:00 UTC
(`backup_hour_utc`, `infra/tofu/prod`): 03:00 PDT, an hour after the snapshot in summer, but
02:00 PST in winter, when the backup may start before that night's snapshot and contain the
previous night's copy. That is acceptable: the snapshot takes seconds, the live WAL database in
the backup is crash-consistent anyway, and moving the backup to 11:00 UTC would collide with the
04:00 PDT reboot window. Unchanged. Pre-migration snapshots are unaffected by any of this; the
nightly job never touches them.

Vultr's backups also contain `/opt/ptt/secrets` in plaintext (the FCM key, the ping key), as they
already do today. Anyone with the Vultr account can read them, which the spec already accepts by
keeping the Vultr token as a top-tier secret.

## Server deploys and automatic rollback

Spec, "Server deploys" step 6: "If health fails within 2 minutes, CI restores the previous digest
and the pre-migration snapshot, then fails the job loudly."

### Where it lives

The `deploy` job moves out of `server-release.yml` into a reusable workflow,
`.github/workflows/deploy-prod.yml` *(new)*, called by `server-release`, `weekly-maintenance` and
`rollback-drill`. Inputs: `image` (pinned by digest), `tag` (the release tag whose `deploy/` and
`infra/ansible/` to use), `environment` (`production` or `maintenance`, see
[weekly-maintenance](#weekly-maintenance)), and `drill` (boolean, default `false`). It keeps the
`deploy-production` concurrency group, so a deploy, a rollback and an `infra` Ansible run never
overlap.

### Before Ansible

The job records, besides the previous image (`deployed-image.sh`, as today):

- `deploy_start`: the host's UTC time, `ssh ptt-prod date -u +%Y%m%dT%H%M%S.000Z` (the
  snapshot timestamp format; the container shares the host clock), taken just before Ansible
  starts;
- `before_schema`: the database's schema version. It is `schema_version` from `/internal/status`
  when that answers with `db_ok: true` (read in the same loop as `wait-no-sessions.sh`; the
  internal listener answers even when `/healthz` fails). Otherwise (`pttd` is not running, or
  answers with `db_ok: false`), it is read before Ansible runs with
  `docker compose -f /opt/ptt/compose.yaml run --rm --no-deps pttd migrate status` (the
  highest `VERSION` whose `STATE` is `applied`; read-only, so safe while `pttd` runs). If neither
  works, `before_schema` is unknown.

### Did this deploy migrate?

The answer comes from the database, never from the tag's `server/migrations/`: the drill's
migration is in no tree's `server/migrations/` (see [the drill](#proving-it-the-rollback-drill)),
so a tag-based answer would say "no" and leave production on schema 99999.

After a failure, `rollback-prod.sh` reads `after_schema` the same two ways: from
`/internal/status` before it stops `pttd` (when it answers with `db_ok: true`), otherwise after
stopping it with
`docker compose -f /opt/ptt/compose.yaml run --rm --no-deps pttd migrate status`. That command
runs the image in `compose.yaml` as Ansible left it, which is the image this deploy installed
whenever Ansible got far enough to start it, and so carries every migration the deploy could have
applied (`migrate status` lists only the migrations its own binary knows).

**This deploy migrated** exactly when `before_schema` and `after_schema` are both known and
`after_schema > before_schema`. An unknown value means "cannot tell": the rollback restores the
image only, with a loud warning, and a person decides about the database.

**Its pre-migration snapshot** is the newest `pre-migrate-*-v<before_schema>.db` in
`/data/snapshots` whose timestamp is **at or after `deploy_start`**. Nothing older qualifies.
`pttd` reuses a snapshot up to 10 minutes old (`db.SnapshotReuseWindow`) instead of writing a
new one; such a snapshot predates this deploy and may miss writes made since, so it is never
selected. If the deploy migrated and only older snapshots exist, the rollback restores the image
only, with a loud warning. The old binary then runs on the newer schema, which expand-then-contract
migrations make safe ("rollback mode", `server/README.md`).

### The rollback

Trigger: the Ansible step or the health-and-smoke step (`check-prod.sh`, `/healthz` within 2
minutes, then `smoke.sh`) failed, **and** there is a previous image. A first deploy has nothing to
roll back to and only fails. A cancelled or timed-out deploy job gets no rollback (intended: a
person stopped it, or something is stuck); the runbook "Server down, VPS reachable" covers it.

`.github/scripts/rollback-prod.sh` *(new)* then:

1. Reads `after_schema` from `/internal/status` if `pttd` answers, and stops `pttd`
   (`docker compose stop pttd`). If `after_schema` is still unknown, reads it with
   `migrate status` as above.
2. If this deploy migrated and its pre-migration snapshot exists:
   - **moves** `pttd.db`, `pttd.db-wal` and `pttd.db-shm` into `/data/rolled-back-<deploy_start>/`
     (deleting any older `rolled-back-*` directory, so one is kept for a person to inspect);
   - **copies** the snapshot to `/data/pttd.db` (`install -o 65532 -g 65532 -m 0600`);
   - **renames** the snapshot in place to `/data/snapshots/restored-<its name>`. Without the
     `pre-migrate-` prefix, `pttd` can never reuse it for a later deploy (it no longer reflects
     the database once the restored server takes writes) and `PrunePreMigrateSnapshots` ignores
     it. Older `restored-*` files are deleted, so one is kept next to the failed database.

   Whatever the new release wrote in its few minutes is lost (a page created then,
   `last_seen_at` updates, a roster change). Accepted: a deploy waits for no active sessions
   first, the window is a few minutes, and the failed database is kept for a person to look at.

   If this deploy migrated but has no qualifying snapshot, or migration cannot be told, nothing
   is restored: `::warning::` lines and the job summary say so in capitals, naming the snapshots
   that exist. If it did not migrate, there is nothing to restore.
3. Finds the previous release's tag from the previous image's `org.opencontainers.image.version`
   label (`docker image inspect` on the host; the image is still there), checks out
   `server-v<version>` (for a maintenance image, its base release; see below), and runs **that
   tree's** `deploy.yml` with `-e pttd_image=<previous image>`. That puts back the previous
   `compose.yaml` and `Caddyfile` too, in case the breakage was there. Its collections are
   installed from **that tree's** `requirements.yml` into a fresh `ANSIBLE_COLLECTIONS_PATH`
   (`$RUNNER_TEMP/rollback-collections`), so the old playbook runs with the collections it was
   written for. `ansible-core` stays the version this workflow pins (`ANSIBLE_CORE_VERSION`); a
   core bump that broke the old playbook would fail this step, and step 5 reports a failed
   rollback. Without the label, it uses the current tree and warns.

   The sops-encrypted secrets are the exception: they come from the **failed release's** tree
   (`-e app_secrets_src_dir=<that tree>/infra/secrets`), because secrets are current state, not
   versioned code; a rotated key must not be rolled back with the code. So a secret file that a
   release needs must not be removed in the release that follows it: that release's rollback
   would find it missing.
4. Runs `check-prod.sh` again.
5. Writes the job summary (what failed, the image and snapshot it restored, where the failed
   database is kept) and fails the job: `::error::Deploy of <image> failed; rolled back to
   <previous>`. If step 4 failed too, the error says the rollback failed and a person is needed
   (runbook: "Server down, VPS reachable").

### The `deployed` tag

After a successful health check and smoke test, a small follow-up job (`needs: deploy`,
`packages: write`, no environment secrets) moves the GHCR tag `deployed` *(new)* to the digest
just deployed (`docker buildx imagetools create --tag ghcr.io/<owner>/<repo>/pttd:deployed
<image@digest>`). The deploy job itself stays at `packages: read`, because its token is handed
to the host for `docker login`. A rolled-back deploy never moves it, so after a
successful rollback it still names what production runs. Production itself still runs by digest;
the tag is how `weekly-maintenance`, `nightly-security` and the drill find the running release
without SSH.

The tag first appears on the first successful deploy through `deploy-prod.yml`. The milestone 6
rollout deploys a release right after that PR merges, before the scheduled workflows are
enabled; if that has not happened, the setup runbook seeds the tag by hand from the digest
`deployed-image.sh` prints. Until it exists, `weekly-maintenance` and the drill fail in their first
step (`weekly-maintenance` pings `ptt-weekly/fail`), and `nightly-security` scans the lockfiles
only and pings `ptt-security/fail` with `no deployed tag`, so a missing tag is noticed.

### Proving it: the rollback drill

The exit check "a deliberately broken release rolls back automatically" runs a dedicated
workflow, `rollback-drill.yml` *(new)*:

- `workflow_dispatch` only, from `main`, with a required input `confirm` that must equal
  `break production`; anything else fails the first step. No schedule, no tag trigger, no
  `workflow_call`. Its deploy job uses the `production` environment, so it also needs an
  admin's approval, like any deploy.
- **Prerequisites, checked first.** The `deployed` tag exists, and the release it names (its
  version label, `+maint...` dropped) contains the drill files below. A release cut before the
  drill PR does not; the drill then fails at once with "deploy a release that contains the drill
  build first", before building anything or touching production.
- It builds that release's tag with the Go build tag `drill` *(new)*. That build differs from the
  release in two ways only: it embeds one extra migration, `99999_drill.sql`
  (`CREATE TABLE drill_marker (id INTEGER PRIMARY KEY) STRICT;`), and its `/healthz` returns
  `503 {"status":"unavailable","reason":"drill"}` once migrated. The migration lives in
  `server/internal/db/drill/` *(new)*, embedded only by a `//go:build drill` file, and never in
  `server/migrations/`, which every build embeds. Release builds never use the tag, so neither
  exists in a real image. The image is pushed by digest with the GHCR tag `drill-<run id>`, is not
  signed, and never gets a version tag.
- It calls `deploy-prod.yml` with `drill: true`. The deploy migrates (to 99999), fails health,
  and rolls back: snapshot restored, previous image redeployed. With `drill: true` the last step
  inverts: the job **passes** only if the rollback ran, restored a snapshot taken at or after
  `deploy_start` and renamed it to `restored-*`, and production is healthy on the previous image
  with schema version `before_schema`; it fails otherwise. A drill build that unexpectedly
  passes the health check is rolled back all the same, so production never stays on it, and the
  drill fails.

Migration number 99999 is deliberate: if a restore ever failed and left it applied, goose would
refuse the next real migration (00006 would be "missing") instead of silently treating it as
applied, so the problem shows up at the next deploy rather than as a lost migration.

Production is down for the drill's few minutes (`compose up --wait` waits up to 5 minutes for
the unhealthy container before Ansible fails), so it runs at a quiet time, announced to the family.
Expect monitoring to notice: the drill build's `pttd` pings `ptt-uptime/fail` with
`drill`, so both admins get a "down" email and then an "up" email once the previous image is back.
The drill does not pause the check: a paused check resumes on its next ping unless its
`manual_resume` option is set (`hc/api/models.py` in the healthchecks repository), and a forgotten
manual pause would silence real outages. The emails also prove the alert path. The drill also
runs once a year with the restore drill.

## Publishing an APK

Spec: `android-release` "builds and signs the release APK (keystore from the protected `release`
environment), attaches it to a GitHub Release, uploads it to the server, and updates
`/v1/app/latest`." How it uploads: [ADR 0011](adr/0011-apk-published-over-the-deploy-path.md).

### On the server

| Path (host) | In the container | Owner, mode | Content |
| --- | --- | --- | --- |
| `/opt/ptt/apk/` | `/apk` (read-only bind mount) | root:root 0755 | the published APKs |
| `/opt/ptt/apk/ptt-<version name>.apk` | | root:root 0644 | one per kept version |
| `/opt/ptt/apk/latest.json` | | root:root 0644 | metadata of the newest |

`compose.yaml` mounts `./apk:/apk:ro` and sets `PTTD_APK_DIR: /apk` *(new variable; unset: both
app endpoints answer `404 not_found`)*. The `app` role creates the directory. `pttd` can read the
APKs but never write them, and they stay out of `/data`, which holds only `pttd`'s own state.
Vultr's backups cover both.

`latest.json`:

```json
{
  "version_name": "0.5.0",
  "version_code": 5000,
  "sha256": "<64 lowercase hex>",
  "size": 15728640,
  "min_supported_version_code": 4000,
  "file": "ptt-0.5.0.apk"
}
```

`pttd` reads it on each `GET /v1/app/latest` and answers `404` unless it parses, `file` is
`ptt-<version_name>.apk`, the file exists and its size equals `size`,
`version_code` matches the formula of protocol §14.1, and `min_supported_version_code <= version_code`.
It does not hash the APK per request; the publish step checked the hash. A bad file is logged
once per change (by modification time). `/internal/status` gains `app_latest` *(new)*: the
published version name, or `null`; `pttd admin status` prints it.

### The publish step

`.github/scripts/publish-apk.sh` *(new)*, run over the existing Tailscale + `ssh ptt-prod` path
(`ssh-setup.sh`), with the APK, its SHA-256, and the version from the build job:

1. `scp` the APK to `/tmp/ptt-apk-<run id>` on the host.
2. On the host (`sudo`): check its SHA-256 equals the build's. If `ptt-<version>.apk` already
   exists: same hash, go on (a rerun is idempotent); different hash, stop with an error
   (versions are immutable, protocol §14.1).
3. `install -o root -g root -m 0644` it as `.ptt-<version>.apk.tmp` in `/opt/ptt/apk/`, then
   `mv -f` it to `ptt-<version>.apk` (atomic rename in the same directory).
4. Write `.latest.json.tmp`, then `mv -f` to `latest.json`. The APK is in place before the
   metadata names it, so `latest` never points to a missing file.
5. Delete `ptt-*.apk` files other than the newest three versions, never the one `latest.json`
   names.
6. Verify: `/internal/status` reports `app_latest` equal to the version.

A version lower than the published one is refused unless the workflow is run by hand with
`allow_older: true`; that is only for re-pointing `latest` after deleting a bad release from the
server by hand, and phones that already installed the newer one keep it either way.

### The workflow

`android-release.yml` *(new)*, started like `server-release` (ADR 0005): called by
`release-please.yml` when the android release is created, by pushing an `android-v*` tag, or by
`workflow_dispatch` with an existing tag.

1. `build` (environment `release`, which requires an admin's approval and holds the keystore
   secrets): checks out the tag, checks that `android/version.txt` equals the tag's version,
   builds and signs `assembleRelease`, checks with `apksigner verify --print-certs` that the
   signer is the release certificate (its SHA-256 is a repository variable
   `ANDROID_SIGNING_CERT_SHA256` *(new)*), checks the APK's `versionCode` equals the formula, and
   reads `android/min-supported-version.txt` *(new)*. It attaches `ptt-<version>.apk` and
   `ptt-<version>.apk.sha256` to the GitHub Release and uploads both as a workflow artifact.
2. `publish` (environment `production`: a second approval, the same gate as a server deploy,
   and the only environment holding the Tailscale and SSH secrets): downloads the artifact and
   runs `publish-apk.sh`. It shares the `deploy-production` concurrency group.

So every Android release takes two approvals, one to sign and one to publish. That is decided
(2026-11-02): the signing key and the production host stay behind separate gates, and an app
release is rare enough that the second click costs little.

## weekly-maintenance

Spec: "Monday 03:00 Pacific. Rebuilds the server image on the latest base image even without
code changes and ships it through the same pipeline. Auto-promotes to production if the CI smoke
tests pass."

**What it builds.** The server's Dockerfile pins each base image by tag and digest, and Renovate
auto-merges digest updates as `chore(deps)` commits, which do not cut a release. Those base-image
fixes would sit on `main` until the next feature release. The weekly run ships them on the
code that is already released:

1. Find the release production runs: the `deployed` tag's `org.opencontainers.image.version`
   label with any `+maint...` suffix dropped, giving `T = server-v<version>`. Never the newest
   `server-v*` tag: a newer release may be waiting for its approval, or may have been rolled
   back, and the weekly run must not ship either without a person. Without the `deployed` tag
   the run fails ([The `deployed` tag](#the-deployed-tag)).
2. Check out `T`. For each `FROM <image>:<tag>@sha256:<digest>` line in `server/Dockerfile`,
   resolve the current digest of the same `<image>:<tag>` (`docker buildx imagetools inspect`) and
   substitute it. Only digests move, never tags, so a new Go minor or Debian release still
   needs a person (Renovate's rules). The code, `go.sum`, and the deploy files are `T`'s.
3. Build exactly as `server-release` does (clean build, push by digest), with version
   `<T version>+maint.<YYYYMMDD>` (the `VERSION` build arg and the
   `org.opencontainers.image.version` label) and the GHCR tag `<T version>-maint.<YYYYMMDD>`
   (OCI tags cannot contain `+`). No git tag, no GitHub Release, no changelog. Rollback reads
   the base release from the label by dropping the `+maint...` suffix.
4. The same gates as a release: Trivy (reading `.trivyignore.yaml` from `main`, as
   `nightly-security` does, so a waiver merged after `T` applies), smoke test on the runner,
   SBOM, cosign signature. The workflow signs as `weekly-maintenance.yml@refs/heads/main` and
   verifies with that identity; `server-release`'s check stays strict
   ([ADR 0005](adr/0005-release-please-calls-server-release.md)). To verify any deployed image,
   accept both workflows:
   `^https://github\.com/OWNER/REPO/\.github/workflows/(server-release\.yml@refs/(heads/main|tags/server-v[0-9][0-9A-Za-z.+-]*)|weekly-maintenance\.yml@refs/heads/main)$`
   with issuer `https://token.actions.githubusercontent.com`.
5. Deploy with `deploy-prod.yml`, `environment: maintenance`, waiting for no active sessions and
   rolling back automatically like any deploy. The run records the digest of the `deployed` tag
   when it resolves the release (step 1) and passes it as `expect_previous`; `deploy-prod`
   compares it with what the host runs, inside the `deploy-production` concurrency group and
   before any change. If an approved release deployed in the meantime, the weekly deploy fails
   with "production changed since this run resolved it" (and pings `ptt-weekly/fail`) instead of
   downgrading production; nothing was changed, and next Monday's run rebuilds the new release.
6. Ping `ptt-weekly` with the run's result.

It deploys every week even when no digest moved. The restart is harmless (it waits for no
sessions), and it exercises the deploy and rollback path weekly. Because it rebuilds what is
already running, a release whose deploy was rolled back is never redeployed by the weekly run;
it stays where the failed deploy left it, for a person.

**Auto-promote without approval** (decided 2026-11-02). The `production` environment requires an
admin's approval, which a Monday 03:00 run cannot wait for. A second environment, `maintenance`
*(new)*, has no required reviewers, allows only the `main` branch, and holds its own copies of
the deploy secrets (`TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`, `DEPLOY_SSH_KEY`, `PTT_DOMAIN`, `SOPS_AGE_KEY`).
Only `weekly-maintenance.yml` uses it. [ADR 0012](adr/0012-weekly-maintenance-deploys-without-approval.md)
records the trade-off: anyone who can push a workflow to `main` (both admins only) can deploy
without a second look, and the deploy secrets now live in two places to rotate. Every other
deploy keeps its approval, and the Android release keeps both of its approvals (`release` to
sign, `production` to publish; [Publishing an APK](#the-workflow)); both were decided on
2026-11-02.

**Schedule.** GitHub cron is UTC, so the workflow has two schedules, `0 10 * * 1` and
`0 11 * * 1`, and its first step exits successfully unless the schedule that fired is the one
that is 03:00 in America/Los_Angeles that day (10:00 UTC in PDT, 11:00 UTC in PST).
`workflow_dispatch` runs always proceed. 03:00 sits after the 02:00 snapshot and before the
04:00 reboot window.

## nightly-security

Spec: "Daily. Rescans the deployed image and lockfiles for newly published vulnerabilities;
opens an issue on any finding."

**Schedule.** `0 13 * * *` (05:00 or 06:00 Pacific), plus `workflow_dispatch`. No secrets beyond
`HC_PING_KEY`; permissions `contents: read`, `packages: read`, `issues: write`.

**The deployed image.** Trivy on `pttd:deployed` ([The `deployed` tag](#the-deployed-tag)),
vulnerabilities, all severities collected. Without the tag the run scans the lockfiles only and
pings `ptt-security/fail`.

**Lockfiles.** OSV-Scanner on `main`, recursive, as in `ci` (Go and Gradle lockfiles), and
`govulncheck` on `main`'s server module. `main`'s lockfiles are what the next release ships; the
deployed server's Go modules are covered by the image scan, which reads the binary's module
list.

**What counts as a finding.** Exactly what would fail a release (the gates below): Trivy
HIGH or CRITICAL, OSV CVSS 7.0 or higher or unscored outside Go, and every `govulncheck` finding.
Lower-severity findings would open issues nobody acts on. The spec says "any finding", so this
narrowing is [ADR 0014](adr/0014-nightly-security-reports-release-gate-findings.md).

**The same waivers.** The scans read the same waiver files as the gates, `.trivyignore.yaml` and
`osv-scanner.toml` ([Security scans as hard gates](#security-scans-as-hard-gates)), from `main`.
A waived finding opens no issue; when its waiver expires it is a finding again.

**Issues, deduplicated.** One issue per (advisory id, package), titled
`security: <id> in <package>`, labelled `security`, with a hidden marker
`<!-- nightly-security: <id> <ecosystem>/<package> -->` in the body. The run lists every issue
with the `security` label, open and closed (`gh issue list --label security --state all --json
number,state,body`, paginated), and matches markers in the bodies. It does not use
the search API, whose index lags and which may not match an HTML comment. Then, per finding and
per known issue:

- open issue with the marker, finding reported: nothing (no comment, no noise);
- no issue with the marker: open one, with the scanner, severity, installed and fixed versions,
  and where it was found (deployed image, or the lockfile path);
- an open issue whose finding no longer appears in any scan: comment "No longer reported by the
  nightly scan" and close it as completed;
- a closed issue whose finding is reported again: reopen it with a comment **only if the bot
  closed it**, that is, the actor of its latest `closed` event
  (`gh api repos/{owner}/{repo}/issues/<n>/events`) is `github-actions[bot]`. An issue an admin closed
  is an accepted risk and stays closed; the waiver files are the place to record that, and an
  admin who wants it tracked again reopens it by hand.

**Issue churn.** The marker is the scanner's own id, so the same weakness can have more than one
issue. OSV-Scanner groups a vulnerability's aliases and the issue uses one id by a fixed rule
(first `CVE-`, else `GHSA-`, else `GO-`, else the first sorted), so it does not change when the
database adds or drops an alias; the other aliases are listed in the body. Across scanners it is
not deduplicated: Trivy (the image; ecosystem `gobinary`, `debian`, ...), OSV-Scanner (the
lockfiles; `Go`, `Maven`) and `govulncheck` (a `GO-` id) give different (id, package) keys, so a
Go dependency flagged by two of them opens two issues. That is cheap to live with: each closes
itself when its scanner stops reporting it, and the issue says which scanner and where.

GitHub emails new issues to watchers, and both admins watch the repository, so a finding reaches
both of them with no extra setup. The run pings `ptt-security` with its own success or failure;
findings are not failures.

## Security scans as hard gates

Spec, "Supply chain": "`govulncheck`, OSV-Scanner on both lockfiles, and Trivy on the container
image; high or critical findings fail the build."

| Gate | Where before milestone 6 | Fails on | Gap (closed by the changes below) |
| --- | --- | --- | --- |
| `govulncheck` | `ci` / `server` (`make vuln`), a required check | any reachable vulnerability, any severity | none |
| OSV-Scanner | `ci` / `osv-scanner`, a required check. The scan step has `continue-on-error: true` only so the next step can apply the threshold; that step fails the job | CVSS 7.0 or higher | unscored advisories only warn. For Go that is intended (`govulncheck` gates them by reachability); for Gradle they are not gated at all |
| Trivy | `server-release` / `scan` only (HIGH, CRITICAL, `ignore-unfixed: false`, `exit-code: 1`) | high or critical in the release image | not run on pull requests, so a base-image or dependency PR that brings in a high finding merges green and fails only at release time |

Changes (the CI PR). Beyond the list, `ci` / `waivers` runs `.github/scripts/check-waivers.py`,
which fails a waiver that has no statement or reason or whose date is past the 30-day
(`.trivyignore.yaml`) or 90-day (`osv-scanner.toml`) limit. `server-release` / `scan` reads
`.trivyignore.yaml` from `main`, not the tag's tree, as the weekly scan will.

1. **Trivy on pull requests.** Add a Trivy step to `ci` / `server-image` against the `pttd:ci`
   image it already builds, with the same settings and pinned version as `server-release`. The job
   is already a required check.
2. **Unscored non-Go OSV findings fail.** In the OSV threshold step, an unscored finding fails
   unless its ecosystem is `Go`. A false positive is waived in `osv-scanner.toml` *(new)* with
   `[[IgnoredVulns]]`, a `reason`, and an `ignoreUntil` date at most 90 days out.
3. **A reviewed way past unfixable findings.** Trivy fails on unfixed findings by design, but an
   unfixed high finding in a base image would block every weekly run until upstream ships a
   fix. Waivers go in `.trivyignore.yaml` *(new)*, each with a statement and an `expired_at`
   at most 30 days out, added by pull request (so a person decided). Both scans in
   `server-release`, `weekly-maintenance`, `ci` and `nightly-security` read it, and
   `nightly-security` reads `osv-scanner.toml` too.
4. **The same gates in the scheduled paths.** `weekly-maintenance` runs Trivy like a release;
   `nightly-security` reports instead of failing (it scans what is already shipped).

## Restore drill: a Vultr backup onto a scratch VPS

The exit check and the yearly calendar item. The full runbook is a later PR
(`docs/runbooks/restore-drill.md`); this is what it proves and its rough steps.

**What it proves.** The newest Vultr backup boots, holds a consistent database with every device
and the current roster, has a recent nightly snapshot next to it, and `pttd` from that disk starts
and serves it. All of that without the scratch server ever touching the family's phones, the real
domain, the tailnet identity of production, or the monitoring.

**Isolation.** The restored disk is a copy of production, so on its own it would join the
tailnet as `ptt-prod`, send `check_in` and other pushes with the real FCM key, ping
Healthchecks.io with the real key (masking a real outage), and ask Let's Encrypt for the real
name. Before its first normal boot it must lose all of that.

**Rough steps.**

1. In Vultr, create a scratch instance (same plan, Seattle) **outside OpenTofu**: no reserved IP,
   no DNS record, its own default firewall group with nothing open. Restore the latest
   automatic backup of production onto it.
2. Boot it from a rescue ISO (Vultr's ISO library) instead of its disk. Mount the disk, then:
   delete `/var/lib/tailscale/` (the production node identity) and leave a one-time,
   non-reusable `tag:ptt-server` auth key with hostname `ptt-drill` for a first-boot
   `tailscale up`; delete `/opt/ptt/secrets/*` (FCM and ping keys); disable the snapshot timer;
   make Docker not start the compose project at boot (`restart: unless-stopped` would otherwise
   bring back Caddy and `pttd`).
3. Boot the disk. Connect with `ssh` over Tailscale to `ptt-drill`.
4. Check the data with the image already on the disk (no `sqlite3` needed):
   `docker compose -f /opt/ptt/compose.yaml run --rm --no-deps pttd migrate status` and
   `... pttd admin devices`
   (device count and names match production), and that the newest `nightly-*.db` exists and was
   under 24 h old at backup time (its integrity was checked when it was written).
5. Start `pttd` alone (`docker compose up -d --no-deps pttd`, FCM unset so pushes are disabled,
   no ping key) and check `curl 127.0.0.1:9090/internal/status`: healthy, schema at the
   expected version, roster present. Caddy is never started.
6. Record the result and timings in the runbook log, destroy the instance, and remove `ptt-drill`
   from the tailnet.

The spec's target for a real restore is under 60 minutes; the drill times steps 1-5 against it.
