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
  `infra/secrets/` (`SOPS_AGE_KEY_FILE` or `SOPS_AGE_KEY`, or sops' default key location). CI
  installs a pinned sops and passes the `SOPS_AGE_KEY` environment secret
  (docs/runbooks/github-setup.md).

Run every command from `infra/ansible/` so `ansible.cfg` is picked up.

## Variables

This repository is public, so no deployment-specific value is committed. Real
values go in `inventory/group_vars/prod/site.yml`, which is git-ignored: copy
`inventory/group_vars/prod/site.yml.example` and fill it in. CI passes the same
variables as an extra-vars file: the `infra` workflow from the `production` secret
`ANSIBLE_SITE_VARS` (this file's content), `server-release` only `ptt_domain` from
`PTT_DOMAIN` (see `docs/runbooks/github-setup.md`).

| Variable | Required by | Notes |
| --- | --- | --- |
| `base_admin_users` | `base` | List of `{name, ssh_keys}`. Names must match the admin users cloud-init creates. Each is added to the `sudo` group; the listed keys become each user's **only** authorized keys. |
| `base_deploy_ssh_keys` | `base` | Public key(s) of the CI deploy key. |
| `base_admin_users_removed` | `base`, optional | Logins to delete (a former admin); home directories are kept. Take the person out of `base_admin_users` in the same change. |
| `ptt_domain` | `app` | Public hostname, written to `/opt/ptt/.env` as `PTT_DOMAIN`. |
| `pttd_image` | `deploy.yml`; optional for `site.yml` | `<registry>/<name>@sha256:<64 hex>`; anything not pinned by digest is rejected. `deploy.yml` requires it. `site.yml` without it keeps the `PTTD_IMAGE` already in `/opt/ptt/.env`; on a host with no release yet it writes the files and starts nothing. |
| `GHCR_TOKEN` (environment variable) | `app`, optional | Token with `read:packages` used to log in to ghcr.io for the pull and log out afterwards (CI: the job's `GITHUB_TOKEN`). Read from the controller's environment, never `-e`, so it stays off command lines. Not needed when the image is already on the host. |
| `tailscale_auth_key` | `tailscale`, optional | Pre-authorized key tagged `tag:ptt-server`; only used if the node is logged out. |
| `app_secrets` | `app` | List of `{src, dest}`: sops files in `infra/secrets/` decrypted to `/opt/ptt/secrets/<dest>`. Empty until the Firebase key exists; an entry with `dest: fcm-service-account.json` also sets `PTTD_FCM_CREDENTIALS_FILE` in `/opt/ptt/.env` ([firebase-setup.md](../../docs/runbooks/firebase-setup.md)). |

Each role asserts its required variables before changing anything.

## Running it

```sh
cd infra/ansible
ansible-galaxy collection install -r requirements.yml

# Full configuration (as yourself; your login must be in base_admin_users).
# Keeps the image that is already deployed:
ansible-playbook -i inventory/prod.yml site.yml

# One area only:
ansible-playbook -i inventory/prod.yml site.yml --tags base

# Preview without changing anything:
ansible-playbook -i inventory/prod.yml site.yml --check --diff

# Deploy a specific release (what CI does):
GHCR_TOKEN=<token> ansible-playbook -i inventory/prod.yml deploy.yml \
  -e pttd_image=ghcr.io/<owner>/ptt/pttd@sha256:<digest>
```

CI runs `deploy.yml` (or `site.yml`) with `-u deploy`, the variables above as `-e`
arguments, and `GHCR_TOKEN` in the environment.

### First run (after `tofu apply` creates the host)

1. Make sure cloud-init finished: the host appears in the tailnet as `ptt-prod` with
   `tag:ptt-server`, and you can `ssh <you>@ptt-prod` with your key.
2. Fill in `inventory/group_vars/prod/site.yml`: both admins with all of their keys,
   and the deploy key.
3. Make sure `deploy/compose.yaml` and `deploy/Caddyfile` exist in your checkout.
4. Run `site.yml` as above. Before the first release there is no image to run, so
   the app role writes the files and reports that nothing was started; the first
   `deploy.yml` run (or `site.yml -e pttd_image=...`) starts the services. Keep
   your current SSH session open until the run finishes and you have opened a
   **new** session successfully.
5. Check: `ssh <you>@ptt-prod 'sudo docker compose -f /opt/ptt/compose.yaml ps'`,
   and `https://<ptt_domain>/healthz` from outside.

## Design notes and tradeoffs

- **Admin group.** Admins are in Debian's `sudo` group, which cloud-init already puts
  them in, so the session running the first play is never outside the groups that
  `AllowGroups sudo deploy` and sudoers allow. `/etc/sudoers.d/90-ptt-admins` grants
  `%sudo` passwordless sudo; cloud-init's per-user `90-cloud-init-users` is removed
  only after that file is in place. The SSH hardening is in
  `/etc/ssh/sshd_config.d/01-ptt-hardening.conf`, the same file cloud-init writes at
  first boot; Ansible owns it from then on.
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
- **IPv6 and real client addresses.** `daemon.json` sets `userland-proxy: false`, so
  published ports are DNATed and Caddy (and so pttd, through `X-Forwarded-For`) sees
  each client's real address, which the per-IP enrollment rate limit needs. With the
  userland proxy off, Docker forwards IPv6 to a container only if its network has
  IPv6. **Contract for `deploy/compose.yaml`:** the project network Caddy publishes
  80/443 on must set `enable_ipv6: true`, or the AAAA record's clients cannot connect.
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
- **Runtime file modes.** `compose.yaml`, `Caddyfile`, and `.env` are root-owned 0600
  (Caddy runs as root inside its container) in the 0750 root-only `/opt/ptt`.
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

### Rebuilding the host

- Remove the old `ptt-prod` node from the tailnet (Tailscale admin console) **before**
  the new VPS joins; otherwise the new node is named `ptt-prod-1` and `ptt-prod` keeps
  pointing at the dead machine.
- The Tailscale hostname here (`tailscale_hostname`, default `ptt-prod`) and the
  inventory's `ansible_host` must match the hostname the tofu/cloud-init config gives
  the node.
- The host key changes: remove the old entry from `~/.ssh/known_hosts`
  (`ssh-keygen -R ptt-prod`) before connecting.

## Checks

```sh
cd infra/ansible
ansible-playbook -i inventory/prod.yml --syntax-check site.yml
ansible-playbook -i inventory/prod.yml --syntax-check deploy.yml
ansible-lint     # production profile, from .ansible-lint
yamllint .
```
