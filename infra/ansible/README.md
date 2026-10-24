# infra/ansible/

One idempotent playbook that configures the single `prod` host over Tailscale, with the roles
`base`, `tailscale`, `docker`, and `app` (milestone 1), plus `snapshot` (nightly SQLite snapshot)
and `monitoring` (Prometheus node exporter on the tailnet) in milestone 6.

Filled in starting with milestone 1.
