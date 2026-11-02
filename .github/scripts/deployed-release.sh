#!/usr/bin/env bash
# Maps the deployed image's org.opencontainers.image.version label to the release it was built
# from: `+maint...` (weekly rebuilds) and any other build metadata are dropped
# (docs/operations.md, "weekly-maintenance").
#
#   .github/scripts/deployed-release.sh 1.2.3+maint.20261102
#   version=1.2.3
#   tag=server-v1.2.3
#
# Fails if the label is not a release version.

set -euo pipefail

label="${1:?usage: deployed-release.sh <version label>}"
if [[ ! "$label" =~ ^([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?)(\+[0-9A-Za-z.-]+)?$ ]]; then
  echo "::error::The deployed image's version label '${label}' is not a release version."
  exit 1
fi
echo "version=${BASH_REMATCH[1]}"
echo "tag=server-v${BASH_REMATCH[1]}"
