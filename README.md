# Family Push-to-Talk

A family-only, Nextel-style push-to-talk pager for Android. Tap a family member's button to page
them; their phone rings even when asleep, they tap to answer, and you talk half-duplex. The app
is a signed, sideloaded APK; a single Go server (`pttd`) on a Seattle VPS wakes phones via FCM
and relays end-to-end encrypted audio it cannot read. Nothing is recorded and no history is
kept.

The full design is in [docs/spec.md](docs/spec.md).

## Layout

| Path | Purpose |
| --- | --- |
| `android/` | Android app (Gradle) |
| `server/` | Go service `pttd`, migrations, sqlc queries |
| `deploy/` | `compose.yaml`, `Caddyfile`, env templates |
| `infra/tofu/` | OpenTofu: VPS, firewall, DNS |
| `infra/ansible/` | Host configuration |
| `infra/secrets/` | sops-encrypted secrets (age) — never commit plaintext |
| `docs/` | Spec, runbooks (`docs/runbooks/`), ADRs (`docs/adr/`) |
| `.github/workflows/` | CI/CD |
| `renovate.json` | Renovate dependency-update policy |

## Conventions

- Commits follow [Conventional Commits](https://www.conventionalcommits.org/).
- The server and the app are versioned separately by
  [release-please](https://github.com/googleapis/release-please), with tags `server-vX.Y.Z` and
  `android-vX.Y.Z`.
- Private GitHub repo; both admins have admin access. `main` is protected: every change lands
  by pull request with CI green.
- Any deviation from the spec's "Decisions already made" table needs an ADR in
  [docs/adr/](docs/adr/) before the change is made.
