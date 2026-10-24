#!/usr/bin/env bash
# Waits until pttd on ptt-prod reports no active sessions, so a deploy or a host change never
# cuts off a conversation (spec, "Server deploys" step 2). Needs `ssh ptt-prod` (ssh-setup.sh).
# Callers skip it when nothing is deployed yet (deployed-image.sh prints nothing).
#
#   .github/scripts/wait-no-sessions.sh
#
# Polls http://127.0.0.1:9090/internal/status on the host every 15 s for up to 10 minutes:
#   - active_sessions == 0: done.
#   - active_sessions > 0: keep waiting.
#   - connection refused (curl exit 7): pttd is not listening, so it has no sessions to
#     protect; proceed with a warning (the deploy or site.yml run may be what fixes it).
#   - any other failure (SSH, timeout, HTTP error): retry until the deadline.
#   - a successful response whose active_sessions is not an integer: fail at once.
# On the deadline it fails; the caller must not change the host.

set -euo pipefail

interval="${WAIT_INTERVAL:-15}"
deadline=$((SECONDS + ${WAIT_TIMEOUT:-600}))
start=$SECONDS

while :; do
	rc=0
	status="$(ssh ptt-prod 'curl -fsS --max-time 5 http://127.0.0.1:9090/internal/status')" || rc=$?
	waited=$((SECONDS - start))
	if [ "$rc" -eq 0 ]; then
		active="$(jq -r '.active_sessions' <<< "$status" 2> /dev/null || true)"
		if [[ ! "$active" =~ ^[0-9]+$ ]]; then
			echo "::error::/internal/status returned active_sessions='${active}', not an integer."
			exit 1
		fi
		if [ "$active" -eq 0 ]; then
			if [ "$waited" -gt 0 ]; then
				echo "::notice::Waited ${waited} s for active sessions to end."
			else
				echo "No active sessions."
			fi
			exit 0
		fi
		reason="${active} active session(s)"
	elif [ "$rc" -eq 7 ]; then
		echo "::warning::pttd's internal listener refused the connection (not running); proceeding."
		exit 0
	elif [ "$rc" -eq 255 ]; then
		reason="SSH to ptt-prod failed"
	else
		reason="/internal/status failed (curl exit ${rc})"
	fi
	if [ "$SECONDS" -ge "$deadline" ]; then
		echo "::error::Gave up after ${waited} s: ${reason}. Nothing was changed; re-run later."
		exit 1
	fi
	echo "${reason}; waiting (${waited} s so far)."
	sleep "$interval"
done
