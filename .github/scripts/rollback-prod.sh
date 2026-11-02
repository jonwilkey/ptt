#!/usr/bin/env bash
# Rolls production back after a failed deploy (docs/operations.md, "The rollback"). Run from the
# repository root of the failed release's tree, with `ssh ptt-prod` working (ssh-setup.sh) and
# ansible-core on PATH:
#
#   FAILED_IMAGE=<image@digest> PREVIOUS_IMAGE=<image@digest> DEPLOY_START=<host UTC timestamp> \
#   BEFORE_SCHEMA=<version, or empty if unknown> PTT_DOMAIN=<domain> SOPS_AGE_KEY=<key> \
#   GHCR_TOKEN=<token with read:packages> .github/scripts/rollback-prod.sh
#
#   1. On the host (rollback-host.sh restore): stops pttd and, if the deploy migrated, restores its
#      pre-migration snapshot, keeping the failed database for a person to inspect.
#   2. Checks out the previous release's tag (from the previous image's version label, any
#      +maint... suffix dropped) and runs that tree's deploy.yml with the previous image, with
#      that tree's collections installed into a fresh ANSIBLE_COLLECTIONS_PATH.
#   3. Runs check-prod.sh.
#   4. Writes the job summary and exits 1 with ::error:: (the deploy failed either way). With
#      DRILL=true it exits 0 when the rollback itself worked, so the drill can check the result.
#
# Safe to rerun: the host step does not restore twice, and the redeploy is idempotent.
# Writes rolled_back (true when production is healthy on the previous image), migrated,
# after_schema and restored to $GITHUB_OUTPUT when set.

set -euo pipefail

: "${FAILED_IMAGE:?}" "${PREVIOUS_IMAGE:?}" "${DEPLOY_START:?}" "${PTT_DOMAIN:?}"
before="${BEFORE_SCHEMA:-unknown}"
drill="${DRILL:-false}"
digest_re='^[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}$'
[[ $FAILED_IMAGE =~ $digest_re && $PREVIOUS_IMAGE =~ $digest_re ]] || {
	echo "::error::FAILED_IMAGE and PREVIOUS_IMAGE must be pinned by digest."
	exit 1
}
[[ $DEPLOY_START =~ ^[0-9]{8}T[0-9]{6}\.[0-9]{3}Z$ && $before =~ ^([0-9]+|unknown)$ ]] || {
	echo "::error::DEPLOY_START or BEFORE_SCHEMA is malformed ('${DEPLOY_START}', '${before}')."
	exit 1
}
tmp="${RUNNER_TEMP:-$(mktemp -d)}"
summary="${GITHUB_STEP_SUMMARY:-/dev/stderr}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

output() { [ -z "${GITHUB_OUTPUT:-}" ] || printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"; }
warn() {
	echo "::warning::$*"
	warnings+=("$*")
}
warnings=()
after_schema=unknown migrated=unknown restored="" failed_db_dir="" snapshots="" previous_version=""

report() {
	local result="$1"
	{
		echo
		echo "## Deploy of \`${FAILED_IMAGE}\` failed: ${result}"
		echo
		echo "| | |"
		echo "| --- | --- |"
		echo "| Rolled back to | \`${PREVIOUS_IMAGE}\` |"
		echo "| Schema before / after the deploy | ${before} / ${after_schema} |"
		echo "| Deploy migrated | ${migrated} |"
		echo "| Snapshot restored | ${restored:-none} |"
		echo "| Failed database kept in | ${failed_db_dir:-not moved} |"
		echo "| Previous release | ${previous_version:-unknown} |"
		if [ "${#warnings[@]}" -gt 0 ]; then
			echo
			printf -- '- **%s**\n' "${warnings[@]}"
		fi
	} >> "$summary"
}

fail_rollback() {
	output rolled_back false
	report "ROLLBACK FAILED"
	echo "::error::Deploy of ${FAILED_IMAGE} failed and the rollback failed too ($*). A person is needed (runbook: \"Server down, VPS reachable\")."
	exit 1
}

# 1. The database.
host_out=""
# shellcheck disable=SC2029 # expanded here on purpose; the values were validated above
if ! host_out="$(ssh ptt-prod "sudo -n bash -s -- restore ${DEPLOY_START} ${before} ${PREVIOUS_IMAGE}" < "${here}/rollback-host.sh")"; then
	fail_rollback "the host step failed; the database was left as the log above shows"
fi
while IFS='=' read -r key value; do
	case "$key" in
	after_schema) after_schema="$value" ;;
	migrated) migrated="$value" ;;
	restored) restored="$value" ;;
	failed_db_dir) failed_db_dir="$value" ;;
	snapshots) snapshots="$value" ;;
	previous_version) previous_version="$value" ;;
	esac
