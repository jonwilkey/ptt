# server/

`pttd`, the family push-to-talk server: one static Go binary (module
`github.com/jonwilkey/ptt/server`) shipped in a distroless container. It relays end-to-end
encrypted audio it cannot decrypt and keeps no history. See `docs/spec.md` ("Server") for the
design; this file is the developer guide.

Released by release-please with tags `server-vX.Y.Z`.

## Layout

| Path | What |
| --- | --- |
| `cmd/pttd/` | `main`: signal handling and exit code only |
| `internal/cli/` | Subcommand parsing and dispatch |
| `internal/app/` | Wiring: opens the DB, migrates, runs both listeners, graceful shutdown |
| `internal/config/` | `PTTD_*` environment variables and the `<NAME>_FILE` secret helper |
| `internal/db/` | SQLite open + pragmas, goose migrator, pre-migration snapshots |
| `internal/db/queries/` | sqlc query sources |
| `internal/db/sqlcdb/` | sqlc output (generated, committed; never edit) |
| `internal/httpapi/` | Public router (`/healthz` and the `/v1` enrollment/device API), internal router (`/metrics`, `/internal/status`), middleware, bearer auth |
| `internal/ratelimit/` | In-memory sliding-window rate limiter |
| `internal/metrics/` | Prometheus registry |
| `internal/admin/` | `pttd admin` client code: the status client and the terminal QR renderer |
| `internal/adminops/` | DB-direct admin operations (bootstrap, list devices, revoke) |
| `internal/auth/` | Device-token and enrollment-code generation and hashing |
| `migrations/` | goose SQL migrations, embedded in the binary |

## Make targets

Tools come from `PATH`: Go 1.27, `golangci-lint` v2, `sqlc`, `govulncheck`.

| Target | Does |
| --- | --- |
| `make build` | Static `linux/amd64` binary at `bin/pttd` (`VERSION=` overrides the version string) |
| `make test` | `go test -race ./...` (needs a C toolchain for `-race`) |
| `make lint` | `golangci-lint run` with `.golangci.yml` (includes gofmt/goimports) |
| `make generate` | `sqlc generate` after changing `migrations/` or `internal/db/queries/` |
| `make sqlc-check` | `sqlc diff`: fails if the committed generated code is stale |
| `make vuln` | `govulncheck ./...` |

The image is built only in CI: `docker buildx build --platform linux/amd64 --build-arg VERSION=... server/`.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PTTD_LISTEN_ADDR` | `:8080` | Public listener; Caddy proxies to it |
| `PTTD_INTERNAL_ADDR` | `:9090` | `/metrics` and `/internal/status`; publish only on loopback or the tailnet, never via Caddy |
| `PTTD_DB_PATH` | `/data/pttd.db` | SQLite database (WAL mode; `-wal` and `-shm` files sit beside it) |
| `PTTD_SNAPSHOT_DIR` | `/data/snapshots` | Pre-migration snapshots |
| `PTTD_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, or `error` |
| `PTTD_PUBLIC_URL` | unset | The address phones reach the server at, e.g. `https://ptt.example.com` (absolute `https` URL, no path or query; `http` only for `localhost`/`127.0.0.1`). Optional; `pttd admin bootstrap` requires it, to put it in the enrollment QR |

Invalid values stop `pttd` at startup with an error naming the variable. Secrets (none yet; the
FCM key arrives in milestone 2) are never set directly: `<NAME>_FILE` names a file, mounted
read-only, whose contents (minus trailing newlines) are the secret. Setting `<NAME>` itself is an
error.

Logs are JSON on stderr. By design there is no access log at `info`: logs carry errors and admin
events, never pages, client IPs, or tokens. `debug` adds one line per request (method, route
pattern, status, duration).

## Subcommands

