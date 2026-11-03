#!/usr/bin/env bash
# Compact, read-only status of a pull request.
#
# Usage: scripts/pr-status.sh <pr-number>
# Prints title, state, draft, head sha, mergeability, review decision, check counts, and one
# line (name + link) per failing or pending check.
# Exit codes: 0 ok, 2 usage error, other: gh/jq failure.

set -euo pipefail

if [ $# -ne 1 ] || ! [[ $1 =~ ^[0-9]+$ ]]; then
  echo "usage: $0 <pr-number>" >&2
  exit 2
fi

gh pr view "$1" --json title,state,isDraft,headRefOid,mergeable,mergeStateStatus,reviewDecision \
  --jq '"#'"$1"' \(.title)\nstate: \(.state)  draft: \(.isDraft)  head: \(.headRefOid[0:7])\nmergeable: \(.mergeable)  mergeState: \(.mergeStateStatus)  review: \(if .reviewDecision == "" then "none" else .reviewDecision end)"'

# gh pr checks exits nonzero (8 pending, 1 failing) while still printing JSON. With no checks at
# all it prints "no checks reported" to stderr, exits 1 and prints nothing on stdout: that is `[]`.
# Any other failure (auth, network) also prints nothing on stdout and exits nonzero with its stderr shown.
rc=0
err=$(mktemp)
trap 'rm -f "$err"' EXIT
checks=$(gh pr checks "$1" --json name,bucket,link 2>"$err") || rc=$?
if [ -z "$checks" ]; then
  if grep -q 'no checks reported' "$err"; then
    checks='[]'
  elif [ "$rc" -ne 0 ] && [ "$rc" -ne 8 ]; then
    cat "$err" >&2
    exit "$rc"
  else
    checks='[]'
  fi
fi
if [ "$checks" = '[]' ]; then
  echo "checks: none reported"
  exit 0
fi
jq -r '
  "checks: pass \([.[] | select(.bucket == "pass")] | length)"
  + "  fail \([.[] | select(.bucket == "fail" or .bucket == "cancel")] | length)"
  + "  pending \([.[] | select(.bucket == "pending")] | length)"
  + "  skipped \([.[] | select(.bucket == "skipping")] | length)",
  (.[] | select(.bucket == "fail" or .bucket == "cancel" or .bucket == "pending")
     | "  \(.bucket | ascii_upcase) \(.name) \(.link)")
' <<<"$checks"
