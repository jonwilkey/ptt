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

# gh pr checks exits nonzero (8 pending, 1 failing) while still printing JSON.
checks=$(gh pr checks "$1" --json name,bucket,link 2>/dev/null || true)
[ -n "$checks" ] || checks='[]'
jq -r '
  "checks: pass \([.[] | select(.bucket == "pass")] | length)"
  + "  fail \([.[] | select(.bucket == "fail" or .bucket == "cancel")] | length)"
  + "  pending \([.[] | select(.bucket == "pending")] | length)"
  + "  skipped \([.[] | select(.bucket == "skipping")] | length)",
  (.[] | select(.bucket == "fail" or .bucket == "cancel" or .bucket == "pending")
     | "  \(.bucket | ascii_upcase) \(.name) \(.link)")
' <<<"$checks"
