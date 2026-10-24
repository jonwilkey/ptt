# Family PTT Pager — Implementation Spec

Oct 23, 2026

## Purpose and decisions

Build a family-only Android app that recreates Nextel-style paging and push-to-talk: one tap pages a family member and opens a talk screen, their phone alerts until they tap the notification, and that tap drops them straight into a radio-style conversation. It is sideloaded (no Play Store), backed by one small server in Seattle, and must be easy for one person to keep updated and secure.

The app is for "I need to talk to you soon, or right now." Less urgent messages stay in the texting apps the family already uses, so this app has no texting and keeps no history.

**Goals**

- Page a family member from a home-screen widget in one tap, at normal or urgent priority; the same tap opens the talk screen on the sender's phone.
- Alert the receiver repeatedly until they answer; the sender sees Delivered and then Answered.
- Answer with one tap on the notification, even on a locked phone, landing in a half-duplex voice session (one talker at a time) where the receiver speaks first.
- Encrypt audio end to end, so the server relays voice it cannot hear.
- Keep nothing after a conversation ends: no history, recordings, or page records on the phones or the server.
- Keep maintenance low: automated builds, updates, and monitoring.

**Non-goals for v1**

- iOS, desktop, or web clients.
- Play Store distribution.
- Text messages, location sharing, images, or video.
- History of pages or conversations, on the phones or the server.
- Offsite backups, and a standby or staging server.

**Decisions already made**

| Area | Decision | Why |
| --- | --- | --- |
| Platform | Android only, all phones have Google Play Services | Whole family is on Android |
| Distribution | Signed APK, sideloaded | No Play policy limits on full-screen alerts, DND override, battery-optimization exemption |
| Wake-up | Firebase Cloud Messaging (FCM) high-priority data messages | Only reliable way through Doze; free |
| Live voice | Opus audio, encrypted end to end on the phones, relayed through our server over WebSocket, half-duplex with floor control | No NAT traversal, \~0.5 s latency, simple; the server forwards only ciphertext |
| Bad signal | Fall back to buffered clips that auto-play on arrival; clips live only in memory and are dropped when the session ends | Works when live streaming can't keep up, without storing audio |
| Server | Single Go binary in a Docker container, SQLite, on a Seattle VPS (\~$5–6/mo) | Close to carrier exchanges; not affected by home power or ISP outages |
| Server data | Accounts, devices, the signed family roster, and in-flight pages only; no audio, history, or message metadata | Nothing sensitive to leak, and little to back up |
| Widget | Two buttons per family member, Page and Urgent; each sends the page and opens the talk screen | Widgets can't detect press-and-hold, and the sender's next step is always to wait and talk |
| Admins | Both parents | No single point of failure for enrollment, approvals, or secrets |
| Expected load | 2–6 users, \~20 sessions/day | Sizing and cost assume this |

## Architecture

Phones running the same app talk only to our own server; Google's FCM is used just to wake a sleeping phone, and the operations services sit underneath.

&#91;embedded content: System architecture · phones, server, and operations\]

A page goes from the sender's phone to `pttd`, which asks FCM to wake the receiver; from then on, the answer and the encrypted live audio travel directly between each phone and `pttd`. GitHub Actions deploys to the VPS over Tailscale, Vultr takes whole-disk backups, and Healthchecks.io alerts both admins if the server goes quiet.

## User experience

Everything starts with one tap: the sender taps a button on the home-screen widget, the receiver taps the notification, and both land on the talk screen. Talking itself happens on the talk screen because widgets can't detect press-and-hold.

**Family widget (resizable, default 4x2)**

- One row per family member: name, presence dot, and two buttons, **Page** and **Urgent**.
- Either button sends the page at that level and opens the talk screen, which shows the page's progress.
- The widget never changes for incoming pages; those arrive as notifications.
- A warning badge appears if the setup checklist regresses.

**Alert levels (defaults, adjustable in settings)**

|  | Page (normal) | Urgent |
| --- | --- | --- |
| Sound | Soft ding, notification stream, current volume | Distinct tone, alarm stream, forced to max volume |
| Vibration | Short pattern | Long, strong pattern |
| Repeats | Every 30 s | Continuous loop with 3 s gaps |
| Silent mode / Do Not Disturb | Vibrates only | Bypasses both |
| Screen | Heads-up notification, also shown on the lock screen | Wakes the screen with a full-screen incoming-page screen over the lock screen |
| Stops when | Answered, acknowledged, cancelled, or 15 min pass (then marked Missed) | Answered, acknowledged, or cancelled by sender |

**Sending a page**

- The talk screen opens immediately and shows the page's progress: Sending, Delivered, Acknowledged, Answered, Not delivered yet, No answer, or Failed.
- The hold-to-talk button stays disabled until the receiver answers and finishes their first transmission (or 10 s pass in silence after they answer). The receiver always speaks first, and their "Hey, what's up?" tells the sender they're there.
- End cancels the page and stops the alert on every phone.
- The sender can leave the talk screen while waiting. An ongoing notification ("Waiting for Mom") returns to it, and when the receiver answers the phone chirps and plays their voice on the speaker.
- A phone has one page or session at a time; starting another ends the current one after a confirmation.

