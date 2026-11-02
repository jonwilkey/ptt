#!/usr/bin/env bash
# weekly-maintenance's first step: decide whether this run is the one that is 03:00 Monday in
# America/Los_Angeles (docs/operations.md, "weekly-maintenance", "Schedule"). GitHub cron is UTC,
# so the workflow fires at both 10:00 UTC (03:00 PDT) and 11:00 UTC (03:00 PST) and only the
# matching one proceeds.
#
# Env: EVENT_NAME (github.event_name), SCHEDULE (github.event.schedule, schedule runs only),
#      MAINT_NOW (tests only: the time to treat as "now", any `date -d` string).
# Prints `run=true` or `run=false` to $GITHUB_OUTPUT (stdout when unset). Exits non-zero for a
# schedule it does not know, so editing the crons without editing this fails loudly.

set -euo pipefail

event="${EVENT_NAME:?EVENT_NAME is required}"
out="${GITHUB_OUTPUT:-/dev/stdout}"

if [ "$event" = workflow_dispatch ]; then
  echo "Manual run: proceeding."
  echo "run=true" >> "$out"
  exit 0
fi

if [ "$event" != schedule ]; then
  echo "::error::weekly-maintenance does not run on '$event'."
  exit 1
fi

case "${SCHEDULE:-}" in
  "0 10 * * 1") utc_hour=10 ;;
  "0 11 * * 1") utc_hour=11 ;;
  *)
    echo "::error::Unknown schedule '${SCHEDULE:-}'."
    exit 1
    ;;
esac

# The UTC day this run belongs to, then what the clock in Los Angeles reads at that cron's hour.
day="$(date -u -d "${MAINT_NOW:-now}" +%F)"
local_time="$(TZ=America/Los_Angeles date -d "${day} ${utc_hour}:00:00 UTC" +%H:%M)"

if [ "$local_time" = "03:00" ]; then
  echo "The ${utc_hour}:00 UTC schedule is 03:00 in Los Angeles on ${day}: proceeding."
  echo "run=true" >> "$out"
else
  echo "The ${utc_hour}:00 UTC schedule is ${local_time} in Los Angeles on ${day}, not 03:00: skipping."
  echo "run=false" >> "$out"
fi
