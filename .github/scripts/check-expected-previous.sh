#!/usr/bin/env bash
# deploy-prod's guard for callers that resolved "what production runs" earlier (weekly-maintenance
# resolves it at 03:00 and deploys ~20 minutes later, behind the deploy-production concurrency
# group): fails if production now runs a different image.
#
#   .github/scripts/check-expected-previous.sh <expected> <actual>
#
# <expected> is `<repo>@sha256:<digest>`, or empty for "no expectation" (always passes).
# <actual> is deployed-image.sh's output (PTTD_IMAGE: `<repo>@sha256:<digest>`; empty when nothing
# is deployed). Compared by digest, so the spelling of the repository cannot cause a mismatch.

set -euo pipefail

expected="${1-}"
actual="${2-}"
[ -n "$expected" ] || exit 0

digest_re='@(sha256:[0-9a-f]{64})$'
if [[ ! "$expected" =~ $digest_re ]]; then
  echo "::error::expect_previous '${expected}' is not <repo>@sha256:<digest>."
  exit 1
fi
want="${BASH_REMATCH[1]}"
got=""
if [[ "$actual" =~ $digest_re ]]; then got="${BASH_REMATCH[1]}"; fi

if [ "$got" != "$want" ]; then
  echo "::error::production changed since this run resolved it: expected ${want}, production runs '${actual:-nothing}'. Nothing was changed; run again."
  exit 1
fi
echo "Production still runs ${want}."
