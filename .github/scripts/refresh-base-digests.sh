#!/usr/bin/env bash
# Moves each pinned base image of a Dockerfile to the current digest of the same tag
# (docs/operations.md, "weekly-maintenance", step 2). Only digests move, never tags:
#
#   FROM golang:1.27.3-trixie@sha256:<old> AS build   ->   ...@sha256:<current digest of golang:1.27.3-trixie>
#
# Usage: refresh-base-digests.sh <Dockerfile>
# Needs `docker buildx imagetools inspect` (a registry lookup; nothing is pulled). Prints one
# line per pinned image. Fails if a digest cannot be resolved, so a registry outage never ships
# an unrefreshed image as if it were refreshed.

set -euo pipefail

file="${1:?usage: refresh-base-digests.sh <Dockerfile>}"
content="$(cat "$file")"
pattern='^FROM( --[^ ]+)* ([^ @]+)@(sha256:[0-9a-f]{64})( |$)'
count=0

while IFS= read -r line; do
  [[ "$line" =~ $pattern ]] || continue
  ref="${BASH_REMATCH[2]}"
  old="${BASH_REMATCH[3]}"
  if [[ "$ref" != *:* ]]; then
    echo "::error::${ref} has a digest but no tag; the weekly refresh keeps tags."
    exit 1
  fi
  new="$(docker buildx imagetools inspect "$ref" --format '{{json .Manifest}}' | jq -r .digest)"
  if [[ ! "$new" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "::error::Could not resolve the current digest of ${ref} (got '${new}')."
    exit 1
  fi
  count=$((count + 1))
  if [ "$new" = "$old" ]; then
    echo "unchanged  ${ref}@${old}"
  else
    content="${content//"${ref}@${old}"/"${ref}@${new}"}"
    echo "refreshed  ${ref}@${old} -> ${new}"
  fi
done < <(printf '%s\n' "$content")

if [ "$count" -eq 0 ]; then
  echo "::error::${file} has no FROM <image>:<tag>@sha256:<digest> line."
  exit 1
fi
printf '%s\n' "$content" > "$file"
