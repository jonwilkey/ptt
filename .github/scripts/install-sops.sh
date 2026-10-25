#!/usr/bin/env bash
# Installs the pinned sops release into $RUNNER_TEMP/sops-bin and adds it to PATH, for Ansible's
# community.sops lookup (the app role's app_secrets).
#
#   .github/scripts/install-sops.sh
#
# This is the single place the sops pin lives. The hash below is authoritative: the release's
# checksums file must agree with it, and so must the downloaded binary. A Renovate bump of the
# version fails the ci workflow's Ansible job (which runs this script) until SOPS_SHA256 is
# updated to match, which is the intended review point. Both may be overridden from the
# environment.
set -euo pipefail

# renovate: datasource=github-releases depName=getsops/sops
SOPS_VERSION="${SOPS_VERSION:-v3.13.3}"
SOPS_SHA256="${SOPS_SHA256:-e5bec3346a873ae91d871550f3e698c1aad962aff462a080e40f25fde17fef6b}"
: "${RUNNER_TEMP:?}" "${GITHUB_PATH:?}"
asset="sops-${SOPS_VERSION}.linux.amd64"
base="https://github.com/getsops/sops/releases/download/${SOPS_VERSION}"
dir="$RUNNER_TEMP/sops-bin"
mkdir -p "$dir"
cd "$dir"
curl -fsSLo "$asset" "$base/$asset"
curl -fsSLo checksums.txt "$base/sops-${SOPS_VERSION}.checksums.txt"
if ! grep -qxF "${SOPS_SHA256}  ${asset}" checksums.txt; then
	echo "::error::SOPS_SHA256 does not match the ${SOPS_VERSION} checksums file"
	exit 1
fi
echo "${SOPS_SHA256}  ${asset}" | sha256sum --check --strict -
install -m 0755 "$asset" sops
rm -f "$asset" checksums.txt
echo "$dir" >> "$GITHUB_PATH"
