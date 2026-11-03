#!/usr/bin/env bash
# Run what CI runs, with compact output.
#
# Usage: scripts/check.sh <server|android|buildsrc|all> [--stop]
#   server:   make -C server lint test build (not vuln)
#   buildsrc: ./gradlew -p buildSrc test
#   android:  ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
#   --stop:   run scripts/stop-daemons.sh at the end (success or failure)
# Each step logs fully under ${PTT_CHECK_LOG_DIR:-a new mktemp dir}; the terminal gets one
# PASS/FAIL line per step plus at most ~60 relevant lines for a failure. Stops at the first failure.
# Exit codes: 0 all passed, 1 a step failed, 2 usage error.

set -euo pipefail

usage() { echo "usage: $0 <server|android|buildsrc|all> [--stop]" >&2; exit 2; }

[ $# -ge 1 ] && [ $# -le 2 ] || usage
target=$1
stop=0
if [ $# -eq 2 ]; then
  [ "$2" = "--stop" ] || usage
  stop=1
fi
case "$target" in server | android | buildsrc | all) ;; *) usage ;; esac

top=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
logdir=${PTT_CHECK_LOG_DIR:-$(mktemp -d)}
mkdir -p "$logdir"
cap=60

if [ "$stop" = 1 ]; then
  trap '"$top/scripts/stop-daemons.sh" >/dev/null 2>&1 || true' EXIT
fi

excerpt_go() {
  grep -E -- '--- FAIL|^FAIL|^panic:|\.go:[0-9]+' "$1" || true
}

excerpt_gradle() {
  grep -E -- 'FAILED|^e: |^w: |Error:|error:|Warning:|Expected|expected|AssertionError|\.kt:[0-9]+|\.xml:[0-9]+|What went wrong|Execution failed|^> ' "$1" || true
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
  printf '%s\n' "$lines" | head -n "$cap"
  if [ "$total" -gt "$cap" ]; then
    echo "... $((total - cap)) more relevant lines cut (see log)"
  fi
  [ "$total" -gt 0 ] || tail -n 20 "$log"
  exit 1
}

run_server() {
  step server-lint go "$top/server" make lint
  step server-test go "$top/server" make test
  step server-build go "$top/server" make build
}
run_buildsrc() {
  step buildsrc gradle "$top/android" ./gradlew -q --console=plain -p buildSrc test
}
run_android() {
  step android gradle "$top/android" ./gradlew -q --console=plain \
    ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
}

case "$target" in
  server) run_server ;;
  buildsrc) run_buildsrc ;;
  android) run_android ;;
  all) run_server; run_buildsrc; run_android ;;
esac
