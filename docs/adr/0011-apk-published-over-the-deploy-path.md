# 0011. Release APKs reach the server over the deploy path

- Status: Accepted
- Date: 2026-11-02

## Context

The spec's `android-release` workflow "uploads [the APK] to the server, and updates
`/v1/app/latest`", and phones download it from `GET /v1/app/apk/{version}` because they trust only
Let's Encrypt's roots (spec, "App updates"). The spec does not say how the upload works. The
options:

1. **A new authenticated write endpoint on `pttd`** (for example `PUT /v1/app/apk`). It needs a
   new credential for CI, adds the only internet-facing endpoint that writes a file, and puts a
   large upload through Caddy and `pttd`'s request limits.
2. **`pttd` fetches the APK from the GitHub Release.** `pttd` would need outbound access to GitHub
   and a way to learn about new releases, and it would have to trust the download.
3. **CI copies the file onto the host** over the Tailscale + SSH path that server deploys
   already use.

## Decision

Option 3. `android-release`'s `publish` job joins the tailnet, copies the APK to the host, checks
its SHA-256 there, and installs it into `/opt/ptt/apk/` with a temp-file-then-rename, then
writes `latest.json` the same way. The newest three versions are kept. A version already on the
server with a different hash is refused. `pttd` reads the directory through a **read-only**
bind mount (`/apk`), not through its writable `/data`. Details: `docs/operations.md`,
"Publishing an APK"; wire format: `docs/protocol.md` §14.

The publish job runs in the `production` environment, which already holds the Tailscale and SSH
secrets and needs an admin's approval. The signing job runs in the `release` environment the spec
names for the keystore. An Android release therefore needs two approvals, one per environment.

## Consequences

- No new endpoint, credential, or listener: the attack surface is the SSH deploy key and the
  tailnet ACL that already exist.
- `pttd` cannot alter the APKs it serves. A tampered APK would still fail the phone's checks
  (SHA-256 from `latest`, same signing certificate, higher version code).
- An Android release waits for two approvals (signing, then publishing). Putting the deploy
  secrets into `release` too would save one approval but would duplicate them in a third
  environment (see ADR 0012 for the second).
- Publishing an APK and deploying the server share the `deploy-production` concurrency group, so
  they never touch the host at the same time.
