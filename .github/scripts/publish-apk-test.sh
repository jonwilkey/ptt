#!/usr/bin/env bash
# Runs publish-apk.sh's host mode against a temporary APK directory (no root, no host):
#
#   .github/scripts/publish-apk-test.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir "$work/bin"
cat > "$work/bin/install" << 'EOF2'
#!/usr/bin/env bash
args=()
while [ "$#" -gt 0 ]; do
	case "$1" in
	-o | -g) shift 2 ;;
	*) args+=("$1"); shift ;;
	esac
done
echo "install ${args[*]: -1}" >> "$FAKE_LOG"
exec /usr/bin/install "${args[@]}"
EOF2
cat > "$work/bin/mv" << 'EOF2'
#!/usr/bin/env bash
echo "mv ${*: -1}" >> "$FAKE_LOG"
exec /usr/bin/mv "$@"
EOF2
chmod +x "$work/bin/"*
export PATH="$work/bin:$PATH"
export FAKE_LOG="$work/log"

dir="$work/apk"
export PTT_APK_DIR="$dir"
failures=0

make_apk() { # <name> <content>
	printf '%s' "$2" > "$work/$1"
	sha256sum < "$work/$1" | cut -d' ' -f1
}

code_of() { IFS=. read -r a b c <<< "$1"; echo $((a * 1000000 + b * 1000 + c)); }

# publish <version> <content> [min code] [allow_older]; stdout/stderr go to $work/out
publish() {
	local v="$1" content="$2" min="${3:-0}" older="${4:-false}" sha code
	sha="$(make_apk "stage-$v" "$content")"
	code="$(code_of "$v")"
	[ "$code" -ge 1 ] || code=1
	"$here/publish-apk.sh" host "$work/stage-$v" "$v" "$code" "$sha" "${#content}" "$min" "$older" > "$work/out" 2>&1
}

check() { # <name> <expected rc: 0|1> <command...>
	local name="$1" want="$2" rc=0
	shift 2
	"$@" || rc=$?
	if [ "$rc" -eq "$want" ] || { [ "$want" = 1 ] && [ "$rc" -ne 0 ]; }; then
		echo "ok    $name"
	else
		echo "FAIL  $name (rc $rc)"
		sed 's/^/      /' "$work/out" 2>/dev/null || true
		failures=$((failures + 1))
	fi
}

names() { (cd "$dir" && ls -A | tr '\n' ' '); }
expect_names() { # <name> <expected listing>
	local got
	got="$(names)"
	if [ "$got" = "$2 " ]; then echo "ok    $1"; else echo "FAIL  $1: got '$got', want '$2'"; failures=$((failures + 1)); fi
}
latest() { jq -r ".$1" "$dir/latest.json"; }
expect_latest() { # <field> <value>
	local got
	got="$(latest "$1")"
	if [ "$got" = "$2" ]; then echo "ok    latest.json $1 = $2"; else echo "FAIL  latest.json $1: got '$got', want '$2'"; failures=$((failures + 1)); fi
}

mkdir "$dir"

check "fresh publish" 0 publish 0.5.0 apk-0.5.0 0
expect_names "fresh publish leaves the apk and latest.json only" "latest.json ptt-0.5.0.apk"
expect_latest version_name 0.5.0
expect_latest version_code 5000
expect_latest file ptt-0.5.0.apk
expect_latest size 9
expect_latest min_supported_version_code 0
expect_latest sha256 "$(sha256sum < "$work/stage-0.5.0" | cut -d' ' -f1)"
if jq -e '(.file == "ptt-" + .version_name + ".apk") and (.min_supported_version_code <= .version_code) and (.sha256 | test("^[0-9a-f]{64}$"))' "$dir/latest.json" > /dev/null; then
	echo "ok    latest.json satisfies pttd's checks"
else
	echo "FAIL  latest.json fails pttd's checks"
	failures=$((failures + 1))
fi
if [ "$(stat -c %a "$dir/latest.json" "$dir/ptt-0.5.0.apk" | tr '\n' ' ')" = "644 644 " ]; then echo "ok    files are 0644"; else echo "FAIL  modes"; failures=$((failures + 1)); fi

