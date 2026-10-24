# infra/ansible/

One idempotent playbook that configures the single production host (`ptt-prod`,
group `prod`) over Tailscale. OpenTofu (`infra/tofu/`) creates the VPS and
cloud-init joins it to the tailnet; Ansible does everything else.

| Playbook | Roles | Used by |
| --- | --- | --- |
| `site.yml` | `base`, `tailscale`, `docker`, `app` (tag per role) | the `infra` workflow and admins |
| `deploy.yml` | `app` only | the `server-release` deploy job |

The `snapshot` and `monitoring` roles arrive in later milestones.

| Role | What it does |
| --- | --- |
| `base` | Admin users plus a `deploy` user for CI, passwordless sudo, SSH hardening, nftables host firewall, timezone (Pacific) and time sync, `unattended-upgrades` with the 04:00 reboot window, `needrestart`. |
| `tailscale` | Tailscale apt repo and package; joins the tailnet if logged out; makes sshd listen only on the Tailscale addresses. |
| `docker` | Docker Engine and compose plugin from Docker's apt repo; `daemon.json` with log rotation and hardening. |
| `app` | `/opt/ptt` with `compose.yaml`, `Caddyfile`, `.env`, and decrypted secrets; `/data`; pulls and starts the compose project and waits until it is healthy. |

## Requirements on the machine running Ansible

- `ansible-core` 2.21 (and `ansible-lint` for checks), on a node in the tailnet that
  ACLs allow to reach `tag:ptt-server` on TCP 22.
- The collections, pinned in `requirements.yml`:

  ```sh
  cd infra/ansible
  ansible-galaxy collection install -r requirements.yml
  ```

