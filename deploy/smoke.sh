#!/usr/bin/env bash
# Smoke test for a running ptt deployment, through Caddy, from the outside.
#
#   deploy/smoke.sh <base_url> [options]
#
#   <base_url>                 https://<host>[:port], e.g. https://ptt.example.com
#   --resolve <host:port:addr> Connect to addr instead of resolving host (curl --resolve), e.g.
#                              ptt.example.com:443:127.0.0.1 or ptt.example.com:443:[::1].
#                              The same address is used for the plain-HTTP check.
#   --cacert <file>            Trust only the CAs in this file: Caddy's local CA under
#                              `tls internal`, or, for production, a bundle of ISRG Root X1
#                              and X2 (https://letsencrypt.org/certificates/), which matches
#                              what the app trusts. Default: the system trust store.
#   --insecure                 Skip certificate verification (last resort for local runs)
#   --http-port <port>         Port of the plain-HTTP listener (default 80)
#
# Checks: /healthz is 200 {"status":"ok"} over HTTPS with HSTS; TLS 1.3 works and TLS 1.2 is
# refused; unknown paths are a JSON 404; /metrics and /internal/status are not reachable;
# http:// redirects to https://. Exits non-zero if any check fails, naming each failure.
#
# Needs bash 4.4+, curl, and jq. Used by CI against a local compose stack and after production
# deploys, so it assumes nothing about where the server runs.

set -euo pipefail

usage() {
	sed -n '2,/^$/{s/^# \{0,1\}//;p}' "$0" >&2
	exit 2
}

base_url=""
resolve=""
http_port=80
tls_opts=()

while [ $# -gt 0 ]; do
	case "$1" in
	--resolve)
		[ $# -ge 2 ] || usage
		resolve="$2"
		shift 2
		;;
	--cacert)
		[ $# -ge 2 ] || usage
		tls_opts+=(--cacert "$2")
		shift 2
		;;
	--insecure | -k)
		tls_opts+=(--insecure)
		shift
		;;
	--http-port)
		[ $# -ge 2 ] || usage
		http_port="$2"
		shift 2
		;;
	-h | --help) usage ;;
	-*)
		echo "smoke: unknown option: $1" >&2
		usage
		;;
	*)
		[ -z "$base_url" ] || usage
		base_url="$1"
		shift
		;;
	esac
done

[ -n "$base_url" ] || usage
for tool in curl jq; do
	command -v "$tool" > /dev/null || {
		echo "smoke: $tool is required" >&2
		exit 2
	}
done