**Answering a page**

- The page arrives as a notification showing who is paging and the level. One tap opens the talk screen and answers. This works on a locked phone: the talk screen shows over the lock screen without unlocking.
- For urgent pages on a locked or idle phone, the full-screen incoming page shows the caller and one large **Answer** button.
- Answering stops the alert on all of the receiver's phones and tells the sender, whose screen shows Answered.
- An **Acknowledged** action on the notification means "seen, can't talk yet." It stops the ringing on all of the receiver's phones, and the sender's talk screen shows Acknowledged, so they can wait, switch to Urgent, or call. The notification stays, and tapping it still answers until the page ends.
- A missed normal page leaves a "Missed page from Dad" notification. Nothing else is kept.

**Talk screen**

- A large hold-to-talk button; holding volume-down also talks while the screen is open, including over the lock screen.
- One talker at a time. Pressing while the other person talks gives a short busy buzz.
- A short original chirp plays when the receiver joins, when you get the floor, and when the other side releases it.
- Shows who is talking, connection quality, and whether it's live or in clip mode.
- Session ends via an End button or after 2 minutes with nobody talking once both sides have joined. Either side can page again later.
- Over the lock screen it shows only this conversation; End returns to the lock screen.

**Setup** is done once per phone: install the APK, scan an enrollment QR code shown on an admin's phone, then follow a guided checklist for each permission (see Android app).

## Android app

One Kotlin app module with clear packages; the project is family-sized, so multi-module overhead isn't worth it. The app must work when it isn't running: every incoming event arrives by FCM and is handled by a foreground service.

**Stack**

- Kotlin, Jetpack Compose (Material 3), Jetpack Glance for widgets.
- minSdk 29 (Android 10, which adds a built-in Opus encoder); targetSdk = latest stable.
- Room for local state (contacts, roster, outbox, active page), DataStore for settings, Hilt for dependency injection.
- OkHttp for REST and WebSocket, kotlinx.serialization for JSON.
- Tink for device keys, HPKE, and signatures, with keysets wrapped by an Android Keystore key; the platform AES-GCM cipher for per-frame audio encryption.
- Firebase Cloud Messaging; Google code scanner (no camera permission needed) for the enrollment QR.
- WorkManager for reliable sends.

**Packages**

| Package | Responsibility |
| --- | --- |
| `push` | `FcmService`: parses data messages (page, page\_cancel, page\_answered, roster, check\_in) and starts the right service |
| `alert` | `AlertService` (foreground): loops sound and vibration per level, posts the incoming-page notification and the urgent full-screen intent, handles answer, acknowledge, and cancel |
| `talk` | `TalkActivity` (can show over the lock screen) + `TalkService` (foreground, microphone type): outgoing-page progress, urgent Answer screen, WebSocket session, floor control, clip fallback |
| `audio` | Recorder (AudioRecord + MediaCodec Opus), player (MediaCodec decode + AudioTrack), jitter buffer, in-memory transmission buffer |
| `crypto` | Device keys, family roster verification and signing, page envelopes, per-frame audio encryption |
| `widget` | Glance widget whose Page and Urgent buttons launch `TalkActivity`; checklist warning badge |
| `data` | Room DB (contacts, roster, outbox, active page), repositories |
| `net` | REST client, WebSocket client, auth interceptor, certificate pinning |
| `setup` | Enrollment, permission checklist, health check, test alert, admin tools (add device, revoke device) |
| `update` | Self-update check and install prompt |

**Alerting mechanics**

- FCM data messages are sent with high priority. Receiving one lets the app start a foreground service from the background.
- Every high-priority message must produce a visible notification; otherwise FCM may downgrade the app's future messages to normal priority.
- `AlertService` runs as a foreground service (type `specialUse`, acceptable because the app is sideloaded) and holds a partial wake lock while alerting.
- Normal: notification channel `page_normal` with a soft sound and vibration and public lock-screen visibility; the service re-triggers it every 30 s.
- Urgent: channel `page_urgent` set to bypass Do Not Disturb. The service plays a looping tone through `MediaPlayer` with `USAGE_ALARM`, saves the alarm volume, sets it to max, and restores it afterward. It also posts a full-screen intent that opens `TalkActivity` in its incoming state, with the Answer button.
- Tapping the notification opens `TalkActivity` and answers. `TalkActivity` calls `setShowWhenLocked(true)` and `setTurnScreenOn(true)`, and Android launches a notification's activity without asking to unlock when that activity can show over the lock screen. Milestone 2 verifies this on both family phone models.
- Acknowledge stops the sound and vibration but leaves the notification up so it can still answer. Answer and cancel also stop the service and clear the notification. Acknowledge and answer are sent to the server through the outbox.

**Talk and audio**

