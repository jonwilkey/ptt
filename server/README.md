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
| `internal/push/` | FCM sender (Firebase Admin SDK), fake and disabled senders, and the roster push |
| `internal/config/` | `PTTD_*` environment variables and the `<NAME>_FILE` secret helper |
| `internal/db/` | SQLite open + pragmas, goose migrator, pre-migration snapshots |
| `internal/db/queries/` | sqlc query sources |
| `internal/db/sqlcdb/` | sqlc output (generated, committed; never edit) |
| `internal/httpapi/` | Public router (`/healthz` and the `/v1` enrollment/device API), internal router (`/metrics`, `/internal/status`), middleware, bearer auth |
| `internal/ratelimit/` | In-memory sliding-window rate limiter |
| `internal/metrics/` | Prometheus registry |
| `internal/admin/` | `pttd admin` client code: the status client and the terminal QR renderer |
| `internal/adminops/` | DB-direct admin operations (bootstrap, list devices, revoke) |
| `internal/wire/` | Strict b64u and canonical-UUID helpers shared by the parsers |
| `internal/signed/` | Signed objects (`{payload, signer, sig}`): decode, sign, verify under `ptt-roster-v1` / `ptt-envelope-v1` |
| `internal/roster/` | Roster payload parser and the server acceptance rules (`Publish`) |
| `internal/pages/` | The page lifecycle: strict envelope parser, `Create` and the delivered/acknowledge/answer/cancel transitions, the DB-driven scheduler (re-push, missed, answered expiry), and the bounded async FCM fan-out |
| `internal/cleanup/` | The nightly retention sweep |
| `internal/clock/` | Injectable clock (and a deterministic fake) for the scheduler and the cleanup |
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
| `PTTD_FCM_CREDENTIALS_FILE` | unset | Path of the Firebase service-account JSON; selects the real FCM sender (a secret file, see below; empty counts as unset) |
| `PTTD_FCM_FAKE` | `false` | `true` records pushes in memory and serves them at `GET`/`DELETE /internal/fake-fcm` on the internal listener (CI only). Setting it together with credentials is an error |

With neither FCM variable set, pttd starts with pushes disabled: it logs one warning and every
send fails permanently.

Invalid values stop `pttd` at startup with an error naming the variable. Secrets (the FCM
service-account key) are never set directly: `<NAME>_FILE` names a file, mounted
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
| `pttd admin test-push <device-id>` | Send the `test` push (high priority, 15 min TTL) to one device's FCM token. Errors for an unknown or revoked device, a device with no token, or FCM not configured; in fake mode prints the message instead of sending |
| `pttd version` | Print the version (set with `-ldflags "-X main.version=..."`) |

Inside the container: `docker compose exec pttd /pttd admin status`. The `bootstrap`, `devices` and `revoke` commands write to the database, so run them as the container user with `PTTD_PUBLIC_URL` set (compose does this).

## Endpoints

- `GET`/`HEAD /healthz` (public): `200 {"status":"ok"}` when the database answers and the
  schema is migrated; otherwise `503` with a short `reason` (`database_unavailable`,
  `schema_outdated`). It does not reveal the version. A schema newer than the binary counts as
  healthy, because migrations are expand-then-contract and a rollback must keep serving. Every
  other public path is a JSON 404 (or a JSON 405 with `Allow`).
