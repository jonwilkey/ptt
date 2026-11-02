#!/usr/bin/env bash
# Maps the deployed image's org.opencontainers.image.version label to the release it was built
# from: the `+maint.<YYYYMMDD>` suffix of weekly rebuilds is dropped (docs/operations.md,
# "weekly-maintenance"). Any other `+...` build metadata is rejected.
#
#   .github/scripts/deployed-release.sh 1.2.3+maint.20261102
#   version=1.2.3
#   tag=server-v1.2.3
#
# Fails if the label is not a release version.

set -euo pipefail

label="${1:?usage: deployed-release.sh <version label>}"
if [[ ! "$label" =~ ^([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?)(\+maint\.[0-9]{8})?$ ]]; then
  echo "::error::The deployed image's version label '${label}' is not a release version."
  exit 1
fi
echo "version=${BASH_REMATCH[1]}"
echo "tag=server-v${BASH_REMATCH[1]}"
