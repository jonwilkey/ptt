#!/usr/bin/env bash
# SSH from a GitHub Actions runner to the production host as the CI `deploy` user.
#
#   DEPLOY_SSH_KEY=<private key> .github/scripts/ssh-setup.sh
#
# Run after tailscale/github-action has joined the tailnet. Writes ~/.ssh/config so that
# `ssh ptt-prod` (and Ansible, whose inventory uses the host name ptt-prod) connects as `deploy`
# with only this key. The server's tailnet address is looked up with `tailscale ip`, which works
# without MagicDNS on the runner, and is masked in the public log.
#
# Host key: trust on first use, as in infra/ansible/ansible.cfg (Tailscale already authenticates
# the peer). The runner is fresh every time, so this only pins the key for one job.

set -euo pipefail

: "${DEPLOY_SSH_KEY:?DEPLOY_SSH_KEY must be set (production environment secret)}"
host=ptt-prod

addr=""
for _ in $(seq 30); do
	addr="$(sudo -n tailscale ip -4 "$host" 2> /dev/null || true)"
	[ -z "$addr" ] || break
	sleep 2
done
if [ -z "$addr" ]; then
	echo "::error::$host is not visible on the tailnet (check the tag:ci ACLs and that the server is online)."
	exit 1
fi
echo "::add-mask::$addr"

install -d -m 0700 ~/.ssh
key="$HOME/.ssh/ptt-deploy"
(umask 077 && printf '%s\n' "$DEPLOY_SSH_KEY" > "$key")

cat > ~/.ssh/config << CONFIG
Host $host
  HostName $addr
  HostKeyAlias $host
  User deploy
  IdentityFile $key
  IdentitiesOnly yes
  BatchMode yes
  ConnectTimeout 15
  ServerAliveInterval 15
  StrictHostKeyChecking accept-new
  LogLevel ERROR
CONFIG
chmod 0600 ~/.ssh/config

ssh "$host" true
echo "SSH to $host as deploy works."
