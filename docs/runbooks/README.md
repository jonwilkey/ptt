# docs/runbooks/

Step-by-step operational runbooks (server down, VPS rebuild, lost phone, key rotation, and so
on), expanding the runbook table in `docs/spec.md`.

Written alongside the features they cover, mostly in milestone 6 (hardening and operations).

| Runbook | When |
| --- | --- |
| [infra-bootstrap.md](infra-bootstrap.md) | Once: from nothing to a running server with DNS |
| [firebase-setup.md](firebase-setup.md) | Once, then yearly: Firebase project, FCM service-account key, and its rotation |
| [github-setup.md](github-setup.md) | Once: repository settings, `production` environment, secrets, and the first deploy |
| [healthchecks-setup.md](healthchecks-setup.md) | Once: the Healthchecks.io project, six checks, both admins' email, the ping key, and an alert test |
| [rollback-drill.md](rollback-drill.md) | Milestone 6 exit check, then yearly: break production on purpose and watch it roll back |
| [restore-drill.md](restore-drill.md) | Milestone 6 exit check, then yearly: restore a Vultr backup onto an isolated scratch VPS |
| [operations.md](operations.md) | When something breaks, and the maintenance calendar: concrete commands for each situation |
