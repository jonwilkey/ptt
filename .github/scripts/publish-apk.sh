#!/usr/bin/env bash
# Publishes a release APK to production (docs/operations.md, "Publishing an APK"; ADR 0011).
#
# On the runner, after tailscale/github-action and ssh-setup.sh:
#
#   APK_FILE=ptt-0.5.0.apk APP_VERSION=0.5.0 APP_VERSION_CODE=5000 APP_MIN_CODE=0 \
#     [ALLOW_OLDER=true] GITHUB_RUN_ID=<id> .github/scripts/publish-apk.sh
#
# APK_FILE has a sibling <APK_FILE>.sha256 (sha256sum format). The script copies the APK to
# /tmp/ptt-apk-<run id> on the host, runs its own host mode there as root (fed over SSH, like
# rollback-host.sh), then checks /internal/status reports app_latest equal to the version.
#
# Host mode (what runs as root on ptt-prod):
#
#   sudo -n bash -s -- host <staged file> <version> <version code> <sha256> <size> <min code> <allow_older>
#
# 1. The staged file's size and SHA-256 must match. 2. ptt-<version>.apk already there: same hash
# is fine (a rerun), a different hash is an error (versions are immutable); a version code lower
# than latest.json's is refused unless allow_older is true. 3. install as a dot-tmp file, then mv.
# 4. latest.json the same way, after the APK. 5. Keep the newest three ptt-*.apk files and the one
# latest.json names.
#
# PTT_APK_DIR overrides /opt/ptt/apk for tests.

set -euo pipefail

version_re='^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$'

die() {
	echo "::error::publish-apk: $*" >&2
	exit 1
}

code_of() {
	[[ $1 =~ $version_re ]] || return 1
	echo $((BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 1000 + BASH_REMATCH[3]))
}

host_mode() {
	[ "$#" -eq 7 ] || die "host mode needs 7 arguments, got $#"
	local staged="$1" version="$2" code="$3" sha="$4" size="$5" min_code="$6" allow_older="$7"
	local dir="${PTT_APK_DIR:-/opt/ptt/apk}"
	local want_code file="ptt-${version}.apk" cur_code=""

	want_code="$(code_of "$version")" || die "'$version' is not a MAJOR.MINOR.PATCH version"
	[ "$want_code" -ge 1 ] || want_code=1
	[ "$code" = "$want_code" ] || die "version code $code is not the code of $version ($want_code)"
	[[ $sha =~ ^[0-9a-f]{64}$ ]] || die "sha256 '$sha' is not 64 lowercase hex digits"
	[[ $size =~ ^[0-9]+$ ]] && [[ $min_code =~ ^[0-9]+$ ]] || die "size and min code must be integers"
	[ "$min_code" -le "$code" ] || die "min supported version code $min_code is above $code"
	case "$allow_older" in true | false) ;; *) die "allow_older must be true or false" ;; esac
	[ -d "$dir" ] || die "$dir does not exist on the host (the app role creates it)"
	[ -f "$staged" ] && [ ! -L "$staged" ] || die "$staged is not a regular file"

	[ "$(stat -c %s "$staged")" = "$size" ] || die "staged file size differs from $size"
	[ "$(sha256sum < "$staged" | cut -d' ' -f1)" = "$sha" ] || die "staged file SHA-256 differs from the build's"

	if [ -f "$dir/latest.json" ]; then
		cur_code="$(sed -n 's/^ *"version_code": *\([0-9][0-9]*\),\{0,1\} *$/\1/p' "$dir/latest.json")"
		[[ $cur_code =~ ^[0-9]+$ ]] || die "$dir/latest.json has no readable version_code; fix it by hand"
	fi

	local have=""
	if [ -f "$dir/$file" ]; then
		have="$(sha256sum < "$dir/$file" | cut -d' ' -f1)"
		[ "$have" = "$sha" ] || die "$file is already published with a different SHA-256 ($have); versions are immutable, ship a new version"
		echo "$file is already published with the same hash; continuing."
	fi

	if [ -n "$cur_code" ] && [ "$code" -lt "$cur_code" ] && [ "$allow_older" != true ]; then
		die "$version (code $code) is lower than the published code $cur_code; run by hand with allow_older to re-point latest"
	fi

	if [ -z "$have" ]; then
		install -o root -g root -m 0644 "$staged" "$dir/.${file}.tmp"
		mv -f "$dir/.${file}.tmp" "$dir/$file"
		echo "Installed $dir/$file."
	fi

	local tmpjson
	tmpjson="$(mktemp)"
	cat > "$tmpjson" << JSON
{
  "version_name": "${version}",
  "version_code": ${code},
  "sha256": "${sha}",
  "size": ${size},
  "min_supported_version_code": ${min_code},
  "file": "${file}"
}
JSON
	install -o root -g root -m 0644 "$tmpjson" "$dir/.latest.json.tmp"
	rm -f "$tmpjson"
	mv -f "$dir/.latest.json.tmp" "$dir/latest.json"
	echo "Wrote $dir/latest.json for $version."

	local f n v c i=0
	local -a ranked=()
	for f in "$dir"/ptt-*.apk; do
		[ -e "$f" ] || continue
		n="${f##*/}"
		v="${n#ptt-}"
		v="${v%.apk}"
		c="$(code_of "$v")" || continue
		ranked+=("$(printf '%09d %s' "$c" "$n")")
	done
	while read -r c n; do
		[ -n "$n" ] || continue
		i=$((i + 1))
		if [ "$i" -gt 3 ] && [ "$n" != "$file" ]; then
			rm -f "$dir/$n"
			echo "Pruned $n."
		fi
	done < <(printf '%s\n' "${ranked[@]}" | sort -r)
}

