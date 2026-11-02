#!/usr/bin/env bash
# Pings a Healthchecks.io check (docs/operations.md, "Healthchecks.io"): the key comes from the
# HC_PING_KEY environment variable and is never printed. The body (argument 3) is
# shown in the alert email, so keep it public-safe: the repository and its logs are public.
#
#   HC_PING_KEY=... hc-ping.sh ptt-weekly ok   "run url, result"
#   HC_PING_KEY=... hc-ping.sh ptt-weekly fail "build failed: ..."
#
# A failed or impossible ping warns and exits 0 for `ok` and `fail` alike: the check itself goes
# down on a missed ping, which is the alert. HC_PING_URL_BASE overrides https://hc-ping.com (tests).

set -euo pipefail

slug="${1:?usage: hc-ping.sh <slug> <ok|fail> [body]}"
status="${2:?usage: hc-ping.sh <slug> <ok|fail> [body]}"
body="${3:-}"

case "$status" in
  ok) suffix="" ;;
  fail) suffix="/fail" ;;
  *)
    echo "::error::status must be ok or fail."
    exit 1
    ;;
esac
if [[ ! "$slug" =~ ^[a-z0-9-]+$ ]]; then
  echo "::error::bad slug."
  exit 1
fi
if [ -z "${HC_PING_KEY:-}" ]; then
  echo "::warning::HC_PING_KEY is not set; skipping the ${slug} ping."
  exit 0
fi

# Healthchecks.io stores up to 100 kB of body; the alert shows the last 10 kB.
body="${body:0:8000}"
url="${HC_PING_URL_BASE:-https://hc-ping.com}/${HC_PING_KEY}/${slug}${suffix}"
# The URL is passed through a curl config on stdin so it is not in the process list, and curl's
# stderr is dropped so no error message can echo it.
if printf 'url = "%s"\n' "$url" | curl --config - --silent --fail --max-time 10 --retry 3 \
  --data-raw "$body" --output /dev/null 2> /dev/null; then
  echo "Pinged ${slug}${suffix}."
else
  echo "::warning::The ${slug}${suffix} ping failed."
fi
