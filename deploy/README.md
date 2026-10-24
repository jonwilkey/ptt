# deploy/

Runtime for the production host: Docker Compose runs Caddy (TLS termination, reverse proxy)
in front of `pttd`. See `docs/spec.md` ("Infrastructure as code" › "Runtime" and "Security" ›
"Transport" and "Server hardening").

| File | What |
| --- | --- |
| `compose.yaml` | The production stack (project `ptt`): `pttd` pinned by digest, Caddy, an IPv6-enabled network |
| `Caddyfile` | Caddy: TLS 1.3 only, Let's Encrypt, HSTS and security headers, proxy to `pttd:8080`, no access log, no admin API |
| `compose.ci.yaml` | Override for CI and local runs: local image `ptt/pttd:ci`, named volumes, Caddy's internal CA |
| `.env.example` | Every variable compose reads, with placeholder values |
| `smoke.sh` | External smoke test through Caddy; used by CI and after production deploys |

## The stack

- **`pttd`** runs as uid/gid 65532 with a read-only root filesystem, all capabilities dropped,
  and `no-new-privileges`. Its only writable path is `/data` (SQLite). Secrets from
  `/opt/ptt/secrets/` are mounted read-only at `/run/secrets/ptt` and read through `PTTD_*_FILE`
  variables (none until milestone 2). Port 8080 (public API) is reachable only from Caddy on
  the compose network; port 9090 (`/metrics`, `/internal/status`) is published on the host's
  `127.0.0.1` only. On SIGTERM it drains for 15 s; compose waits 20 s.
- **Caddy** (official image, pinned by digest) publishes TCP 80 and 443. It also runs with a
  read-only root filesystem, all capabilities dropped except `NET_BIND_SERVICE`, and
  `no-new-privileges`. Certificates and the ACME account live in `/opt/ptt/caddy/data`, which
  survives container recreation. Decisions in the `Caddyfile`:
  - TLS 1.3 only; certificates only from Let's Encrypt (no ZeroSSL fallback), because the app
    trusts only ISRG Root X1 and X2.
  - HTTP/3 is off: it needs UDP 443, which the firewalls do not allow.
  - No `log` directive, so no access log. Caddy's own logs (startup, certificates, errors) go to
    the Docker log; a request that fails inside Caddy (for example a 502 while `pttd` is down)
    is logged with its client address, with credentials redacted.
  - The admin API is off; the healthcheck uses a plain-HTTP listener on the container's
    loopback (`127.0.0.1:8081`), which is not published.
  - `/metrics` and `/internal/*` are answered with a JSON 404 by Caddy itself, in addition to
    `pttd` not serving them on its public listener.
  - WebSocket upgrades pass through `reverse_proxy` unchanged; `X-Forwarded-For` carries the
    client address.
- **IPv6.** The Docker daemon runs with `userland-proxy: false`, so published ports are DNATed and
  Caddy sees real client addresses. Docker then forwards IPv6 to a container only if its network
  has IPv6, so the project network sets `enable_ipv6: true`. Docker Engine 27 and later assigns
  it a ULA `/64` automatically.

## Variables

Compose reads `.env` next to `compose.yaml` (see `.env.example`):

| Variable | Required | Meaning |
| --- | --- | --- |
| `PTTD_IMAGE` | yes | `<registry>/<name>@sha256:<64 hex>` |
| `PTT_DOMAIN` | yes | Public hostname Caddy serves |
| `ACME_EMAIL` | no | Let's Encrypt account contact; empty registers without one |

`PTT_TLS` (Caddy's certificate source) is set in the compose files, not `.env`: `acme` in
`compose.yaml`, `internal` in `compose.ci.yaml`.

## Running it locally

Needs Docker Engine 27+ with the compose plugin, and ports 80 and 443 free.

```sh
docker buildx build --load -t ptt/pttd:ci server/
cd deploy
export PTTD_IMAGE=ptt/pttd:ci PTT_DOMAIN=ptt.example.com
docker compose -f compose.yaml -f compose.ci.yaml up -d --wait

# Caddy's local CA, then the smoke test:
docker compose -f compose.yaml -f compose.ci.yaml exec -T caddy \
  cat /data/caddy/pki/authorities/local/root.crt > /tmp/ptt-ca.crt
./smoke.sh https://ptt.example.com --resolve ptt.example.com:443:127.0.0.1 --cacert /tmp/ptt-ca.crt

docker compose -f compose.yaml -f compose.ci.yaml exec pttd /pttd admin status
docker compose -f compose.yaml -f compose.ci.yaml down -v
```

`compose.yaml` checks that `PTTD_IMAGE` and `PTT_DOMAIN` are set before the override applies,
so both must be set (exported, or in `deploy/.env`) even though `compose.ci.yaml` fixes the
values it uses. CI's `compose-smoke` job runs the same steps and also checks the containers'
hardening with `docker inspect`.

## Production

Ansible's `app` role (`infra/ansible/roles/app/`) copies `compose.yaml` and `Caddyfile` to
`/opt/ptt/`, writes `/opt/ptt/.env` (`PTTD_IMAGE`, `PTT_DOMAIN`) and the decrypted secrets
(`/opt/ptt/secrets/`), creates `/data` and `/opt/ptt/caddy/{data,config}` with the right owners,
and runs `docker compose up --wait`. Never edit the files on the server; change them here and
run the playbook (see `infra/ansible/README.md`).

```sh
sudo docker compose -f /opt/ptt/compose.yaml ps
sudo docker compose -f /opt/ptt/compose.yaml logs --tail 100 caddy
sudo docker compose -f /opt/ptt/compose.yaml exec pttd /pttd admin status
curl -fsS http://127.0.0.1:9090/metrics   # on the host only
```

After a deploy, from anywhere: `deploy/smoke.sh https://<domain>`.

### Restoring a SQLite snapshot

`pttd` writes a snapshot to `/data/snapshots/` before every migration. To restore one: stop
`pttd` (`docker compose stop pttd`), copy the snapshot over `/data/pttd.db`, delete
`/data/pttd.db-wal` and `/data/pttd.db-shm`, keep the files owned by 65532:65532, and start the
image that matches the snapshot's schema. Details are in
[`server/README.md`](../server/README.md#migrations-and-snapshots).

## smoke.sh

```text
deploy/smoke.sh <base_url> [--resolve host:port:addr] [--cacert file | --insecure] [--http-port N]
```

Checks, through Caddy: `GET /healthz` is `200 {"status":"ok"}` over TLS 1.3 with HSTS; a TLS 1.2
handshake is refused; an unknown path, `/metrics`, and `/internal/status` are JSON 404s; and
`http://` redirects to `https://`. Each failed check prints a `FAIL` line, and the script exits
non-zero if any failed. Needs bash 4.4+, curl, and jq.