- `TalkService` starts only while `TalkActivity` is visible; Android requires this for microphone foreground services. It keeps running if the sender leaves the screen while waiting, and stops when the session ends.
- Capture: `AudioRecord` with the `VOICE_COMMUNICATION` source (built-in echo cancellation and noise suppression), 16 kHz mono, 20 ms frames, Opus at 24 kbps.
- Playback: `AudioTrack` with `USAGE_VOICE_COMMUNICATION`, speakerphone by default (walkie-talkie style), switches to Bluetooth or wired headset when connected. A jitter buffer of 120 ms (adaptive 60–300 ms) smooths cellular jitter.
- Every Opus packet is encrypted before it leaves the phone and decrypted just before decoding (see Security).
- Audio is never written to disk. While talking, the phone keeps the current transmission's encrypted frames in memory (at most 60 s, a few hundred KB) so it can resend them if the WebSocket drops partway. The buffer is freed when the server confirms the transmission, and everything, including the session key, is freed when the session ends. Incoming clips are likewise played from memory and discarded.
- Clip mode switches on automatically when round-trip time stays above 800 ms or the socket reconnects twice in 30 s, and the talk screen shows "Clip mode." In clip mode a press records and sends on release; incoming clips auto-play in order.

**Reliable sending (outbox)**

Every outgoing action (page, acknowledge, answer, cancel) is written to an `outbox` table with a client-generated UUID, then sent with exponential backoff (directly from `TalkActivity` or `AlertService`, with an expedited WorkManager job as the retry path). The server treats the UUID as an idempotency key, so retries never double-page anyone. Rows are deleted once the server accepts them.

**Permissions and setup checklist**

The setup screen checks each item, explains why it's needed, deep-links to the right settings page, and shows green or red. It reruns on every app start and shows a warning badge on the widget if anything regresses.

| Permission / setting | Why |
| --- | --- |
| Notifications (`POST_NOTIFICATIONS`) | All alerts |
| Lock-screen notifications shown for this app | One-tap answering on a locked phone |
| Microphone (`RECORD_AUDIO`) | Talking |
| Full-screen alerts (`USE_FULL_SCREEN_INTENT`) | Urgent pages over the lock screen; verify with `canUseFullScreenIntent()` on Android 14+ |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | Urgent pages bypass DND and set alarm volume |
| Ignore battery optimizations | Prevent Doze and OEM battery savers from delaying alerts |
| OEM background and lock-screen settings (e.g. Samsung "Never sleeping apps", Xiaomi "Show on lock screen") | Per-manufacturer checklist with instructions; empty for the family's Pixels, kept for future phones |
| Install unknown apps (`REQUEST_INSTALL_PACKAGES`) | Self-updates |
| Foreground service types: `specialUse`, `microphone` | Alert and talk services |

A **Test alert** button sends a normal and an urgent page to the same phone through the server, proving the whole path end to end, including answering from the lock screen.

## Server

One Go service, `pttd`, does everything: REST API, WebSocket audio relay, FCM sending, and an admin CLI. It is built as a static amd64 binary. It never sees audio in the clear and keeps no history: a page exists on the server only while it is ringing or in conversation.

**Stack**

- Go (current stable), `net/http` with the `chi` router, `github.com/coder/websocket`.
- SQLite through `modernc.org/sqlite` (pure Go, so builds need no C toolchain), `sqlc` for typed queries, `goose` for migrations.
- FCM HTTP v1 API via the Firebase Admin Go SDK, authenticated with a service-account key.
- Structured JSON logs (`log/slog`), Prometheus metrics at `/metrics` (internal only).
- Configuration by environment variables; secrets read from files.

**REST API** (JSON over HTTPS, bearer device token on every call except enroll and health)

| Method and path | Purpose |
| --- | --- |
| `POST /v1/enrollment-codes` | Admin devices only: create a one-time enrollment code |
| `POST /v1/enroll` | Trade an enrollment code plus the new device's public keys and enrollment MAC for a device token; registers the FCM token |
| `PUT /v1/devices/me` | Update FCM token, app version, device name, checklist status |
| `POST /v1/devices/{id}/revoke` | Admin devices only: revoke a device's token and close its sockets |
| `GET /v1/contacts` | Family members and presence |
| `GET /v1/roster` | Latest signed family roster |
| `PUT /v1/roster` | Admin devices only: publish a new signed roster (signature and version checked) |
| `POST /v1/pages` | Send a page: `{id, to, level: normal\|urgent, session_id, envelope}`; `id` is the client UUID and idempotency key |
| `POST /v1/pages/{id}/delivered` | Receiver's phone confirms it got the push |
| `POST /v1/pages/{id}/acknowledge` | Receiver has seen the page but can't talk yet |
| `POST /v1/pages/{id}/answer` | Receiver answers; the first answer wins |
| `POST /v1/pages/{id}/cancel` | Sender cancels |
| `GET /v1/app/latest` | Latest APK version, download URL, SHA-256 |
| `GET /v1/app/apk/{version}` | Download an APK |
| `GET /healthz` | Liveness and readiness (no auth) |

Day-to-day administration happens in the app on either admin's phone: creating enrollment QR codes, approving new devices, and revoking lost ones. Over SSH, `pttd admin ...` subcommands cover the rest: `bootstrap <name>` (enrollment code for the first admin phone, or `--reset` to start a new roster), `devices`, `revoke <device>`, `test-push <device>`, `status`.

**Page lifecycle**

