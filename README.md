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
| `.githooks/`, `scripts/` | Local git hooks (secrets, personal data) and their setup/test scripts |

## Conventions

- Commits follow [Conventional Commits](https://www.conventionalcommits.org/).
- The server and the app are versioned separately by
  [release-please](https://github.com/googleapis/release-please), with tags `server-vX.Y.Z` and
  `android-vX.Y.Z`.
- Public GitHub repo, [MIT licensed](LICENSE); both admins have admin access. `main` is
  protected: every change lands by pull request with CI green.
- Nothing deployment-specific or personal is committed: no names, email addresses, real domain,
  account IDs, IPs or keys. Deployment values live in GitHub Actions variables and secrets and
  in gitignored local files; docs use `example.com` placeholders.
- Any deviation from the spec's "Decisions already made" table needs an ADR in
  [docs/adr/](docs/adr/) before the change is made.

## Contributing / local setup

Run `scripts/setup-hooks.sh` once per clone. It installs git hooks that block secrets
(gitleaks), personal terms from your private denylist and non-noreply commit emails, and creates
that denylist outside the repo. `scripts/test-hooks.sh` tests the hooks.