| Command | Does |
| --- | --- |
| `pttd serve` (or no command) | Open the DB, snapshot if migrations are pending on an existing DB, migrate, serve; SIGTERM/SIGINT drains for up to 15 s |
| `pttd healthcheck` | Exit 0 if `GET /healthz` on the local public listener returns 200 (the image's `HEALTHCHECK`) |
| `pttd migrate up\|down\|status` | Manage the schema of `PTTD_DB_PATH`; `up` and `down` (one step) snapshot first; `status` and `down` refuse to create a missing database |
| `pttd admin status [--json]` | Summary from the running server's `/internal/status` |
| `pttd admin bootstrap [--reset] <name>` | Mint a 24 h single-use enrollment code that makes `<name>` an admin and print it as `pttenroll1:` text plus a terminal QR. Opens the database directly. Refuses if an admin user already exists; `--reset` first revokes every device, deletes the roster and retires outstanding codes (the whole family must re-enroll). Needs `PTTD_PUBLIC_URL`. The QR is drawn for a dark-background terminal (light modules are the filled blocks) |
| `pttd admin devices [--json]` | List every device with its person, admin flag, name, app version, last seen and revocation time |
| `pttd admin revoke <device-id>` | Revoke one device (revoking twice is a no-op). Audit-logged |
| `pttd version` | Print the version (set with `-ldflags "-X main.version=..."`) |

Inside the container: `docker compose exec pttd /pttd admin status`. The `bootstrap`, `devices` and `revoke` commands write to the database, so run them as the container user with `PTTD_PUBLIC_URL` set (compose does this).

## Endpoints

- `GET`/`HEAD /healthz` (public): `200 {"status":"ok"}` when the database answers and the
  schema is migrated; otherwise `503` with a short `reason` (`database_unavailable`,
  `schema_outdated`). It does not reveal the version. A schema newer than the binary counts as
  healthy, because migrations are expand-then-contract and a rollback must keep serving. Every
  other public path is a JSON 404 (or a JSON 405 with `Allow`).
- `/v1` device API (wire contract: `docs/protocol.md` sections 1, 2, 5, 6). Errors are
  `{"error": "<code>"}`; request bodies reject unknown fields.
  - Authentication is `Authorization: Bearer <b64u token>`. Each request hashes the token and
    looks up a non-revoked device (never cached, so revocation applies to the next call);
    otherwise `401 unauthorized`. Admin routes need the device's user to be an admin, else
    `403 forbidden`. `last_seen_at` is written at most once a minute per device. A failure with a
    well-formed but unknown token writes an `auth_failure` audit row (no actor, no token), at
    most one a minute server-wide.
  - `POST /v1/enroll` (no auth): 5 per hour per client IP (first `X-Forwarded-For` entry, else the
    peer; IPv6 by /64), then `429 rate_limited` with `Retry-After`. The IP is only a rate-limit
    key and is never logged. The body is fully validated before the code is touched; the code,
    the new user (if any) and the device are written in one transaction. Unknown, used or
    expired code: `400 code_invalid`; existing `device_id`: `409 conflict`; `mac` present iff the
    code was minted by an admin phone. Audits `device_enrolled`.
  - `POST /v1/enrollment-codes` and `GET /v1/enrollment-codes/{code_id}` (admin): mint a 24 h
    code; poll it (`pending`, `used` with the device's keys and MAC, or `expired`).
  - `PUT /v1/devices/me`: FCM token, app version, device name and the setup `checklist` (at most
    32 boolean items); absent fields are unchanged.
  - `POST /v1/devices/{id}/revoke` (admin): idempotent, audited with the acting device.
  - `GET /v1/contacts`: people with a live non-test device and their presence
    (`ok`/`warning`/`offline`).
- `GET /metrics` (internal): Go and process collectors, `pttd_build_info{version}`,
  `pttd_active_sessions` (0 until milestone 4; deploys wait for it to be 0). No per-person labels.
- `GET /internal/status` (internal): `{version, uptime_seconds, active_sessions, active_pages,
  db_ok, schema_version, binary_schema_version}`.

## Migrations and snapshots

Migrations are goose SQL files in `migrations/`, numbered `NNNNN_name.sql`, never edited after
release, and written expand-then-contract. CI's migration test (`TestMigrationsUpDownUp`) applies
all of them to an empty database, rolls back to zero, and applies them again, so every `Down`
section must work.

Before migrating a database that already has a schema, `pttd` writes
`$PTTD_SNAPSHOT_DIR/pre-migrate-<UTC timestamp>-v<version>.db` with `VACUUM INTO`. If a
snapshot at the same version is less than 10 minutes old (a crash-looping restart), it is reused
instead. Retention keeps the newest five plus the oldest snapshot of each of the five highest
schema versions, so restarts never rotate out the true pre-deploy copy. To restore one, stop `pttd`, copy the snapshot over `pttd.db`, and delete
`pttd.db-wal` and `pttd.db-shm` before starting the older image.

SQLite runs with `journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000`,
`synchronous=NORMAL`, and `temp_store=MEMORY`. The last one, plus `SQLITE_TMPDIR=/data` in the
image, keeps SQLite's temporary files off the read-only root filesystem.