: > "$FAKE_LOG"
check "rerun with the same hash" 0 publish 0.5.0 apk-0.5.0 0
expect_names "rerun changes nothing in the directory" "latest.json ptt-0.5.0.apk"
if ! grep -q 'ptt-0.5.0.apk' "$FAKE_LOG"; then echo "ok    rerun did not reinstall the apk"; else echo "FAIL  rerun reinstalled the apk"; failures=$((failures + 1)); fi

check "same version, different hash is refused" 1 publish 0.5.0 other-bytes 0
grep -q 'different SHA-256' "$work/out" && echo "ok    ... for the right reason" || { echo "FAIL  reason"; failures=$((failures + 1)); }
expect_latest sha256 "$(printf apk-0.5.0 | sha256sum | cut -d" " -f1)"
printf 'apk-0.5.0' | cmp -s - "$dir/ptt-0.5.0.apk" && echo "ok    published bytes untouched" || { echo "FAIL  published bytes changed"; failures=$((failures + 1)); }

check "lower version is refused" 1 publish 0.4.0 apk-0.4.0 0
grep -q 'allow_older' "$work/out" && echo "ok    ... for the right reason" || { echo "FAIL  reason"; failures=$((failures + 1)); }
expect_names "refused lower version leaves the directory alone" "latest.json ptt-0.5.0.apk"

check "bad staged hash is refused" 1 "$here/publish-apk.sh" host "$work/stage-0.5.0" 0.6.0 6000 "$(printf 'a%.0s' $(seq 64))" "$(stat -c %s "$work/stage-0.5.0")" 0 false
expect_names "bad hash leaves the directory alone" "latest.json ptt-0.5.0.apk"
check "wrong version code is refused" 1 "$here/publish-apk.sh" host "$work/stage-0.5.0" 0.6.0 6001 "$(sha256sum < "$work/stage-0.5.0" | cut -d' ' -f1)" "$(stat -c %s "$work/stage-0.5.0")" 0 false
check "min code above the version code is refused" 1 publish 0.6.0 apk-0.6.0 7000

: > "$FAKE_LOG"
check "newer version with a min code" 0 publish 0.6.0 apk-0.6.0 5000
expect_latest min_supported_version_code 5000
apk_line="$(grep -n 'ptt-0.6.0.apk' "$FAKE_LOG" | tail -1 | cut -d: -f1)"
json_line="$(grep -n 'latest.json' "$FAKE_LOG" | tail -1 | cut -d: -f1)"
if [ -n "$apk_line" ] && [ -n "$json_line" ] && [ "$apk_line" -lt "$json_line" ]; then
	echo "ok    the apk is in place before latest.json is written"
else
	echo "FAIL  order: apk at $apk_line, latest.json at $json_line"
	failures=$((failures + 1))
fi

check "0.7.0" 0 publish 0.7.0 apk-0.7.0 0
check "0.8.0" 0 publish 0.8.0 apk-0.8.0 0
expect_names "newest three kept" "latest.json ptt-0.6.0.apk ptt-0.7.0.apk ptt-0.8.0.apk"

check "allow_older re-points latest at an older version" 0 publish 0.6.0 apk-0.6.0 0 true
expect_latest version_name 0.6.0
expect_names "allow_older keeps the three newest" "latest.json ptt-0.6.0.apk ptt-0.7.0.apk ptt-0.8.0.apk"

check "allow_older to a pruned version keeps latest and the newest two" 0 publish 0.5.0 apk-0.5.0 0 true
expect_latest version_name 0.5.0
expect_names "the latest.json version is kept beside the newest three" "latest.json ptt-0.5.0.apk ptt-0.6.0.apk ptt-0.7.0.apk ptt-0.8.0.apk"

check "double-digit versions sort numerically" 0 publish 0.10.0 apk-0.10.0 0 true
expect_names "0.10.0 outranks 0.8.0; 0.5.0 and 0.6.0 pruned" "latest.json ptt-0.10.0.apk ptt-0.7.0.apk ptt-0.8.0.apk"

rm "$dir/latest.json"
check "publish with no latest.json" 0 publish 0.9.0 apk-0.9.0 0
expect_latest version_name 0.9.0

echo '{broken' > "$dir/latest.json"
check "unreadable latest.json stops the publish" 1 publish 0.9.1 apk-0.9.1 0

if [ "$failures" -gt 0 ]; then
	echo "$failures failure(s)"
	exit 1
fi
echo "all publish-apk host tests passed"
