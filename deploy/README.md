# deploy/

Runtime configuration for the production host: `compose.yaml`, the `Caddyfile`, and env
templates (`*.env.example` only; real values come from `infra/secrets/`).

Filled in milestone 1. Ansible's `app` role writes these files to `/opt/ptt` on the VPS.
