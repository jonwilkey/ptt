# Family Push-to-Talk

This repo is for a family push-to-talk Andriod app. The full spec is in docs/spec.md. Follow its
decisions table; record any deviation as an ADR in docs/adr/ before making it. Work milestone by
milestone, in small PRs.

The implementation is over-seen by an orchestrator. If you are the orchestrator, you dispatch
subagents to accomplish each task and have the full spec in context. You create two types of
subagents:

1. implementer: receives a fully scoped task to implement with just the context needed to
  to accomplish that task
2. reviewer: checks the implementers work, has all of the context that the orchestrator deems
  necessary to rigorously review the implementers work and ensure that it meets all reqwuirements

The implementer iterates on feedback from the reviewer until the reviewer is satisfied with the
quality of the implementation.

## Build daemons

Gradle and Kotlin daemons outlive the builds that start them, and leftovers from several agents
have exhausted the dev machine's memory. After any subagent finishes work that ran Gradle (and
before dispatching the next one), stop them:

```sh
(cd android && ./gradlew --stop)
pkill -f '[K]otlinCompileDaemon' || true
```

(The `[K]` keeps `pkill -f` from matching, and killing, the shell that runs it.)

`--stop` only reaches daemons of its own Gradle version, so also run it from any worktree whose
wrapper pins a different version.

A build with an empty `--gradle-user-home` (the lockfile/checksum refresh) does not read
`~/.gradle/gradle.properties`, so it runs without memory limits; one such run crashed the WSL VM.
Always copy `android/gradle/cold-home.properties` into the new home as `gradle.properties` first
(android/README.md, "Dependency locking and verification"), delete the home afterwards (6-8 GB
each), and never run two Gradle builds at once.

## Public repo hygiene

The repo is public. Never commit personal names, email addresses, the real domain, account IDs,
IP addresses or keys. Use `example.com` placeholders in docs and examples, and GitHub Actions
variables/secrets or gitignored local files for real values. Local git hooks (installed by
`scripts/setup-hooks.sh`) enforce this: if a hook rejects a commit or push, fix the content;
never use `--no-verify`.
