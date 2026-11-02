#!/usr/bin/env bash
# The host side of a deploy and its automatic rollback (docs/operations.md, "Server deploys and
# automatic rollback"). Runs on ptt-prod as root, fed over SSH by the runner:
#
#   ssh ptt-prod 'sudo -n bash -s -- schema' < .github/scripts/rollback-host.sh
#   ssh ptt-prod 'sudo -n bash -s -- restore <deploy_start> <before_schema|unknown> <previous image>' \
#     < .github/scripts/rollback-host.sh
#
# schema: prints the database's schema version from `pttd migrate status`, run with the image in
#   compose.yaml (the highest VERSION whose STATE is applied). Fails if that does not work.
#
# restore: reads after_schema (/internal/status if pttd answers with db_ok, otherwise
#   `migrate status` once pttd is stopped), stops pttd, and decides whether this deploy migrated.
#   If it did and a pre-migrate-*-v<before_schema>.db snapshot taken at or after deploy_start
#   exists, it moves the failed database to /data/rolled-back-<deploy_start>/, installs the
#   snapshot as /data/pttd.db, and renames the snapshot to restored-<name>. It never starts pttd;
#   the runner redeploys the previous release. Safe to rerun: a restored database is no longer
#   ahead of before_schema, and a run cut off between moving the failed files and installing the
#   snapshot finishes the restore.
#   Prints key=value lines on stdout for the runner: after_schema, migrated (yes, no, unknown),
#   restored (the restored-* name, or empty), failed_db_dir, snapshots (the pre-migrate-* names),
#   previous_version (the previous image's org.opencontainers.image.version label). Logs go to
#   stderr.
#
# PTT_DATA_DIR, PTT_COMPOSE_FILE and PTT_STATUS_URL override the production paths for tests.

set -euo pipefail

data="${PTT_DATA_DIR:-/data}"
snapdir="${data}/snapshots"
compose=(docker compose -f "${PTT_COMPOSE_FILE:-/opt/ptt/compose.yaml}")
status_url="${PTT_STATUS_URL:-http://127.0.0.1:9090/internal/status}"
db_files=(pttd.db pttd.db-wal pttd.db-shm)

out() { printf '%s=%s\n' "$1" "$2"; }
log() { printf '%s\n' "$*" >&2; }
die() {
	log "error: $*"
	exit 1
}

schema_from_status() {
	local s
	s="$(curl -fsS --max-time 5 "$status_url" 2> /dev/null)" || return 1
	[[ $s =~ \"db_ok\":true ]] || return 1
	[[ $s =~ \"schema_version\":([0-9]+) ]] || return 1
	echo "${BASH_REMATCH[1]}"
}

schema_from_migrate() {
	local s
	s="$("${compose[@]}" run --rm --no-deps -T pttd migrate status)" || return 1
	grep -q '^VERSION[[:space:]]' <<< "$s" || return 1
	awk 'NR > 1 && $2 == "applied" && $1 ~ /^[0-9]+$/ && $1 + 0 > max { max = $1 + 0 }
		END { print max + 0 }' <<< "$s"
}

# The newest pre-migrate-<ts>-v<before>.db with ts >= deploy_start; names sort by time.
qualifying_snapshot() {
	local before="$1" start="$2" f name ts found=""
	for f in "$snapdir"/pre-migrate-*-v"$before".db; do
		[ -f "$f" ] || continue
		name="${f##*/}"
		ts="${name#pre-migrate-}"
		ts="${ts%-v"$before".db}"
		[[ $ts =~ ^[0-9]{8}T[0-9]{6}\.[0-9]{3}Z$ ]] || continue
		[[ $ts < $start ]] && continue
		found="$name"
	done
	echo "$found"
}

restore() {
	local start="$1" before="$2" previous="$3"
	[[ $start =~ ^[0-9]{8}T[0-9]{6}\.[0-9]{3}Z$ ]] || die "deploy_start '$start' is not a snapshot timestamp"
	[[ $before =~ ^([0-9]+|unknown)$ ]] || die "before_schema '$before' is not a number or 'unknown'"
	[[ $previous =~ ^[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]] || die "previous image '$previous' is not pinned by digest"

	local failed_dir="${data}/rolled-back-${start}" after="" migrated stopped=true resume=false
	if [ ! -e "${data}/pttd.db" ] && [ -e "${failed_dir}/pttd.db" ]; then
		resume=true
		log "A previous run moved the failed database to ${failed_dir} but did not finish; finishing it."
	elif after="$(schema_from_status)"; then
		log "after_schema ${after} (from /internal/status)."
	fi
	"${compose[@]}" stop pttd >&2 || {
		stopped=false
		log "warning: could not stop pttd."
	}
	if ! $resume && [ -z "$after" ]; then
		if after="$(schema_from_migrate)"; then
			log "after_schema ${after} (from migrate status)."
		else
			after=""
			log "after_schema is unknown: /internal/status and migrate status both failed."
		fi
	fi

	if $resume; then
		migrated=yes
	elif [[ $before =~ ^[0-9]+$ && $after =~ ^[0-9]+$ ]]; then
		if ((after > before)); then migrated=yes; else migrated=no; fi
	else
		migrated=unknown
	fi

	local snap="" restored=""
	if [ "$migrated" = yes ]; then
		snap="$(qualifying_snapshot "$before" "$start")"
	fi
	if [ -n "$snap" ]; then
		$stopped || die "could not stop pttd; not touching the database"
		local d
		for d in "${data}"/rolled-back-*; do
			[ "$d" = "$failed_dir" ] || rm -rf -- "$d"
		done
		install -d -m 0700 "$failed_dir"
		install -o 65532 -g 65532 -m 0600 "${snapdir}/${snap}" "${data}/.pttd.db.restoring"
		local f
		for f in "${db_files[@]}"; do
			[ -e "${data}/${f}" ] || continue
			[ ! -e "${failed_dir}/${f}" ] || die "${failed_dir}/${f} already exists; not overwriting it"
			mv -- "${data}/${f}" "${failed_dir}/${f}"
		done
		mv -f -- "${data}/.pttd.db.restoring" "${data}/pttd.db"
		for f in "$snapdir"/restored-*; do
			[ -e "$f" ] && rm -f -- "$f"
		done
		restored="restored-${snap}"
		mv -- "${snapdir}/${snap}" "${snapdir}/${restored}"
		log "Restored ${snap} to ${data}/pttd.db; the failed database is in ${failed_dir}."
	fi

	[ -e "${data}/pttd.db" ] || die "${data}/pttd.db is missing; redeploying now would start an empty database"

	local snaps="" f
	for f in "$snapdir"/pre-migrate-*.db; do
		[ -f "$f" ] && snaps+="${snaps:+ }${f##*/}"
	done
	local version
	version="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$previous" 2> /dev/null)" || version=""
	[ "$version" != "<no value>" ] || version=""

	out after_schema "${after:-unknown}"
	out migrated "$migrated"
	out restored "$restored"
	[ -n "$restored" ] || failed_dir=""
	out failed_db_dir "$failed_dir"
	out snapshots "$snaps"
	out previous_version "$version"
}

case "${1:-}" in
schema)
	schema_from_migrate
	;;
restore)
	[ "$#" -eq 4 ] || die "usage: restore <deploy_start> <before_schema|unknown> <previous image>"
	restore "$2" "$3" "$4"
	;;
*)
	die "usage: $0 schema | restore <deploy_start> <before_schema|unknown> <previous image>"
	;;
esac