1. The sender's phone creates a session ID and session key, wraps the key for each of the receiver's devices in the roster, and posts the page. The server stores it in `active_pages` and pushes it by FCM to exactly the devices named in the envelope. The sender's phone joins the session over WebSocket and waits.
2. Each receiving phone posts `delivered`. The server tells the sender over the WebSocket (or FCM if it isn't connected).
3. If no device confirms delivery within 30 s, the server re-pushes at 30 s and 2 min, and the sender's talk screen shows "Not delivered yet."
4. The first `answer` from any of the receiver's devices wins. The server tells the sender, sends `page_cancel` (reason: answered elsewhere) to the receiver's other devices, and the answering phone joins the session.
5. If the sender's phone isn't connected when the answer arrives, the server sends `page_answered` by FCM. Its notification reopens the talk screen, and anything the receiver already said plays as a clip.
6. A receiver `acknowledge` stops the alert on all of the receiver's devices and tells the sender; the page stays answerable. A sender `cancel` stops the alert everywhere. Unanswered normal pages become missed after 15 min, acknowledged or not; urgent pages never time out.
7. When the page is cancelled or missed, or its session ends, the server deletes the page row and any buffered audio. Only the page ID remains, as an idempotency tombstone for 24 h.

**WebSocket protocol** (`/v1/ws`, opened by `TalkService` only while a page or session is active)

- Text frames carry JSON control messages: `hello`, `join {session_id}`, `page_state {state}`, `peer_joined`, `peer_left`, `floor_request`, `floor_granted {talker, transmission_id}`, `floor_busy`, `floor_release`, `floor_released`, `heard {transmission_id, last_seq}`, `listener_gap {transmission_id}`, `clip {transmission_id}`, `resume {transmission_id}`, `resume_from {last_seq}`, `session_end`, `ping`/`pong` (with timestamps for RTT).
- Binary frames carry audio: a 1-byte type, 16-byte transmission UUID, 4-byte sequence number, then one 20 ms Opus packet encrypted end to end (ciphertext plus 16-byte tag). The header is readable by the server, which needs it for relay and floor control, and is authenticated as associated data.
- The server owns the floor: first request wins; the floor is released on `floor_release`, after 1 s with no frames, after 60 s of continuous talk, or when the talker's socket closes.
- The server relays frames to the other participant and holds the current transmission's frames in memory, never on disk, until the listener reports `heard`.
- If the listener's socket is down or its send buffer overflows, the server sends `listener_gap` to the talker and replays the buffered transmission as a clip when the listener reconnects within the session.
- If the talker's socket drops mid-transmission, the talker reconnects and sends `resume`; the server replies with the last frame it received, and the talker resends the rest from its in-memory buffer.

**Data model (SQLite)**

| Table | Key columns |
| --- | --- |
| `users` | id, display\_name, is\_admin, created\_at |
| `devices` | id, user\_id, name, token\_hash, fcm\_token, app\_version, checklist\_status, last\_seen\_at, revoked\_at, is\_test |
| `enrollment_codes` | code\_hash, display\_name, make\_admin, created\_by\_device, expires\_at, used\_at |
| `roster` | version, signed\_roster, signer\_device, updated\_at (latest only) |
| `active_pages` | id (client UUID), session\_id, from\_device, to\_user, level, envelope, state, created\_at, delivered\_at, push\_attempts |
| `page_tombstones` | id, expires\_at |
| `audit_log` | at, actor\_device, action, detail (enrollment, revocation, roster changes, and auth failures only) |

Sessions and audio exist only in memory. Once a page ends, nothing on the server records who paged whom, and logs record errors and admin events but not pages. A nightly job deletes expired tombstones and enrollment codes, and audit entries older than 90 days.

## Security

Only enrolled family devices can page or talk; audio is encrypted end to end, so the server relays only ciphertext; the server exposes nothing but HTTPS; and every secret lives outside the repository. The main threats are strangers reaching the API, a lost or stolen phone, a compromised server or dependency, and a tampered APK.

**Enrollment and device auth**

- Both parents' phones are admin devices. The first is enrolled with `pttd admin bootstrap <name>` over SSH; every later phone is added in person from an admin phone (Settings › Add device), which can also make the new phone an admin.
- The admin phone gets a one-time code from the server (128-bit random, single use, expires in 24 h) and shows a QR code holding the server URL, the code, a fresh 256-bit enrollment secret that never goes to the server, and the admins' public keys.
- The new phone generates its device keys, enrolls with the code, and sends its public keys with a MAC computed from the enrollment secret. The admin phone checks the MAC, then adds the device to the family roster and signs it; the new phone becomes usable when the signed roster arrives. A server that swapped in its own keys would fail the MAC check.
- Enrolling returns a 256-bit device token. The server stores only its SHA-256 hash; the phone stores it encrypted with an Android Keystore key.
- Every request carries the token as a bearer header; the WebSocket authenticates in its first frame.
- Lost phone: either admin revokes it from the app, which removes it from the roster, revokes its token, and closes its sockets immediately. `pttd admin revoke <device>` revokes the token from SSH if no admin phone is at hand.
- Rate limits: enrollment 5 attempts/hour per IP; pages 30/min per device (stops a stuck button from paging all night).
- The app opts out of Android cloud backup (`allowBackup="false"`), so device keys and tokens never leave the phone.

**End-to-end encryption**

- Each phone has an X25519 key for receiving session keys and an Ed25519 key for signing, generated on the phone with Tink and wrapped by an Android Keystore key. Private keys never leave the phone.
- The **family roster** lists every device (device ID, person, public keys, admin flag) with a version number. A phone accepts a new roster only if an admin device from its current roster (or, at enrollment, from the QR code) signed it and its version is higher. The server can serve the roster but cannot forge, alter, or roll it back; it also checks signatures before accepting one.
- Each page carries a fresh random 256-bit session key, wrapped with HPKE (RFC 9180: X25519, HKDF-SHA256, ChaCha20-Poly1305) for each of the receiver's roster devices. The envelope (page ID, session ID, sender device, level, time, wrapped keys) is signed with the sender's Ed25519 key; receivers drop pages that don't verify.
- Each transmission derives its own key from the session key and transmission ID (HKDF). Each frame is encrypted with AES-256-GCM, using the sequence number as the nonce and the frame header as associated data. The server cannot hear or alter audio; it can only drop or delay it.
- Session keys live only in memory and are discarded when the session ends. There is no ratchet: someone who captured the encrypted traffic and later stole a device's private key could decrypt that device's past sessions. This is accepted for v1 because the server keeps no traffic.
- What the server still learns while a page or session is active: which devices exist, who is paging whom, and who is talking and for how long. It keeps none of this afterward. Google sees the timing of pushes to each phone.

**Lock screen**

Answering works over the lock screen, so anyone holding a locked family phone can answer a page and talk. Over the lock screen, the talk screen exposes only that conversation: no contacts, settings, or admin tools, and no way to start a new page without unlocking.

**Transport**

- TLS 1.3 only, terminated by Caddy with automatic Let's Encrypt certificates, HSTS on.
- The app's network security config forbids cleartext and trusts only the ISRG Root X1 and X2 certificates (Let's Encrypt's roots). This blocks interception by other CAs without breaking on routine certificate renewals.

**Server hardening**

- Public firewall (cloud level and host level) allows only TCP 80 (ACME redirect) and 443.
- SSH is not reachable from the internet: both admins connect over Tailscale, key-only, no root login. `/metrics` is also Tailscale-only.
- OS security updates install automatically with an automatic reboot window at 04:00 Pacific.
- The container runs as a non-root user with a read-only root filesystem, all Linux capabilities dropped, `no-new-privileges`, and only `/data` writable.

**Secrets**

| Secret | Where it lives | Rotation |
| --- | --- | --- |
| FCM service-account key | `sops`-encrypted file in the infra repo, encrypted to both admins' age keys, deployed as a file mounted read-only | Yearly, or on suspicion |
| APK signing keystore | Password-manager vault shared by both admins, plus an offline copy; CI gets it from a protected GitHub environment that requires approval by either admin | Never (losing it forces every phone to uninstall, reinstall, and re-enroll) |
| Vultr API token | GitHub Actions secret, scoped to the minimum permissions | Yearly |
| AWS access (Route 53 record, OpenTofu state bucket) | No stored keys: GitHub Actions assumes an IAM role through OIDC, limited to the `ptt` record and the state bucket | Nothing to rotate |
| Device tokens | Hashed in SQLite | Revoke and re-enroll |
| Device private keys | On each phone only, wrapped by Android Keystore | Revoke and re-enroll |

Restrict the Firebase Android API key to this app's package name and signing-certificate fingerprint in the Google Cloud console.

**Supply chain**

- Renovate keeps Go modules, Gradle dependencies, the base image, GitHub Actions, and Terraform providers current; GitHub Actions are pinned by commit SHA.
- CI runs `govulncheck`, OSV-Scanner on both lockfiles, and Trivy on the container image; high or critical findings fail the build.
- Gradle dependency verification metadata is checked in.
- Release APKs and images are built only in CI, never on a laptop; the APK's SHA-256 is published with each release and checked by the in-app updater.

## Infrastructure as code

Every piece of infrastructure is declared in the repo, so the whole server can be rebuilt from scratch in under an hour: OpenTofu creates the cloud resources, Ansible configures the host, and Docker Compose runs the services.

**Repository layout (one monorepo)**

```
ptt/
  android/              Android app (Gradle)
  server/               Go service pttd, migrations, sqlc queries
  deploy/               compose.yaml, Caddyfile, env templates
  infra/tofu/           OpenTofu: VPS, firewall, DNS
  infra/ansible/        Host configuration
  infra/secrets/        sops-encrypted secrets (age)
  docs/                 This spec, runbooks, ADRs
  .github/workflows/    CI/CD
  renovate.json
```

**Cloud resources (OpenTofu)**

| Resource | Detail |
| --- | --- |
| VPS | Vultr, Seattle region, smallest regular Cloud Compute plan, Debian stable, IPv4 + IPv6 |
| Firewall group | Inbound TCP 80 and 443 only; everything else dropped |
| Reserved IP | So the server can be rebuilt without changing DNS |
| Automatic backups | Vultr whole-disk snapshot backups enabled (the only backup) |
| DNS | `ptt.example.com` A/AAAA records in the existing `example.com` Route 53 hosted zone (the zone is read as a data source; OpenTofu manages only these records) |
| State | S3 backend on a private, versioned AWS S3 bucket with S3-native locking and OpenTofu state encryption enabled |

OpenTofu is used instead of Terraform for its open license and built-in state encryption; the providers are the same.

**Host configuration (Ansible)**

One idempotent playbook, run from CI or a laptop over Tailscale, against the single `prod` host. Roles:

- `base`: users, SSH hardening, nftables firewall, time sync, `unattended-upgrades` with the 04:00 reboot window.
- `tailscale`: joins the tailnet with a tagged, pre-authorized key; SSH listens only on the tailnet interface.
- `docker`: Docker Engine from Docker's apt repo, log rotation, daemon hardening.
- `app`: writes `compose.yaml`, `Caddyfile`, and decrypted secrets to `/opt/ptt` with mode 0600; creates `/data`.
- `snapshot`: nightly SQLite snapshot with a systemd timer (see Operations).
- `monitoring`: Prometheus node exporter bound to the tailnet.

**Runtime (Docker Compose)**

- `caddy`: TLS termination and reverse proxy to `pttd`, including the WebSocket upgrade.
- `pttd`: the app, pinned by image digest (not a moving tag), volume `/data` for SQLite.
- Healthchecks on both; `restart: unless-stopped`.
- No auto-updating containers (such as Watchtower): updates arrive only through CI so every change is tested and recorded.

**Test environments**

There is no staging server. CI tests every release candidate by starting the real image with a fake FCM sender and running the smoke tests against it, and developers run the same compose file locally.

## CI/CD and updates

GitHub Actions builds, tests, and ships everything; a person only merges pull requests and approves production deploys. Routine dependency and base-image updates flow through automatically with tests as the gate.

**Repository rules**

- Private GitHub repo; both admins have admin access. `main` is protected: changes land by pull request with CI green.
- Conventional Commits; `release-please` opens release PRs that bump versions and write the changelog. Server and app are versioned separately (`server-vX.Y.Z`, `android-vX.Y.Z`).
- Architecture decisions are recorded as short ADRs in `docs/adr/`.

**Workflows**

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci` | Every PR and push | Server: lint, `go test -race`, migration up/down test, `sqlc` drift check, `govulncheck`. Android: ktlint, detekt, Android Lint, unit and Robolectric tests, debug build. Infra: `tofu fmt`/`validate`/`plan` (posted on the PR), `ansible-lint`. OSV-Scanner on lockfiles. |
| `server-release` | Tag `server-v*` | amd64 image to GitHub Container Registry, Trivy scan, SBOM, cosign signature. Starts the image in the runner with a fake FCM sender and runs smoke tests, then waits for either admin's approval to deploy to production. |
| `android-release` | Tag `android-v*` | Builds and signs the release APK (keystore from the protected `release` environment), attaches it to a GitHub Release, uploads it to the server, and updates `/v1/app/latest`. |
| `infra` | Merge touching `infra/` | `tofu plan`, then `tofu apply` after approval; then runs the Ansible playbook against prod after approval. |
| `weekly-maintenance` | Monday 03:00 Pacific | Rebuilds the server image on the latest base image even without code changes and ships it through the same pipeline. Auto-promotes to production if the CI smoke tests pass. |
| `nightly-security` | Daily | Rescans the deployed image and lockfiles for newly published vulnerabilities; opens an issue on any finding. |

**Server deploys**

1. CI connects over Tailscale (GitHub's Tailscale action with an ephemeral, tagged key).
2. CI waits until `pttd` reports no active sessions (up to 10 min), so a deploy never cuts off a conversation. Ringing pages survive the restart because they are in SQLite.
3. Ansible's `app` role updates the image digest in `compose.yaml`.
4. `pttd` snapshots the SQLite database, then runs pending migrations on startup.
5. CI polls `/healthz` and runs smoke tests with two pre-provisioned test devices (hidden from contacts and absent from the family roster): page one from the other, answer, and relay one opaque audio frame.
6. If health fails within 2 minutes, CI restores the previous digest and the pre-migration snapshot, then fails the job loudly.

Migrations follow expand-then-contract: add new columns or tables in one release, stop using old ones in a later release. This keeps a rollback safe.

**App updates**

- The APK is served by our own server (`/v1/app/apk/<version>`, device-token authenticated) because a private GitHub repo's releases need a login.
- A daily WorkManager job checks `/v1/app/latest`. If a newer version exists, it downloads the APK, verifies the SHA-256 and that the signing certificate matches, then installs through `PackageInstaller`.
- The first update needs the user to tap Install. After the app has installed itself once, Android 12+ can allow later updates without a prompt (`USER_ACTION_NOT_REQUIRED`); the app should request this and fall back to a prompt.
- `/v1/app/latest` also returns `min_supported_version`. Older apps show a blocking "Update required" screen but still receive and answer pages.
- Compatibility rule: the server supports the previous app version's API for at least 30 days after a new app release.

**Renovate policy**

- Patch and minor updates for GitHub Actions, Go modules, and Docker base images: auto-merge when CI passes.
- Android and Gradle dependencies: grouped into one PR per month, merged by a person after a quick check.
- Major versions: never auto-merged.
- Yearly reminder issue: raise `targetSdk` to the newest Android version and review new background, notification, and lock-screen restrictions.

## Operations

The system watches itself and emails both admins only when something needs a person. Routine upkeep is about 30 minutes a month.

**Monitoring**

- **Uptime:** an external monitor (Healthchecks.io free tier) expects a ping from `pttd` every 5 minutes; a missed ping emails and texts both admins. Email/SMS is used because the app can't page anyone when its own server is down.
- **Push path:** every 6 hours `pttd` sends an FCM validate-only request to confirm the service-account key still works.
- **Phone heartbeat:** once a day the server sends a silent check-in push (normal priority, so it needs no visible notification); each phone replies with its app version and permission checklist status. A phone silent for 48 hours, or with a red checklist item, triggers an email to both admins naming the device. This catches the most common real failure: a phone whose battery saver or a permission change stopped alerts from arriving.
- **Metrics:** page delivery time, answer time, FCM errors, active sessions, and clip-fallback rate at `/metrics`, viewable over Tailscale. Metrics are aggregate counters with no per-person labels. `pttd admin status` prints a one-screen summary.

**Backups**

- The server holds only accounts, device registrations, and the signed roster: small and slow-changing. Pages and sessions are in flight and need no backup.
- Nightly at 02:00 Pacific: `VACUUM INTO` a consistent local SQLite copy (7 kept), so Vultr's disk backups always contain a clean database. The job pings its own Healthchecks.io check.
- Vultr automatic whole-disk backups are the off-host copy.
- Every phone also holds the signed roster, so an admin phone can re-upload it to a rebuilt server.
- If no backup is usable, the fallback is a fresh server and re-enrolling each phone, about 5 minutes per phone.

**Runbook**

| Situation | Action |
| --- | --- |
| Server down, VPS reachable | SSH over Tailscale; `docker compose ps` and logs; restart; if a bad release, rerun the previous release's deploy |
| VPS lost or corrupted | Restore the latest Vultr backup (or `tofu apply` a fresh VPS, which reuses the reserved IP), run Ansible, deploy current digest. If no backup is usable, `pttd admin bootstrap --reset` and re-enroll every phone. Target: under 60 min |
| Phone not receiving pages | Open the app's setup checklist; run Test alert; check OEM battery settings; `pttd admin test-push <device>` |
| Lost or stolen phone | Revoke it from either admin phone (or `pttd admin revoke <device>`); enroll the replacement |
| One admin phone lost | The other admin revokes it and enrolls the replacement as an admin |
| Both admin phones lost | `pttd admin bootstrap --reset` starts a new roster; every phone must re-enroll |
| FCM key leaked or expiring | Create a new key in Google Cloud, update the sops secret, redeploy, delete the old key |
| Signing keystore lost | No recovery: release with a new key, reinstall the app on every phone, and re-enroll it |

**Maintenance calendar**

| When | Task |
| --- | --- |
| Weekly | Nothing manual; maintenance release ships automatically |
| Monthly | Merge the grouped Android dependency PR; glance at `pttd admin status` |
| Quarterly | Run Test alert on every phone; confirm every phone's checklist is green |
| Yearly | Raise `targetSdk`; rotate the FCM key and API tokens; restore a Vultr backup onto a scratch VPS to prove it works; review costs |

**Running costs (estimate)**

| Item | Monthly |
| --- | --- |
| Vultr VPS, Seattle, smallest plan | \~$5–6 |
| Vultr automatic backups | \~$1 |
| Domain name and Route 53 zone | $0 extra (`example.com` is already owned and hosted) |
| S3 bucket for OpenTofu state | \~$0.01 |
| FCM, GitHub private repo, Tailscale personal, Healthchecks.io | $0 |
| Total | \~$6–7 |

## Testing

The riskiest behavior is "a page reaches a sleeping phone, keeps alerting, and one tap gets the receiver talking," so testing concentrates there, on real devices in Doze and on the lock screen, not just emulators.

**Automated (run in CI)**

- Server unit tests: page state machine, idempotent page creation (including retries after the page has ended), floor control (contention, timeout, socket loss), roster signature and version checks, cleanup (no page rows or audio buffers remain after a session ends), rate limits.
- Server integration tests: real HTTP and WebSocket against a temporary SQLite database with a fake FCM sender; two simulated clients exchange pages, answers, and opaque audio frames, including listener gap, talker resume, and a `pttd` restart while a page is ringing.
- Fuzz test for the binary audio frame parser.
- Android unit tests: outbox retries, alert-level policy, first-talker rule, clip-mode switching thresholds, update verification (wrong hash or signer must be rejected), crypto (envelope and frame round trips; wrong key, tampered header or frame, unsigned roster, and rolled-back roster must all be rejected).
- Robolectric tests: notification channels, incoming-page notification contents and tap intent, widget rendering.
- Debug builds include a test-only receiver that injects fake push payloads, so alert and answer flows can be tested on an emulator without Firebase, and a network impairment setting that adds delay and frame loss in the client's WebSocket layer.

**Manual, on both family phone models (Pixel 10a and the older Pixel) before a release that touches alerting, answering, or audio**

- Force Doze (`adb shell dumpsys deviceidle force-idle`) and the restricted standby bucket, then page the phone.
- Repeat with silent mode, vibrate mode, and Do Not Disturb on.
- Page from the widget with the app swiped away; answer from the notification on an unlocked phone, from the notification on a locked phone with the screen on and off, and from the urgent full-screen Answer screen.
- Talk session over cellular with Wi-Fi off; then with the debug impairment setting at 300 ms delay and 5% frame loss to confirm clip mode kicks in.
- Bluetooth headset connected and disconnected mid-session.
- After a session, confirm the app's files and cache directories hold no audio.
- Self-update from the previous release.

**Release acceptance targets**

- [ ] A page to an idle phone in Doze starts alerting within 5 s in at least 19 of 20 tries.
- [ ] Urgent pages are audible at max volume with silent mode and DND on.
- [ ] An alert never stops before it is answered, acknowledged, or cancelled (normal: until 15 min).
- [ ] The sender sees Acknowledged within 3 s of the receiver tapping it.
- [ ] The receiver reaches the talk screen in one tap from the notification, including on a locked phone.
- [ ] The answer reaches the sender within 3 s on cellular.
- [ ] Live voice mouth-to-ear delay under 700 ms on LTE between two Seattle-area phones.
- [ ] No duplicate pages after forced retries.
- [ ] After a session ends, no audio or page record remains on either phone or on the server.
- [ ] Idle battery drain from the app under 1% per day (Android battery usage screen).

## Milestones

Build in this order so paging and answering (the most valuable and riskiest part) work end to end on real phones before any voice work starts. Each milestone ends with its exit check passing; work in small pull requests, and record any deviation from the decisions table as an ADR before making it.

1. **Foundations.** Monorepo layout, `ci` workflow, Renovate, OpenTofu for VPS, firewall, and DNS, Ansible `base`/`tailscale`/`docker`/`app` roles, Caddy serving `pttd`'s `/healthz`, basic image build, CI smoke test, and deploy.
   - Exit: a merged change passes CI smoke tests and deploys to production after approval.
2. **Enrollment and paging core.** Device keys, admin bootstrap, in-app enrollment QR and roster signing, revocation, admin CLI. Pages API with signed envelopes, the full lifecycle, FCM, device tokens, rate limits. App setup checklist, `AlertService` for both levels, Acknowledged action, outbox, and answering into a placeholder `TalkActivity` from the notification and the urgent full-screen screen.
   - Exit: Doze, silent-mode, and DND paging tests pass, and one-tap answering from a locked phone works, on both family phone models.
3. **Widget and sender flow.** Family widget with Page and Urgent; sender talk screen with page progress, End, and the waiting notification; checklist warning badge.
   - Exit: page from the home screen and answer from a locked phone, with the app swiped away on both.
4. **Live talk.** WebSocket session, server floor control and relay, Opus capture and playback, per-frame encryption, talk screen, first-talker rule, volume-key talk, chirps, session timeout.
   - Exit: latency and Bluetooth tests pass on LTE; a capture on the server shows only ciphertext.
5. **Clip fallback.** In-memory transmission buffers on phone and server, `heard`, `listener_gap`, talker resume, automatic clip mode, clip replay after an answer to a disconnected sender.
   - Exit: the impaired-network test passes, and no audio or page records remain after sessions end.
6. **Hardening and operations.** `android-release` and self-update, deploy rollback, nightly snapshot and Vultr backups, Healthchecks.io checks, phone heartbeat, `weekly-maintenance` and `nightly-security` workflows, security scans as hard gates.
   - Exit: a deliberately broken release rolls back automatically; a Vultr backup restores onto a scratch VPS.
7. **Family rollout.** Enroll every phone with both parents as admins, walk each person through setup, run Test alert on each, and write a one-page "how to use it" guide in `docs/`.
   - Exit: all release acceptance targets met.

## Open questions

None open.

**Resolved**

- [x] Family members and phones? **One phone each**, all Pixels: a Pixel 10a and an older Pixel (1–2 years older). Multiple devices per person stay supported (useful while switching phones) but aren't a test focus.
- [x] "Page everyone" group page in v1? **No, deferred to v2.** The envelope can already wrap the session key for several people.
- [x] Escalate an unanswered normal page to urgent automatically? **No.** The sender ends the page and presses Urgent if it has become urgent.
- [x] Limits for children's phones, such as no urgent pages or quiet hours? **None.**
- [x] Can a receiver stop the ringing without answering? **Yes, with an Acknowledged action**; it stops the ringing on all their phones and the sender sees Acknowledged.
- [x] Timeout for an unanswered normal page? **15 min**, then Missed.
- [x] Domain? **`ptt.example.com`**, in the existing Route 53 zone. OpenTofu state moves to S3, so the project no longer uses Cloudflare.
- [x] Keep recordings of live talk in history? **No.** Nothing is recorded; audio exists only in memory during a session.
- [x] End-to-end encrypted audio? **Yes, in v1** (see Security).
- [x] A second family member as backup admin? **Both parents are admins**: each has an admin phone, SSH over Tailscale, an age key for the sops secrets, access to the shared password-manager vault, and approval rights on production deploys and releases.