done <<< "$host_out"
output migrated "$migrated"
output after_schema "$after_schema"
output restored "$restored"

case "$migrated" in
yes)
	if [ -z "$restored" ]; then
		warn "THE DEPLOY MIGRATED (schema ${before} -> ${after_schema}) BUT NO PRE-MIGRATION SNAPSHOT TAKEN AT OR AFTER ${DEPLOY_START} EXISTS: ONLY THE IMAGE WAS ROLLED BACK; THE PREVIOUS RELEASE RUNS ON THE NEWER SCHEMA. A person decides about the database. Snapshots: ${snapshots:-none}."
	fi
	;;
unknown)
	warn "COULD NOT TELL WHETHER THE DEPLOY MIGRATED (schema before: ${before}, after: ${after_schema}): ONLY THE IMAGE WAS ROLLED BACK. A person decides about the database. Snapshots: ${snapshots:-none}."
	;;
esac

# 2. The previous release's tree and image.
tree="$PWD"
if [[ $previous_version =~ ^([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?)(\+.*)?$ ]]; then
	prev_tag="server-v${BASH_REMATCH[1]}"
	wt="${tmp}/rollback-tree"
	git worktree remove --force "$wt" 2> /dev/null || rm -rf "$wt"
	if git fetch --quiet --depth 1 origin "refs/tags/${prev_tag}:refs/tags/${prev_tag}" &&
		git worktree add --quiet --detach "$wt" "refs/tags/${prev_tag}"; then
		tree="$wt"
		echo "Redeploying with ${prev_tag}'s deploy files."
	else
		warn "Could not check out ${prev_tag}; redeploying the previous image with this release's deploy files."
	fi
else
	warn "The previous image has no usable version label ('${previous_version}'); redeploying it with this release's deploy files."
fi

collections="${tmp}/rollback-collections"
vars="${tmp}/rollback-vars.json"
trap 'rm -f "$vars"' EXIT
rm -rf "$collections"
(umask 077 && jq -n --arg d "$PTT_DOMAIN" '{ptt_domain: $d}' > "$vars")
# Chained with &&: errexit does not apply inside an if condition.
if ! (
	cd "${tree}/infra/ansible" &&
		export ANSIBLE_COLLECTIONS_PATH="$collections" &&
		ansible-galaxy collection install -r requirements.yml -p "$collections" &&
		ansible-playbook -i inventory/prod.yml deploy.yml -u deploy \
			-e "@${vars}" -e "pttd_image=${PREVIOUS_IMAGE}"
); then
	fail_rollback "redeploying ${PREVIOUS_IMAGE} failed"
fi

# 3. Health.
if ! "${here}/check-prod.sh"; then
	fail_rollback "production is not healthy on ${PREVIOUS_IMAGE}"
fi

# 4. Report.
output rolled_back true
report "rolled back to the previous release"
if [ "$drill" = true ]; then
	echo "::notice::Drill: deploy of ${FAILED_IMAGE} failed and was rolled back to ${PREVIOUS_IMAGE}."
	exit 0
fi
echo "::error::Deploy of ${FAILED_IMAGE} failed; rolled back to ${PREVIOUS_IMAGE}."
exit 1
