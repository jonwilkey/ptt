# Runbook: operating the system

What to do when something goes wrong, and the routine upkeep, as concrete commands. It expands
the Runbook and Maintenance-calendar tables of [docs/spec.md](../spec.md#operations); the design
of the checks, the rollback and the scheduled workflows is in [docs/operations.md](../operations.md)
and is not repeated here.

Conventions. `<admin>` is your login on the server. Commands that start with `ssh` run from a
machine on the tailnet; the rest run on the server after `ssh <admin>@ptt-prod`. `dc` stands for
the compose command, which you can define once per session:

```sh
dc() { sudo docker compose -f /opt/ptt/compose.yaml "$@"; }
```

`dc exec pttd /pttd admin ...` reaches the running server; `dc run --rm --no-deps pttd ...` runs
the image once without it (the image's entrypoint is already `/pttd`).

## Reading an alert

Alerts are emails from Healthchecks.io naming a check. Both admins get them; agree who acts.
The email shows the last ping's body, which says what is wrong. Daily reminders repeat while a
check stays down.

| Check | Means | Go to |
| --- | --- | --- |
| `ptt-uptime` | `pttd` is down, unhealthy, or the host or network is down | [Server down, VPS reachable](#server-down-vps-reachable); if the host does not answer, [VPS lost or corrupted](#vps-lost-or-corrupted) |
| `ptt-fcm` | The FCM key is rejected, or FCM is not configured | [FCM key leaked or expiring](#fcm-key-leaked-or-expiring) |
| `ptt-phones` | A phone needs attention; the body names it | [A phone is reported by ptt-phones](#a-phone-is-reported-by-ptt-phones) |
| `ptt-snapshot` | The nightly snapshot failed or did not run | [The nightly snapshot failed](#the-nightly-snapshot-failed) |
| `ptt-weekly` | The Monday maintenance run failed, rolled back, or did not run | [The weekly maintenance run failed](#the-weekly-maintenance-run-failed) |
| `ptt-security` | The nightly scan failed or the `deployed` tag is missing (findings are GitHub issues, not this check) | [The nightly security scan failed](#the-nightly-security-scan-failed) |

A quick look at everything: `dc exec pttd /pttd admin status` (version, uptime, database, schema
version, active sessions and pages, published app version) and the Healthchecks.io dashboard.

## Server down, VPS reachable

```sh
ssh <admin>@ptt-prod
dc ps
dc logs --tail 100 pttd
dc exec pttd /pttd admin status
curl -fsS 127.0.0.1:9090/internal/status
```

1. `pttd` not running or restarting: read the log lines for the reason (database path, schema
   newer than the binary, a bad secret file). Restart it: `dc restart pttd`, or
   `dc up -d pttd` if the container is gone.
2. `database: ok` false or `schema version ... BEHIND`: the database or a migration is the
   problem; see [A bad release or a bad migration](#a-bad-release-or-a-bad-migration).
3. `pttd` is healthy but the app cannot connect: check Caddy (`dc logs --tail 50 caddy`),
   `curl -fsS https://<server-name>/healthz` from outside, and the certificate. `dc restart caddy`.
4. The host is up but Docker is not: `sudo systemctl status docker`, `sudo systemctl restart docker`,
   then `dc ps`.
5. Disk full: `df -h /data /var/lib/docker`; `sudo docker system prune` is safe for unused
   images; snapshots are in `/data/snapshots`.
6. If the last deploy is the suspect and was not rolled back automatically (a cancelled or
   timed-out deploy gets no rollback), rerun the **previous** release's deploy: **Actions >
   server-release > Run workflow** with the previous `server-v<version>` tag, and approve it.
7. If the host does not answer over Tailscale at all, use the Vultr web console
   ([infra/ansible/README.md](../../infra/ansible/README.md), "Lock-out recovery"), or go to
   [VPS lost or corrupted](#vps-lost-or-corrupted).

## A bad release or a bad migration

A failed deploy normally rolls itself back and its job summary says what it restored. Check
that production is healthy on the previous image: `dc exec pttd /pttd admin status`.

If the summary warns in capitals that a migrated deploy had **no qualifying snapshot** (or could
not tell whether it migrated), the image was restored but the database was not; the old binary runs
on the newer schema ("rollback mode"), which is safe for expand-then-contract migrations. A person
decides whether to restore data:

```sh
dc exec pttd /pttd admin status        # "schema version: N (newer than binary's M; rollback mode)"
sudo ls -l /data/snapshots             # pre-migrate-*-v<schema>.db, nightly-*.db, restored-*
```

To restore a snapshot by hand (this loses everything written since the snapshot; announce it and
make sure no one is mid-session):

```sh
dc stop pttd
sudo mkdir -p /data/rolled-back-manual
sudo mv /data/pttd.db /data/pttd.db-wal /data/pttd.db-shm /data/rolled-back-manual/ 2>/dev/null
sudo install -o 65532 -g 65532 -m 0600 /data/snapshots/<chosen-snapshot>.db /data/pttd.db
dc up -d pttd
dc exec pttd /pttd admin status
```

Pick the newest `pre-migrate-*` snapshot of the schema version before the bad deploy, or the
newest `nightly-*` one. Keep `/data/rolled-back-manual` until you are sure.

## VPS lost or corrupted

Target: under 60 minutes. Choose one:

**A. Restore the latest Vultr backup** (preferred; keeps data). The procedure is the one of the
[restore drill](restore-drill.md), minus the isolation: restore the newest automatic backup onto
the replacement instance, then boot normally. Do **not** remove `ptt-prod` from the tailnet: the
restored disk is the same node (it carries production's `/var/lib/tailscale`, the same `100.x`
addresses that `20-ptt-listen.conf` and node-exporter rely on), and removing it first locks admins
and CI out. Make sure the old instance is destroyed or powered off first, because two copies of
one node key conflict. Prefer restoring onto the existing instance (its **Backups > Restore**):
this keeps the reserved IP, the firewall group and the OpenTofu state. Only if the instance is
gone, restore onto a new one, move the reserved IP to it, and expect `tofu plan` drift. Then run
`site.yml` (below) and deploy the current digest.

**B. A fresh VPS from OpenTofu.** `tofu apply` in `infra/tofu/prod` recreates the instance and
reuses the reserved IP ([infra-bootstrap.md](infra-bootstrap.md), steps 6, 8 and 9; this needs a
fresh one-time Tailscale auth key and the Vultr apply key, and the lost node removed from the
tailnet first). The database is empty: restore it from a snapshot copied off the old disk if you
still have one, or use C.

Then, for A and B:

1. `ssh-keygen -R ptt-prod` (the host key changed), then run the Ansible play:
   **Actions > infra > Run workflow**, approve the plan and the Ansible job. It writes `/opt/ptt`,
   the secrets (including the Healthchecks.io ping key) and the snapshot timer.
2. Deploy the digest production should run: **Actions > server-release > Run workflow** with the
   current `server-v<version>` tag and approve it, or the `deployed` image
   (`.github/scripts/deployed-image.sh` prints the digest the host runs).
3. Check `dc exec pttd /pttd admin status` and `dc exec pttd /pttd admin devices`.
4. After a fresh disk the `deployed` tag may name an image the host no longer has; the deploy
   above moves it.

**C. No backup is usable.** Start over with a fresh server and re-enroll every phone (about
5 minutes each):

```sh
dc exec pttd /pttd admin bootstrap --reset <your-name>   # prints a QR to enroll the first admin
```

`--reset` revokes every device and deletes the roster; the family must enroll again. An admin
phone that still holds the signed roster can re-upload it to a rebuilt server instead, which
avoids re-enrolling everyone.

## Phone not receiving pages

1. On the phone, open the app's setup checklist; fix every red item. Run **Test alert**.
2. Check the phone's OEM battery settings (restricted background use, "optimized" battery for the
   app, Do Not Disturb exceptions).
3. From the server:

   ```sh
   dc exec pttd /pttd admin devices                 # find the device id and last seen
   dc exec pttd /pttd admin test-push <device-id>
   ```

   Errors are listed in [firebase-setup.md](firebase-setup.md#5-verify). `no FCM token` means
   the phone has not registered: open the app once. Silent phones show an old `LAST SEEN`.
4. If no phone receives pushes, check `ptt-fcm` and
   [FCM key leaked or expiring](#fcm-key-leaked-or-expiring).

## A phone is reported by ptt-phones

The alert body has one line per phone: person, device name, device id, the reason (silent, red
checklist item, no push token) and the app version.

| Reason | Action |
| --- | --- |
| silent for 48 h | Ask the person to open the app and check the setup checklist. If the phone is lost, [revoke it](#lost-or-stolen-phone). A phone that is switched off or abroad is not an error: it clears when seen |
| red checklist item | The line names the item (for example `battery`, `full_screen`); the person fixes it in the app's setup checklist |
| no push token | Open the app once so it registers with FCM; if it does not, `admin test-push` as above |

The check clears when no phone needs attention. A retired device that will never come back:
`dc exec pttd /pttd admin revoke <device-id>`. The state of every device:
`dc exec pttd /pttd admin devices`.

## Lost or stolen phone

Revoke it from either admin phone (the app's family list), or from the server:

```sh
dc exec pttd /pttd admin devices
dc exec pttd /pttd admin revoke <device-id>
```

Then enroll the replacement phone. If the lost phone was an admin's, the **other admin revokes it
and enrolls the replacement as an admin**. If **both admin phones are lost**, start a new roster:
`dc exec pttd /pttd admin bootstrap --reset <your-name>` and re-enroll every phone.

## FCM key leaked or expiring

Follow the rotation section of [firebase-setup.md](firebase-setup.md#rotation-yearly-or-on-suspicion-of-a-leak):
a new key, `sops` it into `infra/secrets/fcm-service-account.sops.json`, merge so `infra`
deploys it, check with `admin test-push`, **delete the old key** in Google Cloud. `ptt-fcm` goes
green again within 6 hours of the redeploy (restart `pttd`, and it pings about a minute later).

## Signing keystore lost

There is no recovery. Release with a new key: generate a new keystore, update the `release`
environment secrets and `ANDROID_SIGNING_CERT_SHA256`
([github-setup.md](github-setup.md#2a-the-release-environment)), publish a release, then
**reinstall the app on every phone and enroll it again**: Android refuses an update signed with a
different key, and the self-update check requires the old certificate.

## The nightly snapshot failed

```sh
systemctl status ptt-snapshot.timer ptt-snapshot.service
journalctl -u ptt-snapshot.service -n 50 --no-pager
sudo ls -l /data/snapshots | grep nightly-
```

The alert body holds the last lines of the output. Usual causes: `pttd` was not running at 02:00
(a deploy or a crash: fix that, then `sudo systemctl start ptt-snapshot.service`), a full disk
(`df -h /data`), or a failed integrity check (`quick_check` not `ok`: treat the database as
suspect and look at `dc logs pttd`). After the fix, `sudo systemctl start ptt-snapshot.service`
sends a good ping and clears the alert.

## The weekly maintenance run failed

Open the failed run (**Actions > weekly-maintenance**); its first lines say where it stopped.

| Message | Meaning and action |
| --- | --- |
| `deployed` tag does not exist | Deploy a release through `server-release` first, or seed the tag ([github-setup.md](github-setup.md#notes)) |
| production changed since this run resolved it | An approved release deployed meanwhile; nothing was changed. Next Monday rebuilds the new release. Nothing to do |
| build, Trivy, smoke or signing failed | Read the step. A new base-image CVE with no fix needs a waiver PR or a wait; rerun with **Run workflow** on `main` when fixed |
| deploy failed and rolled back | The job summary says what was restored. Production runs the previous image; see [A bad release or a bad migration](#a-bad-release-or-a-bad-migration) |
| no run at all (the check went down by itself) | GitHub disabled the schedule after 60 days without activity, or the workflow was disabled: **Actions > weekly-maintenance** and run it once by hand to re-enable it |

## The nightly security scan failed

- `no deployed tag`: same as above, deploy a release.
- A failed scan job: read the run, rerun it with **Actions > nightly-security > Run workflow**.
- A **finding** is a GitHub issue labelled `security`, not an alert on this check. Fix it, or
  waive it with an expiring entry in `.trivyignore.yaml` or `osv-scanner.toml`
  ([operations.md](../operations.md#security-scans-as-hard-gates)).

## Android release

1. Merge the android release PR (release-please). `android-release` starts: approve the `release`
   environment (signs), then the `production` environment (publishes); two approvals.
2. Check: `dc exec pttd /pttd admin status` shows `app latest: <version>`, and a phone's
   self-update installs it within a day (or on the next app start).
3. A bad release: do not re-point `latest` older unless you have to; publish a fixed patch
   release instead. To re-point after removing a bad release from the server by hand, run
   `android-release` manually with the tag and `allow_older` true.
4. To force old apps to update: raise `android/min-supported-version.txt` in a deliberate PR.

## Maintenance calendar

| When | Task | How |
| --- | --- | --- |
| Weekly | Nothing manual | `weekly-maintenance` ships the maintenance release; `ptt-weekly` alerts if it fails |
| Monthly | Merge the grouped Android dependency PR (Renovate); glance at the server | Review and merge; `dc exec pttd /pttd admin status` and the Healthchecks.io dashboard; the monthly 30 minutes |
| Quarterly | Run Test alert on every phone; confirm every checklist is green | The app's setup checklist and **Test alert** on each phone; `dc exec pttd /pttd admin devices` for last-seen times |
| Yearly | Raise `targetSdk` | Do the reminder issue Renovate raises; review new background, notification and lock-screen restrictions |
| Yearly | Rotate the FCM key and API tokens | [firebase-setup.md](firebase-setup.md#rotation-yearly-or-on-suspicion-of-a-leak); rotate the Vultr, Tailscale OAuth and deploy keys ([github-setup.md](github-setup.md), [infra-bootstrap.md](infra-bootstrap.md)); the Healthchecks.io ping key if in doubt ([healthchecks-setup.md](healthchecks-setup.md#rotation)) |
| Yearly | Restore a Vultr backup onto a scratch VPS | [restore-drill.md](restore-drill.md); run the [rollback drill](rollback-drill.md) the same week |
| Yearly | Review costs | Vultr, Route 53, Healthchecks.io plan limits; the estimate is in [spec.md](../spec.md#operations) |