if [ "${1:-}" = host ]; then
	shift
	host_mode "$@"
	exit 0
fi

: "${APK_FILE:?APK_FILE must be set}"
: "${APP_VERSION:?APP_VERSION must be set}"
: "${APP_VERSION_CODE:?APP_VERSION_CODE must be set}"
: "${APP_MIN_CODE:?APP_MIN_CODE must be set}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID must be set}"
allow_older="${ALLOW_OLDER:-false}"
[[ $GITHUB_RUN_ID =~ ^[0-9]+$ ]] || die "GITHUB_RUN_ID '$GITHUB_RUN_ID' is not numeric"
code_of "$APP_VERSION" > /dev/null || die "'$APP_VERSION' is not a MAJOR.MINOR.PATCH version"
[[ $APP_VERSION_CODE =~ ^[0-9]+$ && $APP_MIN_CODE =~ ^[0-9]+$ ]] || die "version codes must be integers"
[ -f "$APK_FILE" ] || die "$APK_FILE not found"
[ -f "$APK_FILE.sha256" ] || die "$APK_FILE.sha256 not found"

sha="$(cut -d' ' -f1 < "$APK_FILE.sha256")"
[[ $sha =~ ^[0-9a-f]{64}$ ]] || die "$APK_FILE.sha256 does not start with a SHA-256"
[ "$(sha256sum < "$APK_FILE" | cut -d' ' -f1)" = "$sha" ] || die "$APK_FILE does not match its .sha256 file"
size="$(stat -c %s "$APK_FILE")"
staged="/tmp/ptt-apk-${GITHUB_RUN_ID}"

cleanup() { ssh ptt-prod "sudo -n rm -f ${staged}" > /dev/null 2>&1 || true; }
trap cleanup EXIT

scp -q "$APK_FILE" "ptt-prod:${staged}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC2029 # expanded here on purpose; every value was validated above
ssh ptt-prod "sudo -n bash -s -- host ${staged} ${APP_VERSION} ${APP_VERSION_CODE} ${sha} ${size} ${APP_MIN_CODE} ${allow_older}" < "$here/publish-apk.sh"

got=""
for _ in $(seq 10); do
	status="$(ssh ptt-prod 'curl -fsS --max-time 5 http://127.0.0.1:9090/internal/status' 2> /dev/null || true)"
	got="$(jq -r '.app_latest // empty' <<< "$status" 2> /dev/null || true)"
	[ "$got" = "$APP_VERSION" ] && break
	sleep 3
done
[ "$got" = "$APP_VERSION" ] || die "/internal/status reports app_latest '${got:-null}', not $APP_VERSION (pttd rejects latest.json or the APK; see pttd's log)"
echo "ok    app_latest is $APP_VERSION"