- `/v1` device API (wire contract: `docs/protocol.md` sections 1, 2, 5, 6). Errors are
  `{"error": "<code>"}`; request bodies reject unknown fields, and top-level field names must match exactly (case-sensitive, no repeats), as protocol section 3 requires.
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
  - `GET /v1/roster`: the accepted signed object as its canonical re-encoding `{payload, signer, sig}` (the payload bytes unchanged, so the signature still verifies), or `404 not_found` before the
    first one.
  - `PUT /v1/roster` (admin): body is the signed object. A malformed envelope is
    `400 invalid_request`. Then, in one transaction (writers are serialised, so concurrent PUTs
    cannot both win), the server requires: a valid `ptt-roster-v1` signature by a non-revoked,
    non-test `signer` device under the key it holds (the signer need not be the caller); a
    well-formed payload; the signer listed as admin in the current stored roster, or, when none
    exists, the signer's user is an admin and the new roster lists it as admin; `version` above
    the stored one (`409 roster_stale`); and every listed device existing, non-revoked,
    non-test, with matching `user_id`, both keys, `admin` (= the user's flag) and the user's
    `display_name`. Anything else is `400 roster_invalid`. Success is `204`, an audit row
    `roster_published` (`version`, `devices`), then the `RosterNotifier` hook (a no-op until the
    FCM sender lands). Omitting a device from a roster does not revoke it: to remove a phone the
    app signs and PUTs a roster without the device, then calls `POST /v1/devices/{id}/revoke`.
  - Pages (wire contract: `docs/protocol.md` sections 6 to 9; code in `internal/pages`):
    - `POST /v1/pages`: 30 per minute per device (`429` with `Retry-After`); idempotent retries
      and retries of ended pages do not count. The envelope payload is parsed strictly
      (exact keys, `keys` an object of canonical device ids to 80-byte b64u values). The
      signature is not verified (receivers do that); the server requires `signer` ==
      `from_device` == the caller, the envelope's page id, session, addressee and level to match
      the request, every key to be a device of the addressee with the same `is_test` as the sender (a page never crosses the test/real boundary), and the page push to fit FCM's
      4096 bytes (all else `400 invalid_request`). `201 ringing`; same id and same body `200`
      with the current state; same id after it ended `200 ended` (never re-pushed); same id,
      different body `409 conflict`. If every addressed device is revoked the page is
      tombstoned at once (`201 ended`, no push).
    - `POST /v1/pages/{id}/delivered`: `200 {"state":"ringing"|"acknowledged"|"answered"}` (the
      phone alerts only on `ringing`); `410 page_ended` if the page ended or another device
      answered it. The sender hears `page_state delivered` only if the page is still ringing.
    - `POST /v1/pages/{id}/acknowledge|answer|cancel`: `204`; `403` for a device that
      may not call it, `404` for an unknown id, `410 page_ended` for a tombstoned one (but
      `cancel` on a tombstone is `204`); a second device's `answer` is `409 already_answered`
      (the first answer wins atomically, in one write transaction).
    - Every transition's pushes go out after commit on background goroutines (at most 16 sends
      in flight, 1 minute per transition), so a slow FCM never delays a response. An
      unregistered token is cleared (only if it is still the stored one). If every initial push
      fails permanently, or nobody has a token, the page ends as `failed` and the sender gets
      `page_state failed`; a transient failure leaves it ringing for the re-push.
    - Timers are derived from the `active_pages` row and the clock, not stored: an undelivered
      page is pushed again (and `page_state not_delivered` sent) 30 s and 2 min after creation;
      a normal page unanswered after 15 min is `missed`; an answered page ends 15 min after the
      answer; urgent pages never time out. The scheduler wakes on the next deadline or every
      second at most, first pass at startup, so a restart while a page rings carries on. If a
      page's end is already due (a long outage) it is ended without re-ringing it first; if both
      re-push deadlines passed, one re-push covers both. A re-push re-reads the page and does
      nothing unless it is still ringing and undelivered; if it reaches nobody the page fails.
      The rate-limit budget is spent only by requests that pass validation.
    - An ended page's row is deleted in the same transaction that writes its tombstone (id and
      expiry only, 24 h). Nothing about pages is logged, labelled or audited.
  - Nightly cleanup (at startup, then every 24 h; logs counts only): deletes expired page
    tombstones, enrollment codes more than 24 h past their `expires_at` or `used_at` (so an
    admin can still see `used`/`expired` for a day, then `GET /v1/enrollment-codes/{code_id}` is
    `404`), and `audit_log` rows older than 90 days.
- `GET /metrics` (internal): Go and process collectors, `pttd_build_info{version}`,
  `pttd_active_sessions` (0 until milestone 4; deploys wait for it to be 0), and page
  aggregates: `pttd_page_delivery_seconds` and `pttd_page_answer_seconds` (histograms, creation
  to first `delivered` / to the answer), `pttd_pages_ended_total{reason}` (`cancelled`, `ended`,
  `missed`, `failed`, `expired`) and `pttd_fcm_send_errors_total{kind}` (`unregistered`,
  `permanent`, `transient`). No per-person labels.
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
