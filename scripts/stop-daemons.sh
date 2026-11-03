#!/usr/bin/env bash
# Stop Gradle and Kotlin daemons so they do not pile up and exhaust memory.
#
# Usage: scripts/stop-daemons.sh [worktree...]
#   Runs `./gradlew --stop` in android/ of this checkout and of each given worktree (a wrapper
#   pinning another Gradle version only reaches its own daemons), then kills Kotlin compile daemons.
# Exit codes: 0 always (stopping is best effort).
#
# The `[K]` in the pkill pattern keeps `pkill -f` from matching, and killing, the shell running it.

set -euo pipefail

top=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
stopped=0
for root in "$top" "$@"; do
  if [ -x "$root/android/gradlew" ]; then
    (cd "$root/android" && ./gradlew --stop -q >/dev/null 2>&1) || true
    stopped=$((stopped + 1))
  fi
done
pkill -f '[K]otlinCompileDaemon' || true
echo "stopped Gradle daemons for $stopped checkout(s); Kotlin daemons killed"
