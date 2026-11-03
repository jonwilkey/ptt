#!/usr/bin/env bash
# Stop Gradle and Kotlin daemons so they do not pile up and exhaust memory.
#
# Usage: scripts/stop-daemons.sh [--kill] [worktree...]
#   Runs `./gradlew --stop` in android/ of this checkout and of each given worktree (a wrapper
#   pinning another Gradle version only reaches its own daemons), then kills Kotlin compile daemons.
#   Daemons that survive (other Gradle versions, orphans) are listed with their Gradle version;
#   by default they are NOT killed, since an orphan cannot be told from a live build.
#   --kill: TERM the survivors, then KILL any still alive after ~10 s.
# Exit codes: 0 always (stopping is best effort).
#
# The `[K]` and `[G]` in the patterns keep pgrep/pkill from matching the shell running them.

set -euo pipefail

kill_survivors=0
roots=()
for a in "$@"; do
  if [ "$a" = "--kill" ]; then kill_survivors=1; else roots+=("$a"); fi
done

top=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
stopped=0
for root in "$top" ${roots[@]+"${roots[@]}"}; do
  if [ -x "$root/android/gradlew" ]; then
    if (cd "$root/android" && ./gradlew --stop -q >/dev/null 2>&1); then
      stopped=$((stopped + 1))
    else
      echo "warning: ./gradlew --stop failed in $root" >&2
    fi
  else
    echo "warning: no android/gradlew in $root, skipped" >&2
  fi
done

kotlin=$( { pgrep -f '[K]otlinCompileDaemon' || true; } | wc -l)
pkill -f '[K]otlinCompileDaemon' || true
echo "stopped Gradle daemons for $stopped checkout(s); Kotlin daemons killed: $kotlin"

mapfile -t survivors < <(pgrep -f '[G]radleDaemon' || true)
if [ "${#survivors[@]}" -gt 0 ]; then
  for pid in "${survivors[@]}"; do
    ver=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -oE 'gradle-[0-9][0-9.]*' | head -n1 || true)
    echo "still running: $pid (${ver:-unknown gradle version})"
  done
  if [ "$kill_survivors" = 1 ]; then
    kill "${survivors[@]}" 2>/dev/null || true
    for _ in $(seq 10); do
      pgrep -f '[G]radleDaemon' >/dev/null || break
      sleep 1
    done
    mapfile -t left < <(pgrep -f '[G]radleDaemon' || true)
    if [ "${#left[@]}" -gt 0 ]; then kill -KILL "${left[@]}" 2>/dev/null || true; fi
    echo "killed ${#survivors[@]} surviving Gradle daemon(s)"
  else
    echo "run scripts/stop-daemons.sh --kill to kill them (only if no build is running)"
  fi
fi