- For `app_secrets`: `sops` on the controller and an age key that can decrypt
  `infra/secrets/` (`SOPS_AGE_KEY_FILE`, or sops' default key location).

Run every command from `infra/ansible/` so `ansible.cfg` is picked up.

## Variables

This repository is public, so no deployment-specific value is committed. Real
values go in `inventory/group_vars/prod/site.yml`, which is git-ignored: copy
`inventory/group_vars/prod/site.yml.example` and fill it in. CI passes the same
variables as extra vars built from GitHub Actions variables and secrets.

| Variable | Required by | Notes |
| --- | --- | --- |
| `base_admin_users` | `base` | List of `{name, ssh_keys}`. Names must match the admin users cloud-init creates. The listed keys become each user's **only** authorized keys. |
| `base_deploy_ssh_keys` | `base` | Public key(s) of the CI deploy key. |
| `ptt_domain` | `app` | Public hostname, written to `/opt/ptt/.env` as `PTT_DOMAIN`. |
| `pttd_image` | `app` | `<registry>/<name>@sha256:<64 hex>`; anything not pinned by digest is rejected. Pass with `-e` on every run. |
| `ghcr_token` | `app`, optional | Token with `read:packages`, used to log in to ghcr.io for the pull (CI: the job's `GITHUB_TOKEN`). Not needed when the image is already on the host. |
| `tailscale_auth_key` | `tailscale`, optional | Pre-authorized key tagged `tag:ptt-server`; only used if the node is logged out. |
| `app_secrets` | `app` | List of `{src, dest}`: sops files in `infra/secrets/` decrypted to `/opt/ptt/secrets/<dest>`. Empty until milestone 2. |

Each role asserts its required variables before changing anything.

## Running it

```sh
cd infra/ansible
ansible-galaxy collection install -r requirements.yml

# Full configuration (as yourself; your login must be in base_admin_users):
ansible-playbook -i inventory/prod.yml site.yml \
  -e pttd_image=ghcr.io/<owner>/ptt/pttd@sha256:<digest> \
  -e ghcr_token="$GHCR_TOKEN"

# One area only:
ansible-playbook -i inventory/prod.yml site.yml --tags base -e pttd_image=...

# Preview without changing anything:
ansible-playbook -i inventory/prod.yml site.yml --check --diff -e pttd_image=...
```

CI runs `deploy.yml` (or `site.yml`) with `-u deploy` and the variables above as
`-e` arguments.

### First run (after `tofu apply` creates the host)

1. Make sure cloud-init finished: the host appears in the tailnet as `ptt-prod` with
   `tag:ptt-server`, and you can `ssh <you>@ptt-prod` with your key.
2. Fill in `inventory/group_vars/prod/site.yml`: both admins with all of their keys,
   and the deploy key.
3. Make sure `deploy/compose.yaml` and `deploy/Caddyfile` exist in your checkout and
   the image digest has been published.
4. Run `site.yml` as above. Keep your current SSH session open until the run
   finishes and you have opened a **new** session successfully.
5. Check: `ssh <you>@ptt-prod 'sudo docker compose -f /opt/ptt/compose.yaml ps'`,
   and `https://<ptt_domain>/healthz` from outside.

## Design notes and tradeoffs

- **Passwordless sudo for admins.** Accounts have no password, so sudo cannot ask for
  one. SSH is key-only and reachable only over the tailnet, so getting root needs
  an admin's private key *and* a device in the tailnet. Adding passwords would mean
  managing and rotating them for little gain on a two-admin host.
- **Full sudo for `deploy`.** Ansible's `become` runs a different
  `sudo /bin/sh -c '... AnsiballZ_<module>.py'` command for every task, so a
  command-limited sudoers rule cannot express "only what the app role needs"; any
  rule that works allows `/bin/sh`, which is root anyway. The `infra` workflow also
  runs the full `site.yml` as this user. The account is limited instead by: its key
  works only from Tailscale addresses (`from=` in `authorized_keys`), with no agent
  or port forwarding; Tailscale ACLs should let only `tag:ci` reach it; and the
  private key lives only in a GitHub environment secret.
- **Firewall.** `/etc/nftables.conf` replaces only `table inet host_firewall` and never
  runs `flush ruleset`, and a drop-in changes `nftables.service`'s stop action so it
  does not flush either: both would delete Docker's and Tailscale's rules. Ports
  Docker publishes on public addresses bypass the input chain (they are DNATed to the
  container), so the cloud firewall is what limits them; compose publishes pttd's
  9090 only on 127.0.0.1.
- **SSH on the tailnet only.** sshd listens on the Tailscale IPv4 and IPv6 addresses.
  `ip_nonlocal_bind` lets it bind them at boot before `tailscale0` is up, and
  `ssh.service` is ordered after `tailscaled`. The firewall also allows port 22 only
  on `tailscale0`, and the cloud firewall blocks it from the internet.
- **Updates.** `unattended-upgrades` installs Debian security updates and updates from
  the Tailscale and Docker repositories at 03:30 (package lists refresh at about
  03:00) and reboots at 04:00 Pacific when an update needs it. `needrestart`
  restarts services that still use replaced libraries. Docker runs with
  `live-restore`, so a Docker update does not stop the containers. Check the origin
  matching on the host with `sudo unattended-upgrade --dry-run --debug`.
- **Tailscale DNS.** The server runs with `--accept-dns=false`: it needs no MagicDNS
  names, and pointing the host at 100.100.100.100 can break DNS inside containers.
  Clients still reach it as `ptt-prod`.
- **Secrets.** Decrypted on the controller, written to `/opt/ptt/secrets/` as uid/gid
  65532 (pttd's container user), mode 0600. Removing an entry from `app_secrets`
  does not delete the file from the host.
- **Recreating containers.** Changes to `.env` or `compose.yaml` recreate the affected
  containers. Changes to the bind-mounted `Caddyfile` or a secret force every
  container to be recreated.

## Lock-out recovery

The role guards against lock-out: it refuses to run if your login or any admin's
keys are missing, writes sshd settings only after `sshd -t` accepts them, and
changes `ListenAddress` only after reading valid Tailscale addresses. If SSH still
becomes unreachable (for example Tailscale is logged out or the node was
re-registered with new addresses):

1. Open the instance's console in the Vultr web portal (Products > the instance >
   View Console) and log in as `root` with the root password Vultr shows for the
   instance. Root login is blocked only for SSH, not on the console. If no console
   password works, boot the instance from Vultr's rescue/ISO option and edit the
   disk from there.
2. Undo the tailnet-only listener, which lets sshd listen everywhere again (the
   firewalls still allow port 22 only from the tailnet):

   ```sh
   rm /etc/ssh/sshd_config.d/20-ptt-listen.conf
   sshd -t && systemctl restart ssh
   ```

3. If the firewall is the problem: `nft delete table inet host_firewall` (lasts until
   the next reboot or Ansible run).
4. If Tailscale is down: `tailscale status`, then
   `tailscale up --auth-key=<new tagged key> --advertise-tags=tag:ptt-server --hostname=ptt-prod`.
5. Rerun `site.yml` from a laptop once SSH works.

After a rebuild the host key changes: remove the old `ptt-prod` entry from
`~/.ssh/known_hosts` (`ssh-keygen -R ptt-prod`) before connecting.

## Checks

```sh
cd infra/ansible
ansible-playbook -i inventory/prod.yml --syntax-check site.yml
ansible-playbook -i inventory/prod.yml --syntax-check deploy.yml
ansible-lint     # production profile, from .ansible-lint
yamllint .
```
