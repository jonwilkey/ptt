#!/usr/bin/env bash
# Run what CI runs, with compact output.
#
# Usage: scripts/check.sh <server|android|buildsrc|all> [--stop]
#   server:   make -C server lint sqlc-check test build (not vuln)
#   buildsrc: ./gradlew -p buildSrc test
#   android:  ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
#   --stop:   run scripts/stop-daemons.sh at the end (success or failure)
# Each step logs fully under ${PTT_CHECK_LOG_DIR:-<repo>/.scratch/check} (overwritten each run);
# the terminal gets one PASS/FAIL line per step plus at most ~60 relevant lines for a failure.
# Stops at the first failure.
# Not run here although CI runs them: the release guard (compileReleaseKotlin and
# ReleaseHasNoImpairmentTest, ci.yml android job), server vuln (govulncheck), and the Docker,
# OpenTofu and workflow lint jobs. Server `sqlc diff` (make sqlc-check) is included.
# Exit codes: 0 all passed, 1 a step failed, 2 usage error.

set -euo pipefail

usage() { echo "usage: $0 <server|android|buildsrc|all> [--stop]" >&2; exit 2; }

if [ $# -lt 1 ] || [ $# -gt 2 ]; then usage; fi
target=$1
stop=0
if [ $# -eq 2 ]; then
  [ "$2" = "--stop" ] || usage
  stop=1
fi
case "$target" in server | android | buildsrc | all) ;; *) usage ;; esac

top=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
logdir=${PTT_CHECK_LOG_DIR:-$top/.scratch/check}
mkdir -p "$logdir"
cap=60

if [ "$stop" = 1 ]; then
  trap '"$top/scripts/stop-daemons.sh" >/dev/null 2>&1 || true' EXIT
fi

# Two passes: error lines first, then warnings/context (the cap cuts the latter).
excerpt_go() {
  grep -E -- '--- FAIL|^FAIL|^panic:' "$1" || true
  grep -E -- '\.go:[0-9]+' "$1" || true
}

excerpt_gradle() {
  grep -E -- '^e: |FAILED|What went wrong|Execution failed|AssertionError|^> (There were|Process|Could not|Compilation|A problem|Failed)|Error:|error:|Expected|expected' "$1" || true
  grep -E -- '^w: |Warning:|\.kt:[0-9]+|\.xml:[0-9]+' "$1" || true
}

# step <name> <kind go|gradle> <dir> <cmd...>
step() {
  local name=$1 kind=$2 dir=$3
  shift 3
  local log="$logdir/$name.log" start end rc=0
  start=$(date +%s)
  (cd "$dir" && "$@") >"$log" 2>&1 || rc=$?
  end=$(date +%s)
  if [ "$rc" -eq 0 ]; then
    echo "PASS $name ($((end - start))s)"
    return 0
  fi
  echo "FAIL $name ($((end - start))s) log: $log"
  local lines total
  lines=$("excerpt_$kind" "$log" | awk '!seen[$0]++')
  total=$(printf '%s\n' "$lines" | grep -c . || true)
  head -n "$cap" <<<"$lines"
  if [ "$total" -gt "$cap" ]; then
    echo "... $((total - cap)) more relevant lines cut (see log)"
  fi
  [ "$total" -gt 0 ] || tail -n 20 "$log"
  exit 1
}

run_server() {
  step server-lint go "$top/server" make lint
  step server-sqlc go "$top/server" make sqlc-check
  step server-test go "$top/server" make test
  step server-build go "$top/server" make build
}
run_buildsrc() {
  step buildsrc gradle "$top/android" ./gradlew --console=plain -p buildSrc test
}
run_android() {
  step android gradle "$top/android" ./gradlew --console=plain \
    ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
}

case "$target" in
  server) run_server ;;
  buildsrc) run_buildsrc ;;
  android) run_android ;;
  all) run_server; run_buildsrc; run_android ;;
esac
