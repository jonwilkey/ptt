#!/usr/bin/env bash
# `tofu plan` for CI in a public repository: run from the module directory (already initialized).
#
#   tofu-plan.sh <plan file> [extra tofu plan args, e.g. -lock=true]
#
# Writes the (encrypted) saved plan to <plan file> and "exitcode=<0|1|2>" to $GITHUB_OUTPUT
# (-detailed-exitcode: 0 no changes, 1 error, 2 changes). Always exits 0 so the caller can act on
# the code. The human-readable plan and the error text are discarded: they can quote identifying
# values, and the log is public. Locking is off unless an extra argument turns it on.

set -uo pipefail

plan_file="$1"
shift

err="$(mktemp)"
tofu plan -lock=false "$@" -no-color -detailed-exitcode -out="$plan_file" > /dev/null 2> "$err"
code=$?
echo "exitcode=${code}" >> "$GITHUB_OUTPUT"
if [ "$code" -eq 1 ]; then
	errors=$(grep -c 'Error: ' "$err" || true)
	echo "tofu plan failed with ${errors} error(s); see a maintainer-run plan for details."
fi
rm -f "$err"
exit 0
