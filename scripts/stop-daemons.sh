#!/usr/bin/env bash
# Stop Gradle and Kotlin daemons so they do not pile up and exhaust memory.
#
# Usage: scripts/stop-daemons.sh [--kill | --kill-all] [worktree...]
#   Runs `./gradlew --stop` in android/ of this checkout and of each given worktree (a wrapper
#   pinning another Gradle version only reaches its own daemons), then kills Kotlin compile daemons.
#   Daemons that survive (other Gradle versions, orphans) are listed with their Gradle version;
#   by default they are NOT killed, since an orphan cannot be told from a live build.
#   --kill: TERM the survivors, then KILL any still alive after ~10 s. Survivors that already
#     received the stop command ("shutting down", running Gradle's cache cleanup for minutes) are
#     left alone, since killing them restarts the cleanup on the next stop.
#   --kill-all: like --kill but also kills shutting-down daemons.
#   Honours GRADLE_USER_HOME (default ~/.gradle) to find the daemon logs.
#   PTT_SKIP_GRADLEW_STOP=1 skips the `./gradlew --stop` calls (test hook).
# Exit codes: 0 always (stopping is best effort).
#
# The `[K]` and `[G]` in the patterns keep pgrep/pkill from matching the shell running them.


# is_shutting_down <gradle-home> <version> <pid>: true if the daemon's log shows it received the
# stop command (it is then finishing its cache cleanup).
is_shutting_down() {
  local log="$1/daemon/$2/daemon-$3.out.log"
  [ -f "$log" ] && grep -qF 'stop command received' "$log"
}

# Sourced by test-scripts.sh: define the functions only.
if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0; fi

set -euo pipefail

kill_survivors=0
kill_all=0
roots=()
for a in "$@"; do
  case "$a" in
    --kill) kill_survivors=1 ;;
    --kill-all) kill_survivors=1; kill_all=1 ;;
    *) roots+=("$a") ;;
  esac
done
gradle_home=${GRADLE_USER_HOME:-$HOME/.gradle}

top=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
stopped=0
for root in "$top" ${roots[@]+"${roots[@]}"}; do
  if [ -n "${PTT_SKIP_GRADLEW_STOP:-}" ]; then
    :
  elif [ -x "$root/android/gradlew" ]; then
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
  killable=()
  waiting=0
  for pid in "${survivors[@]}"; do
    ver=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -oE 'gradle-[0-9][0-9.]*' | head -n1 || true)
    ver=${ver#gradle-}
    if [ -n "$ver" ] && is_shutting_down "$gradle_home" "$ver" "$pid"; then
      echo "shutting down: $pid ($ver, cache cleanup) - let it finish"
      waiting=$((waiting + 1))
      if [ "$kill_all" = 1 ]; then killable+=("$pid"); fi
    else
      echo "still running: $pid (${ver:-unknown gradle version})"
      killable+=("$pid")
    fi
  done
  if [ "$kill_survivors" = 1 ]; then
    if [ "${#killable[@]}" -gt 0 ]; then
      kill "${killable[@]}" 2>/dev/null || true
      for _ in $(seq 10); do
        alive=0
        for pid in "${killable[@]}"; do kill -0 "$pid" 2>/dev/null && alive=1; done
        [ "$alive" = 1 ] || break
        sleep 1
      done
      for pid in "${killable[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
      done
    fi
    echo "killed ${#killable[@]} surviving Gradle daemon(s); left $((kill_all ? 0 : waiting)) shutting down"
  elif [ "${#killable[@]}" -gt 0 ]; then
    echo "run scripts/stop-daemons.sh --kill to kill the 'still running' ones (only if no build is running)"
  fi
fi
