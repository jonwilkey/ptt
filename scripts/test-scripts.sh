#!/usr/bin/env bash
# Self-test for the agent tooling scripts (stop-daemons, check, refresh-locks, pr-status,
# agent-usage). Runs bash -n and shellcheck (if installed), the usage paths, and agent-usage.py
# against a synthetic transcript tree in a temp dir. Needs no network, Gradle or gh login.
# Usage: scripts/test-scripts.sh
# Exit codes: 0 all passed, 1 a check failed.

set -u

here=$(cd -- "$(dirname -- "$0")" && pwd -P)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }

scripts=(stop-daemons.sh check.sh refresh-locks.sh pr-status.sh test-scripts.sh)
for s in "${scripts[@]}"; do
  if bash -n "$here/$s"; then ok "bash -n $s"; else bad "bash -n $s"; fi
done
if command -v shellcheck >/dev/null 2>&1; then
  if shellcheck "${scripts[@]/#/$here/}"; then ok "shellcheck"; else bad "shellcheck"; fi
else
  echo "skip shellcheck (not installed)"
fi

"$here/check.sh" bogus >/dev/null 2>&1
rc=$?
if [ "$rc" -eq 2 ]; then ok "check.sh bad argument exits 2"; else bad "check.sh bad argument exits $rc, want 2"; fi
"$here/check.sh" >/dev/null 2>&1
rc=$?
if [ "$rc" -eq 2 ]; then ok "check.sh no argument exits 2"; else bad "check.sh no argument exits $rc, want 2"; fi
"$here/pr-status.sh" >/dev/null 2>&1
rc=$?
if [ "$rc" -eq 2 ]; then ok "pr-status.sh no argument exits 2"; else bad "pr-status.sh no argument exits $rc, want 2"; fi
"$here/refresh-locks.sh" --bogus >/dev/null 2>&1
rc=$?
if [ "$rc" -eq 2 ]; then ok "refresh-locks.sh bad flag exits 2"; else bad "refresh-locks.sh bad flag exits $rc, want 2"; fi

# Dry run: the homes are created (so the list grew) and the EXIT trap removes all of them.
dry=$(PTT_DRY_RUN=1 "$here/refresh-locks.sh" --force 2>&1)
rc=$?
made=$(grep '^dry run: homes ' <<<"$dry" | cut -d' ' -f4-)
n=$(wc -w <<<"$made")
left=0
for d in $made; do [ -e "$d" ] && left=$((left + 1)); done
if [ "$rc" -eq 0 ] && [ "$n" -eq 3 ] && [ "$left" -eq 0 ]; then ok "refresh-locks.sh dry run tracks and removes homes"; else bad "refresh-locks.sh dry run rc=$rc homes=$n left=$left"; fi

# Synthetic transcripts: the same message id twice counts once; a 1h write prices at 2x input.
proj="$tmp/proj/session1"
mkdir -p "$proj"
cat >"$proj.jsonl" <<'JSON'
{"type":"assistant","timestamp":"2026-11-01T10:00:00Z","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":1000000,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}
{"type":"assistant","timestamp":"2026-11-01T10:00:01Z","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":1000000,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}
not json
{"type":"assistant","timestamp":"2026-11-01T10:01:00Z","message":{"id":"m2","model":"claude-opus-5-5","usage":{"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":1000000,"cache_creation":{"ephemeral_1h_input_tokens":1000000,"ephemeral_5m_input_tokens":0}}}}
JSON
out=$(python3 -I "$here/agent-usage.py" --project-dir "$tmp/proj" 2>&1)
# 1M input at $4 once = 4.00; 1M 1h write at 2x = 8.00; total 12.00 (a double count would be 16.00).
if grep -q '^TOTAL [$]12\.00 ' <<<"$out"; then ok "agent-usage dedupes and prices 1h writes at 2x"; else bad "agent-usage total: $(head -1 <<<"$out")"; fi
if grep -q 'skipped 1 malformed' <<<"$out"; then ok "agent-usage skips malformed lines"; else bad "agent-usage malformed line count"; fi

# stop-daemons.sh with pgrep/pkill stubs that exit 1 (nothing running) must still report.
stubs="$tmp/stubs"
mkdir -p "$stubs"
printf '#!/bin/sh\nexit 1\n' >"$stubs/pgrep"
cp "$stubs/pgrep" "$stubs/pkill"
chmod +x "$stubs/pgrep" "$stubs/pkill"
sd=$(PATH="$stubs:$PATH" PTT_SKIP_GRADLEW_STOP=1 "$here/stop-daemons.sh" 2>&1)
if grep -q 'stopped Gradle daemons' <<<"$sd"; then ok "stop-daemons.sh reports with no daemons running"; else bad "stop-daemons.sh silent: $sd"; fi

# is_shutting_down reads the daemon log under the Gradle home.
# shellcheck source=stop-daemons.sh
# shellcheck source=scripts/stop-daemons.sh disable=SC1091
. "$here/stop-daemons.sh"
mkdir -p "$tmp/gh/daemon/9.9.9"
echo 'Starting 1' >"$tmp/gh/daemon/9.9.9/daemon-111.out.log"
printf 'x\nDaemon stop command received\n' >"$tmp/gh/daemon/9.9.9/daemon-222.out.log"
if is_shutting_down "$tmp/gh" 9.9.9 222 && ! is_shutting_down "$tmp/gh" 9.9.9 111 \
  && ! is_shutting_down "$tmp/gh" 9.9.9 333; then
  ok "is_shutting_down tells stopping daemons from live ones"
else bad "is_shutting_down"; fi

exit "$fail"