base_url="${base_url%/}"
if [[ ! "$base_url" =~ ^https://([^/:]+|\[[0-9a-fA-F:]+\])(:([0-9]+))?$ ]]; then
	echo "smoke: base_url must be https://<host>[:port] with no path, got '$base_url'" >&2
	exit 2
fi
host="${BASH_REMATCH[1]}"
# An explicit :443 is dropped so base_url matches the redirect target Caddy sends.
[ "${BASH_REMATCH[3]}" != 443 ] || base_url="https://$host"

curl_base=(--silent --show-error --connect-timeout 5 --max-time 15 --proto '=https,http')
http_resolve=()
if [ -n "$resolve" ]; then
	# host:port:addr; addr may be a bracketed IPv6 address containing colons.
	if [[ ! "$resolve" =~ ^([^:]+):([0-9]+):(.+)$ ]]; then
		echo "smoke: --resolve must be host:port:addr, got '$resolve'" >&2
		exit 2
	fi
	if [ "${BASH_REMATCH[1]}" != "$host" ]; then
		echo "smoke: --resolve host '${BASH_REMATCH[1]}' does not match the base_url host '$host'" >&2
		exit 2
	fi
	curl_base+=(--resolve "$resolve")
	http_resolve=(--resolve "${BASH_REMATCH[1]}:${http_port}:${BASH_REMATCH[3]}")
fi
https=("${curl_base[@]}" "${tls_opts[@]}")

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
	printf 'FAIL  %s\n' "$1" >&2
	failures=$((failures + 1))
}

# request <name> <curl args...>: runs curl, leaving the body in $tmp/<name>.body and the headers
# in $tmp/<name>.headers; prints the status code ("000" if the request itself failed).
request() {
	local name="$1"
	shift
	local code
	code="$(curl "$@" --output "$tmp/$name.body" --dump-header "$tmp/$name.headers" \
		--write-out '%{http_code}' 2> "$tmp/$name.err")" || true
	echo "${code:-000}"
}

# header <name> <header>: the value of a response header (case-insensitive), or empty.
header() {
	awk -v h="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" -F': *' '
		{ sub(/\r$/, "") }
		tolower($1) == h { sub(/^[^:]*: */, ""); print; exit }
	' "$tmp/$1.headers" 2> /dev/null || true
}

curl_error() { tr -d '\r' < "$tmp/$1.err" | tail -n 1; }

echo "smoke: $base_url${resolve:+ (resolve $resolve)}"

# 1. Health over HTTPS (TLS 1.3).
code="$(request health "${https[@]}" --tlsv1.3 "$base_url/healthz")"
if [ "$code" != 200 ]; then
	fail "GET /healthz: want 200, got $code $(curl_error health)"
elif [ "$(jq -r '.status? // empty' "$tmp/health.body" 2> /dev/null)" != ok ]; then
	fail "GET /healthz: want {\"status\":\"ok\"}, got $(head -c 200 "$tmp/health.body")"
else
	pass "GET /healthz is 200 {\"status\":\"ok\"} over TLS 1.3"
fi

# 2. HSTS.
hsts="$(header health strict-transport-security)"
if [[ "$hsts" =~ max-age=([0-9]+) ]] && [ "${BASH_REMATCH[1]}" -ge 31536000 ]; then
	pass "HSTS: $hsts"
else
	fail "HSTS: want Strict-Transport-Security with max-age >= 31536000, got '${hsts}'"
fi

# 3. TLS 1.2 and older are refused. Only a failed handshake (curl exit 35) counts as refused;
# any other failure (not resolving, not connecting, timing out, ...) proves nothing.
rc=0
curl "${https[@]}" --tlsv1 --tls-max 1.2 --output /dev/null "$base_url/healthz" 2> "$tmp/tls12.err" || rc=$?
case "$rc" in
0) fail "TLS 1.2: handshake succeeded, want it refused" ;;
35) pass "TLS 1.2 refused ($(curl_error tls12))" ;;
*) fail "TLS 1.2: could not test, curl exit $rc $(curl_error tls12)" ;;
esac

# 4. Unknown paths are a JSON 404, and 5. the internal endpoints are not exposed.
for path in /smoke-test-no-such-path /metrics /internal/status; do
	name="path$(printf '%s' "$path" | tr -c 'a-zA-Z0-9' '_')"
	code="$(request "$name" "${https[@]}" "$base_url$path")"
	ctype="$(header "$name" content-type)"
	if [ "$code" != 404 ]; then
		fail "GET $path: want 404, got $code $(curl_error "$name")"
	elif [[ "$ctype" != application/json* ]] ||
		[ "$(jq -r '.error? // empty' "$tmp/$name.body" 2> /dev/null)" != not_found ]; then
		fail "GET $path: want a JSON {\"error\":\"not_found\"}, got '$ctype' $(head -c 200 "$tmp/$name.body")"
	else
		pass "GET $path is a JSON 404"
	fi
done

# 6. Plain HTTP redirects to HTTPS.
if [ "$http_port" = 80 ]; then
	http_url="http://$host"
else
	http_url="http://$host:$http_port"
fi
code="$(request redirect "${curl_base[@]}" "${http_resolve[@]}" "$http_url/healthz")"
location="$(header redirect location)"
# base_url carries the HTTPS port when it is not 443, so the target must match it exactly.
if [[ "$code" =~ ^30[178]$ ]] && [[ "$location" == "$base_url/"* ]]; then
	pass "$http_url redirects ($code) to $location"
else
	fail "$http_url/healthz: want a redirect to $base_url/, got $code location='$location' $(curl_error redirect)"
fi

if [ "$failures" -gt 0 ]; then
	echo "smoke: $failures check(s) failed" >&2
	exit 1
fi
echo "smoke: all checks passed"
