#!/usr/bin/env bash
# Runs rollback-host.sh's decisions against a temporary /data with fake docker, curl and install
# (no root, no Docker, no host):
#
#   .github/scripts/rollback-host-test.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir "$work/bin"
cat > "$work/bin/curl" << 'EOF'
#!/usr/bin/env bash
[ -n "${FAKE_STATUS:-}" ] || exit 7
printf '%s' "$FAKE_STATUS"
EOF
cat > "$work/bin/docker" << 'EOF'
#!/usr/bin/env bash
case "$*" in
*" stop pttd") exit "${FAKE_STOP_RC:-0}" ;;
*" migrate status")
	[ -n "${FAKE_MIGRATE:-}" ] || exit 1
	printf 'VERSION  STATE    APPLIED AT (UTC)      FILE\n'
	for v in 1 2 3 4 5 99999; do
		if [ "$v" -le "$FAKE_MIGRATE" ]; then s=applied; else s=pending; fi
		printf '%s  %s  -  %05d_x.sql\n' "$v" "$s" "$v"
	done
	;;
"image inspect"*) printf '%s\n' "${FAKE_LABEL-1.4.0+maint.20261026}" ;;
*) exit 1 ;;
esac
EOF
cat > "$work/bin/install" << 'EOF'
#!/usr/bin/env bash
args=()
while [ "$#" -gt 0 ]; do
	case "$1" in
	-o | -g) shift 2 ;;
	*) args+=("$1"); shift ;;
	esac
done
exec /usr/bin/install "${args[@]}"
EOF
chmod +x "$work/bin/"*

start=20261102T031500.000Z
image="ghcr.io/example/ptt/pttd@sha256:$(printf '%064d' 0)"
failures=0

# setup <snapshot names...>: a fresh /data with a live database and the given snapshots.
setup() {
	data="$work/data"
	rm -rf "$data"
	mkdir -p "$data/snapshots" "$data/rolled-back-20261001T000000.000Z"
	echo failed > "$data/pttd.db"
	echo wal > "$data/pttd.db-wal"
	echo shm > "$data/pttd.db-shm"
	local s
	for s in "$@"; do echo "$s" > "$data/snapshots/$s"; done
}

run() {
	PATH="$work/bin:$PATH" PTT_DATA_DIR="$data" PTT_COMPOSE_FILE=/dev/null \
		bash "$here/rollback-host.sh" restore "$start" "$1" "$image" 2> "$work/log"
}

check() {
	local name="$1" got="$2" want="$3"
	if [ "$got" = "$want" ]; then
		echo "ok    $name"
	else
		echo "FAIL  $name"
		echo "      got:  $got"
		echo "      want: $want"
		sed 's/^/      log: /' "$work/log"
		failures=$((failures + 1))
	fi
}

field() { sed -n "s/^$1=//p" <<< "$2"; }
summary() { echo "migrated=$(field migrated "$1") restored=$(field restored "$1") db=$(cat "$data/pttd.db")"; }

old=pre-migrate-20261102T030900.000Z-v5.db
new=pre-migrate-20261102T031512.345Z-v5.db
newer=pre-migrate-20261102T031530.000Z-v5.db
other=pre-migrate-20261102T031600.000Z-v4.db
status99999='{"version":"x","active_sessions":0,"db_ok":true,"schema_version":99999}'

setup "$old" "$new" "$newer" "$other" restored-pre-migrate-20261001T000000.000Z-v4.db
o="$(FAKE_STATUS="$status99999" run 5)"
check "migrated, qualifying snapshot: newest at or after deploy_start restored" \
	"$(summary "$o")" "migrated=yes restored=restored-$newer db=$newer"
check "  failed files moved, older rolled-back dir and restored-* removed" \
	"$(cd "$data" && echo rolled-back-*/* snapshots/restored-*)" \
	"rolled-back-$start/pttd.db rolled-back-$start/pttd.db-shm rolled-back-$start/pttd.db-wal snapshots/restored-$newer"
check "  live WAL and SHM gone; failed database kept" \
	"$(find "$data" -maxdepth 1 -name 'pttd.db-*' | wc -l) $(cat "$data/rolled-back-$start/pttd.db")" "0 failed"
check "  previous version label reported" "$(field previous_version "$o")" "1.4.0+maint.20261026"

o2="$(FAKE_MIGRATE=5 run 5)"
check "rerun after a restore: not migrated, nothing moved again" \
	"$(summary "$o2") $(cat "$data/rolled-back-$start/pttd.db")" "migrated=no restored= db=$newer failed"

setup "pre-migrate-${start}-v5.db"
o="$(FAKE_STATUS="$status99999" run 5)"
check "snapshot taken exactly at deploy_start qualifies" \
	"$(summary "$o")" "migrated=yes restored=restored-pre-migrate-${start}-v5.db db=pre-migrate-${start}-v5.db"

setup "$old" "$other"
o="$(FAKE_STATUS="$status99999" run 5)"
check "migrated, only an older (reused) snapshot: image only" \
	"$(summary "$o") $(field snapshots "$o")" "migrated=yes restored= db=failed $old $other"

setup "$new"
o="$(FAKE_STATUS='{"db_ok":false,"schema_version":0}' FAKE_MIGRATE=99999 run 5)"
check "db_ok false: after_schema from migrate status" \
	"$(summary "$o") $(field after_schema "$o")" "migrated=yes restored=restored-$new db=$new 99999"

setup "$new"
o="$(run 5)"
check "unknown after_schema (no status, migrate status fails): image only" \
	"$(summary "$o") $(field after_schema "$o")" "migrated=unknown restored= db=failed unknown"

setup "$new"
o="$(FAKE_STATUS="$status99999" run unknown)"
check "unknown before_schema: image only" "$(summary "$o")" "migrated=unknown restored= db=failed"

setup "$new"
o="$(FAKE_STATUS='{"db_ok":true,"schema_version":5}' run 5)"
check "not migrated (Caddyfile or code-only failure)" "$(summary "$o")" "migrated=no restored= db=failed"

setup "$new"
mkdir "$data/rolled-back-$start"
mv "$data/pttd.db" "$data/pttd.db-wal" "$data/rolled-back-$start/"
o="$(run 5)"
check "rerun after a cut-off restore: finishes it" \
	"$(summary "$o") $(cat "$data/rolled-back-$start/pttd.db")" "migrated=yes restored=restored-$new db=$new failed"

setup "$new"
rc=0
FAKE_STATUS="$status99999" FAKE_STOP_RC=1 run 5 > /dev/null || rc=$?
check "pttd will not stop: refuses to touch the database" \
	"$rc $(cat "$data/pttd.db") $(ls "$data/snapshots")" "1 failed $new"

setup "$new"
o="$(FAKE_STATUS="$status99999" FAKE_LABEL='' run 5)"
check "no version label: reported empty" "$(field previous_version "$o")" ""

setup
rm "$data/pttd.db"
rc=0
run 5 > /dev/null || rc=$?
check "no live database and nothing to finish: refuses to redeploy" "$rc" "1"

[ "$failures" -eq 0 ] || {
	echo "$failures check(s) failed."
	exit 1
}
echo "All checks passed."
