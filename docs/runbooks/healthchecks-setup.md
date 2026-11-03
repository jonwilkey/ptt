# Runbook: Healthchecks.io setup

Do this once, after the server runs (a release deployed through `deploy-prod`) and before turning
on the scheduled workflows. It takes about an hour and ends with every alert reaching both admins.
The design (what each check means, why email only) is in
[docs/operations.md](../operations.md#monitoring); this runbook only creates the pieces.

Both admins take part: one creates the project, the other accepts the team invitation (step 2).

You need: a Healthchecks.io account for each admin, `sops` and an age identity that can decrypt
`infra/secrets/` (see [infra/secrets/README.md](../../infra/secrets/README.md)), the CI age public
key ([github-setup.md](github-setup.md), "The CI age key"), and SSH access to the server
over Tailscale (`ssh <admin>@ptt-prod`).

## 1. Project and checks

1. Sign in at <https://healthchecks.io> (the shared login lives in the password-manager vault) and
   create one project, for example `ptt`. The free Hobbyist plan is enough: 6 checks of its 20.
2. Create the six checks below, **named exactly like the slug** and with the slug set (the check's
   **Slug** field; slugs are addressed by the ping URL, so a typo means a check that never
   goes green). Do not use `?create=1` auto-provisioning: it makes checks with default timing.

   | Name and slug | Schedule | Grace time |
   | --- | --- | --- |
   | `ptt-uptime` | Simple, period 5 minutes | 5 minutes |
   | `ptt-fcm` | Simple, period 6 hours | 1 hour |
   | `ptt-phones` | Simple, period 1 hour | 30 minutes |
   | `ptt-snapshot` | Cron `0 2 * * *`, time zone America/Los_Angeles | 1 hour |
   | `ptt-weekly` | Cron `0 3 * * 1`, time zone America/Los_Angeles | 3 hours |
   | `ptt-security` | Cron `0 5 * * *`, time zone America/Los_Angeles | 3 hours |

   These are the values of the table in [operations.md](../operations.md#healthchecksio); if the
   two ever differ, operations.md wins and this table is the bug.
3. Leave the checks' own settings otherwise at their defaults. A new check starts in the "new"
   state and goes green on its first ping.

## 2. Notifications for both admins

1. **Project > Integrations > Email**: add admin 1's address, then add admin 2's address as a
   second email integration. One integration per admin, so each can be muted or removed alone.
2. Make sure **both integrations are enabled on every check** (each check's page lists the
   integrations with a toggle).
3. **Project > Settings > Team**: invite the other admin, who accepts with their own
   Healthchecks.io account. Team membership is what lets each admin set personal reminders.
4. **Each admin**, in their own account (**Account settings > Email reports**, the reminders
   setting): set reminders to **daily**. Healthchecks.io emails on a state change only; the daily
   reminder is what makes a check that stays down speak up again, and it is the only repeat for
   `ptt-uptime`, `ptt-fcm`, `ptt-snapshot`, `ptt-weekly` and `ptt-security`.
   Reminders go to each account's own email, not to the integrations.
5. Both admins: check the spam folder once and allow mail from Healthchecks.io.

## 3. The ping key

The ping key lets anyone who holds it mark our checks up or down: treat it like the FCM key.

1. **Project > Settings > Ping key**: create the key and copy it.
2. Encrypt it as a bare value (no trailing newline) into the repository. Use the same recipients as
   the FCM key (both admins' age public keys plus the CI key; public keys, not identity files):

   ```sh
   cd infra/secrets
   read -rs PING_KEY                                  # paste the key, then Enter
   printf %s "$PING_KEY" > healthchecks-ping-key.plain.txt   # gitignored name
   unset PING_KEY
   sops -e --age age1exampleadminonepublickey,age1exampleadmintwopublickey,age1examplecipublickey \
     --input-type binary --output-type binary healthchecks-ping-key.plain.txt \
     > healthchecks-ping-key.sops.txt
   shred -u healthchecks-ping-key.plain.txt
   ```

   Check that the encrypted file does not contain the key, and commit it. Conventions:
   [infra/secrets/README.md](../../infra/secrets/README.md).
3. In `infra/ansible/inventory/group_vars/prod/main.yml`, uncomment the line the file already
   carries under `app_secrets`:

   ```yaml
   app_secrets:
     - {src: fcm-service-account.sops.json, dest: fcm-service-account.json}
     - {src: healthchecks-ping-key.sops.txt, dest: healthchecks-ping-key}
   ```

   The `healthchecks-ping-key` dest is what makes the `app` role write
   `PTTD_HEALTHCHECKS_PING_KEY_FILE=/run/secrets/ptt/healthchecks-ping-key` into
   `/opt/ptt/.env` and what the `snapshot` role reads for its ping.
4. Add the **same key** as the repository secret `HC_PING_KEY`
   ([github-setup.md](github-setup.md#the-hc_ping_key-repository-secret)), for
   `weekly-maintenance` and `nightly-security`. The two copies must stay in step when the key is
   rotated.

## 4. Deploy it (rerun the Ansible play)

Open a PR with the sops file and the `app_secrets` line, and merge it after CI is green. The
`infra` workflow runs on merge because the change is under `infra/` (or start it by hand:
**Actions > infra > Run workflow**); it plans, then runs `site.yml` after an approval of the
`production` environment. `site.yml` covers both roles that need the key:

- `app` decrypts the key to `/opt/ptt/secrets/healthchecks-ping-key` (0600, uid 65532), writes the
  `.env` line, and recreates `pttd` because its environment changed;
- `snapshot` installs the nightly snapshot timer that pings `ptt-snapshot`.

From a machine on the tailnet with the sops key, the same play by hand is
(from `infra/ansible/`, with the site vars of [infra/ansible/README.md](../../infra/ansible/README.md)):

```sh
ansible-playbook -i inventory/prod.yml site.yml --tags app,snapshot
```

## 5. Confirm each check goes green

On the server (`ssh <admin>@ptt-prod`):

```sh
sudo docker compose -f /opt/ptt/compose.yaml logs --tail 50 pttd | grep -i -E 'healthchecks|ping'
sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin status
```

The log must not say the ping key is unset (that line means `pttd` runs without monitoring), and
must not show `healthchecks ping failed` lines. `pttd admin status` proves `pttd` is up and its
database healthy (`database: ok`); it does not show Healthchecks state, so use the dashboard for
that.

In the Healthchecks.io dashboard, per check:

| Check | Goes green | To make it happen now |
| --- | --- | --- |
| `ptt-uptime` | within about 5 minutes of the `pttd` restart | wait |
| `ptt-fcm` | about 1 minute after the `pttd` restart. It goes **red** (`fcm not configured`) if FCM is unset, which is intended; set up [firebase-setup.md](firebase-setup.md) first | wait |
| `ptt-phones` | about 5 minutes after the restart | wait. Red with a body listing devices means a real phone needs attention (see [operations.md](operations.md#a-phone-is-reported-by-ptt-phones)) |
| `ptt-snapshot` | after 02:00 Pacific | run it now: `sudo systemctl start ptt-snapshot.service`, then `systemctl status ptt-snapshot.timer` shows the next run |
| `ptt-weekly` | after a Monday 03:00 Pacific run | **Actions > weekly-maintenance > Run workflow** on `main` (it deploys; quiet time) |
| `ptt-security` | after the daily run | **Actions > nightly-security > Run workflow** |

Click a check to see its ping log; the last ping's body is what an alert email would show.
Do not enable the scheduled workflows before the `deployed` tag exists; the order is in
[github-setup.md](github-setup.md#before-the-first-scheduled-run).

## 6. Test that an alert reaches both admins

Do each once, then tell the other admin to confirm that they received it too.

1. **A failure ping.** A `/fail` ping alerts at once and needs no waiting. On the server, the
   key is read from its file and passed to `curl` on stdin, so it stays off the command line:

   ```sh
   sudo sh -c 'printf "url = \"https://hc-ping.com/%s/ptt-snapshot/fail\"\n" "$(tr -d "[:space:]" < /opt/ptt/secrets/healthchecks-ping-key)"' \
     | curl -fsS -m 10 --data-raw 'alert test, ignore' -K - -o /dev/null
   ```

   Both admins get a "down" email naming `ptt-snapshot` with the body `alert test, ignore`.
   Then `sudo systemctl start ptt-snapshot.service` sends a good ping and both get an "up" email.
2. **Do not test by pausing.** A paused check sends nothing. Pause a check only to silence a
   known, planned gap and resume it afterwards; a forgotten pause hides real outages.
3. **A truly missed ping (optional).** `sudo systemctl stop ptt-snapshot.timer`; after the next
   02:00 Pacific plus the 1 hour grace, `ptt-snapshot` goes down and both admins are emailed.
   Then `sudo systemctl start ptt-snapshot.timer`. (Do not stop `pttd` for this: it takes the
   family's pages down.)
4. The rollback drill ([rollback-drill.md](rollback-drill.md)) also proves the path end to end:
   both admins get a `ptt-uptime` "down" and "up" email.

## Rotation

Rotate the ping key after a suspected leak, or when an admin leaves: create a new key in the
project settings, replace `infra/secrets/healthchecks-ping-key.sops.txt` (step 3) and the
repository secret `HC_PING_KEY`, let `infra` deploy it (step 4), then check that all six checks
still receive pings (step 5). Revoke the old key in the project settings once nothing uses it.
