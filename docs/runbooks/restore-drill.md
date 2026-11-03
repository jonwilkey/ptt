# Runbook: restore drill (Vultr backup onto a scratch VPS)

Proves that the newest Vultr backup boots, holds a consistent database with every device and the
current roster, has a recent nightly snapshot beside it, and that `pttd` from that disk starts and
serves it. It is the milestone 6 exit check and a yearly item in the maintenance calendar
([operations.md](operations.md#maintenance-calendar)). The design and the reasoning for the
isolation steps are in
[docs/operations.md](../operations.md#restore-drill-a-vultr-backup-onto-a-scratch-vps).

The spec's target for a real restore is **under 60 minutes**; time steps 2 to 6 against it.

**The restored disk is a copy of production.** On its own it would rejoin the tailnet as
`ptt-prod`, push to the family's phones with the real FCM key, ping Healthchecks.io with the real
key (masking a real outage), and ask Let's Encrypt for the real name. Step 3 removes all of that
before the disk boots normally. Do not skip or reorder it.

You need: Vultr account access, the Tailscale admin console, `ssh` from a machine on the tailnet,
and about two hours the first time. The scratch instance costs a few cents.

## 1. Prepare

1. Use production's region and plan for the scratch instance (`region` and `plan` in your
   `infra/tofu/prod/terraform.tfvars`; Seattle, the smallest plan).
2. In the Tailscale admin console (**Settings > Keys > Generate auth key**) create a key with
   description `ptt-drill`, **Reusable off**, **Expiration 1 day**, **Ephemeral off**,
   **Pre-approved on** (if shown), tag **`tag:ptt-server`**. Keep it in the password manager
   for step 4.
3. Write down production's reference values for step 6:

   ```sh
   ssh <admin>@ptt-prod 'sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin devices'
   ssh <admin>@ptt-prod 'sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin status'
   ssh <admin>@ptt-prod 'sudo ls -l /data/snapshots'
   ```

   Note the device count and names, and the schema version that `pttd admin status` prints.
4. Start a clock.

## 2. Scratch instance and the backup

In the Vultr portal, **outside OpenTofu** (nothing here may be added to `infra/tofu/`):

1. Deploy a new instance: same region and plan as production, any current Debian or Ubuntu image,
   hostname `ptt-drill`, **no reserved IP, no automatic backups, no DNS record**. Attach a **new
   firewall group with no rules** (nothing open), not the production group.
2. Add a rescue ISO to the instance (**Settings > Custom ISO**, pick a rescue or live Linux ISO
   from Vultr's ISO library, for example SystemRescue). **Do this before restoring and confirm in
   the web console that the instance boots the ISO.** The point is that the restored disk must
   not boot normally yet.
3. Restore the newest automatic backup of the production instance onto the scratch instance
   (the instance's **Backups** tab lists automatic backups; choose **Restore** for the newest one
   and the scratch instance as the target; this overwrites the scratch disk). Note the backup's
   timestamp for step 5.
   Watch the web console during the restore, in case the instance boots the disk instead of the ISO.
4. Reboot the scratch instance from the ISO (web console) and stay in the console.

> If the scratch instance booted the restored disk by mistake: power it off at once, then check
> that the real `ptt-prod` shows as connected in the Tailscale console, that `ptt-uptime` is green
> in Healthchecks.io, confirm SSH to `ptt-prod` still works (the same node key briefly ran
> twice), and tell the other admin. Then reboot into the ISO and continue at step 3, or destroy
> the instance and start again at 2.1.

## 3. Strip production's identity (rescue ISO)

In the rescue shell. The root filesystem is usually the first partition of the disk
(`lsblk` shows it; the commands use `/dev/vda1`, adjust if it differs):

```sh
mount /dev/vda1 /mnt
test -f /mnt/opt/ptt/.env || echo "wrong partition"   # the first partition may be the EFI one; try vda2
# 1. The tailnet identity of production, and the tailnet-only sshd listener (it names
#    production's tailnet addresses, which this node will not have).
rm -rf /mnt/var/lib/tailscale
rm -f /mnt/etc/ssh/sshd_config.d/20-ptt-listen.conf
# 2. The secrets: FCM key and Healthchecks.io ping key. Blank the two .env lines that
#    point at them (an unreadable key file would stop pttd starting).
rm -f /mnt/opt/ptt/secrets/*
sed -i -e 's|^PTTD_FCM_CREDENTIALS_FILE=.*|PTTD_FCM_CREDENTIALS_FILE=|' \
       -e 's|^PTTD_HEALTHCHECKS_PING_KEY_FILE=.*|PTTD_HEALTHCHECKS_PING_KEY_FILE=|' \
       /mnt/opt/ptt/.env
# 3. The nightly snapshot timer (it would ping ptt-snapshot with no key anyway; this keeps the
#    disk quiet).
rm -f /mnt/etc/systemd/system/timers.target.wants/ptt-snapshot.timer
# 4. Docker restarts containers that have restart: unless-stopped, which would bring back
#    Caddy and pttd. Park the old containers; images and /data are untouched, and compose
#    recreates pttd in step 5.
mv /mnt/var/lib/docker/containers /mnt/var/lib/docker/containers.parked
grep -E '^PTTD_(FCM|HEALTHCHECKS)' /mnt/opt/ptt/.env     # empty values (a line is absent if never configured)
chroot /mnt passwd root   # one-time drill password; keep it in the password manager until teardown
umount /mnt
```

Detach the ISO in the Vultr portal (**Settings > Custom ISO > Detach**) and reboot from the disk.
`/etc/ssh/sshd_config.d/20-ptt-listen.conf` removed means sshd listens everywhere on the scratch
box, but the host firewall allows port 22 from `tailscale0` only and the Vultr group has nothing
open ([infra/ansible/README.md](../../infra/ansible/README.md), "Lock-out recovery").

## 4. Join the tailnet as `ptt-drill`

SSH is tailnet-only, so use the **Vultr web console** (log in as `root` with the drill password from step 3; root login is blocked for SSH only):

```sh
tailscale up --auth-key=tskey-auth-EXAMPLE --advertise-tags=tag:ptt-server --hostname=ptt-drill --accept-dns=false
tailscale status | head -3
```

Use the one-time key from step 1. The node must appear as `ptt-drill` in the Tailscale console,
**not** `ptt-prod` or `ptt-prod-1`. If it appears under any production name, remove it in the
console, `rm -rf /var/lib/tailscale` and start over from step 3. Then, from your machine:

```sh
ssh <admin>@ptt-drill 'sudo -n true && echo ok'
```

(Admin logins are the production ones: the disk is a copy.)

## 5. Check the data on the disk

No `sqlite3` is needed; the `pttd` image on the disk does the work:

```sh
ssh <admin>@ptt-drill
sudo docker compose -f /opt/ptt/compose.yaml run --rm --no-deps pttd migrate status
sudo docker compose -f /opt/ptt/compose.yaml run --rm --no-deps pttd admin devices
sudo ls -l /data/snapshots
```

- `migrate status` shows every migration `applied`, up to the schema version you noted.
- `admin devices` lists the same devices and people as production (count and names).
- The newest `nightly-*.db` in `/data/snapshots` is no more than 24 hours older than the backup's
  timestamp (its integrity was checked when it was written). Pre-migration snapshots are there
  too; that is normal.

## 6. Start `pttd` alone

If compose reports an existing endpoint or network, run `sudo docker network rm ptt_default`
(scratch box only) and rerun.

```sh
sudo docker compose -f /opt/ptt/compose.yaml up -d --no-deps pttd
sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin status
curl -fsS 127.0.0.1:9090/internal/status
sudo docker compose -f /opt/ptt/compose.yaml logs --tail 30 pttd
```

`admin status` shows `database: ok` and a `schema version` equal to production's, and the log has
no FCM or Healthchecks activity (pushes and pings are off by design: no key files). **Never start
Caddy**: `up --no-deps pttd` does not, and `docker ps` must show only `pttd`.

Because `PTTD_FCM_CREDENTIALS_FILE` is empty, `admin test-push` here reports FCM is not
configured; do not try to make it work.

## 7. Record and tear down

1. Stop the clock and fill in the [drill log](#drill-log) below.
2. Destroy the scratch instance in the Vultr portal (**Settings > Destroy**), then the firewall
   group made for it, and any ISO attachment goes with it.
3. In the Tailscale admin console, remove the machine `ptt-drill` and make sure the auth key
   from step 1 is revoked or expired.
4. Confirm production is untouched: `ptt-prod` shows connected in Tailscale, and all six checks
   in Healthchecks.io are as before the drill (no unexplained state change).
5. Delete any copy of the auth key, and the drill root password entry, from the password manager.

## What to verify (summary)

| Check | Pass |
| --- | --- |
| Backup restored | The newest automatic backup is the source; its timestamp is recent (under about 25 hours) |
| Identity stripped | Node is `ptt-drill`; Caddy was never started; no secrets on the disk |
| Schema | `migrate status` all applied; equals production's version |
| Roster | `admin devices` matches production's devices and people |
| Snapshot | A `nightly-*.db` at most 24 hours older than the backup |
| Serves | `admin status` healthy; `/internal/status` healthy |
| Time | Steps 2 to 6 under 60 minutes |
| Production | Untouched; checks green; no emails |

## Drill log

Add a row after each drill (dates and durations only; no names or addresses).

| Date | Backup age | Steps 2 to 6 | Result and notes |
| --- | --- | --- | --- |
| (none yet) | | | |
