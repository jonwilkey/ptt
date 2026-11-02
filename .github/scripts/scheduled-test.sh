#!/usr/bin/env bash
# Tests for the scripts behind weekly-maintenance and nightly-security: the DST gate, the
# version-label parsing, the base-digest refresh (fake docker), the Healthchecks ping (fake curl;
# the key must never be printed), and the issue open/close/reopen decisions (fake gh).
# Runs on any machine with bash, jq and python3; nothing touches the network.

set -euo pipefail
cd "$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fails=0

check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "ok    $1"; else
    echo "FAIL  $1: expected '$2', got '$3'"
    fails=$((fails + 1))
  fi
}

# --- maintenance-gate.sh: 03:00 Los Angeles across both DST changes (2026: Mar 8, Nov 1) ---
# env -u GITHUB_OUTPUT: in Actions the script writes there instead of stdout.
gate() { env -u GITHUB_OUTPUT EVENT_NAME=schedule SCHEDULE="$1" MAINT_NOW="$2" ./maintenance-gate.sh 2> /dev/null | grep '^run='; }
check "gate: Mon 2026-03-02 (PST) 11:00 fires"      run=true  "$(gate '0 11 * * 1' '2026-03-02 11:02 UTC')"
check "gate: Mon 2026-03-02 (PST) 10:00 skips"      run=false "$(gate '0 10 * * 1' '2026-03-02 10:03 UTC')"
check "gate: Mon 2026-03-09 (PDT) 10:00 fires"      run=true  "$(gate '0 10 * * 1' '2026-03-09 10:05 UTC')"
check "gate: Mon 2026-03-09 (PDT) 11:00 skips"      run=false "$(gate '0 11 * * 1' '2026-03-09 11:01 UTC')"
check "gate: Mon 2026-10-26 (PDT) 10:00 fires"      run=true  "$(gate '0 10 * * 1' '2026-10-26 10:30 UTC')"
check "gate: Mon 2026-10-26 (PDT) 11:00 skips"      run=false "$(gate '0 11 * * 1' '2026-10-26 11:30 UTC')"
check "gate: Mon 2026-11-02 (PST) 11:00 fires"      run=true  "$(gate '0 11 * * 1' '2026-11-02 11:10 UTC')"
check "gate: Mon 2026-11-02 (PST) 10:00 skips"      run=false "$(gate '0 10 * * 1' '2026-11-02 10:10 UTC')"
check "gate: Mon 2027-03-15 (PDT) 10:00 fires"      run=true  "$(gate '0 10 * * 1' '2027-03-15 10:00 UTC')"
check "gate: Mon 2027-11-08 (PST) 11:00 fires"      run=true  "$(gate '0 11 * * 1' '2027-11-08 11:00 UTC')"
check "gate: manual run always proceeds" run=true "$(env -u GITHUB_OUTPUT EVENT_NAME=workflow_dispatch ./maintenance-gate.sh | grep '^run=')"
if EVENT_NAME=schedule SCHEDULE='0 12 * * 1' ./maintenance-gate.sh > /dev/null 2>&1; then
  check "gate: unknown schedule fails" fail ran
else check "gate: unknown schedule fails" fail fail; fi
if EVENT_NAME=push ./maintenance-gate.sh > /dev/null 2>&1; then
  check "gate: other events fail" fail ran
else check "gate: other events fail" fail fail; fi

# --- deployed-release.sh ---
check "label: plain release"  "version=1.2.3 tag=server-v1.2.3"         "$(./deployed-release.sh 1.2.3 | xargs)"
check "label: +maint dropped" "version=1.2.3 tag=server-v1.2.3"         "$(./deployed-release.sh 1.2.3+maint.20261102 | xargs)"
check "label: prerelease kept" "version=1.2.3-rc.1 tag=server-v1.2.3-rc.1" "$(./deployed-release.sh 1.2.3-rc.1+maint.20261109 | xargs)"
for bad in "" dev "1.2" "v1.2.3" "1.2.3+" "1.2.3 +x" "1.2.3+drill.5" "1.2.3+maint" "1.2.3+maint.2026110" "1.2.3+maint.202611021" "1.2.3+maint.20261102+x"; do
  if ./deployed-release.sh "$bad" > /dev/null 2>&1; then check "label: '$bad' rejected" rejected accepted
  else check "label: '$bad' rejected" rejected rejected; fi
done

# --- check-expected-previous.sh: weekly must not overwrite a production that changed under it ---
d1=sha256:$(printf '1%.0s' {1..64}) d2=sha256:$(printf '2%.0s' {1..64})
exp() { ./check-expected-previous.sh "$@" > /dev/null 2>&1 && echo ok || echo changed; }
check "expect: unset allows anything"        ok      "$(exp '' "ghcr.io/o/r/pttd@$d1")"
check "expect: unset allows first deploy"    ok      "$(exp '' '')"
check "expect: same digest"                  ok      "$(exp "ghcr.io/o/r/pttd@$d1" "ghcr.io/o/r/pttd@$d1")"
check "expect: same digest, other spelling"  ok      "$(exp "ghcr.io/o/r/pttd@$d1" "GHCR.io/o/r/pttd@$d1")"
check "expect: different digest fails"       changed "$(exp "ghcr.io/o/r/pttd@$d1" "ghcr.io/o/r/pttd@$d2")"
check "expect: nothing deployed fails"       changed "$(exp "ghcr.io/o/r/pttd@$d1" '')"
check "expect: tag-only actual fails"        changed "$(exp "ghcr.io/o/r/pttd@$d1" 'ghcr.io/o/r/pttd:1.2.3')"
check "expect: malformed expectation fails"  changed "$(exp 'ghcr.io/o/r/pttd:deployed' "ghcr.io/o/r/pttd@$d1")"
check "expect: message names the problem" 1 "$(./check-expected-previous.sh "ghcr.io/o/r/pttd@$d1" "ghcr.io/o/r/pttd@$d2" 2>&1 | grep -c 'production changed since this run resolved it' || true)"

# --- refresh-base-digests.sh with a fake docker ---
old1=sha256:$(printf 'a%.0s' {1..64}) new1=sha256:$(printf 'b%.0s' {1..64}) same=sha256:$(printf 'c%.0s' {1..64})
mkdir -p "$tmp/bin"
cat > "$tmp/bin/docker" <<FAKE
#!/usr/bin/env bash
# docker buildx imagetools inspect <ref> --format ...
case "\$4" in
  golang:1.27.3-trixie) echo '{"digest":"$new1"}' ;;
  gcr.io/distroless/static-debian13:nonroot) echo '{"digest":"$same"}' ;;
  *) echo '{"digest":"garbage"}' ;;
esac
FAKE
chmod +x "$tmp/bin/docker"
cat > "$tmp/Dockerfile" <<DF
# syntax=docker/dockerfile:1
ARG VERSION=dev
FROM golang:1.27.3-trixie@$old1 AS build
RUN echo golang:1.27.3-trixie@$old1
FROM gcr.io/distroless/static-debian13:nonroot@$same
COPY --from=build /x /x
DF
out="$(PATH="$tmp/bin:$PATH" ./refresh-base-digests.sh "$tmp/Dockerfile")"
check "refresh: golang digest moved"   1 "$(grep -c "^FROM golang:1.27.3-trixie@$new1 AS build" "$tmp/Dockerfile")"
check "refresh: only FROM-pinned text replaced elsewhere too" 0 "$(grep -c "$old1" "$tmp/Dockerfile")"
check "refresh: distroless unchanged"  1 "$(grep -c "^FROM gcr.io/distroless/static-debian13:nonroot@$same\$" "$tmp/Dockerfile")"
check "refresh: other lines untouched" 1 "$(grep -c '^COPY --from=build /x /x$' "$tmp/Dockerfile")"
check "refresh: report" "refreshed unchanged" "$(awk '{print $1}' <<< "$out" | xargs)"
cp "$tmp/Dockerfile" "$tmp/Dockerfile.before"
printf 'FROM alpine:3@sha256:%s\n' "$old1" > "$tmp/bad"
if PATH="$tmp/bin:$PATH" ./refresh-base-digests.sh "$tmp/bad" > /dev/null 2>&1; then
  check "refresh: unresolvable digest fails" fail ran
else check "refresh: unresolvable digest fails" fail fail; fi
check "refresh: failed run leaves the file alone" 1 "$(grep -c "$old1" "$tmp/bad")"
printf 'FROM scratch\n' > "$tmp/none"
if PATH="$tmp/bin:$PATH" ./refresh-base-digests.sh "$tmp/none" > /dev/null 2>&1; then
  check "refresh: no pinned FROM fails" fail ran
else check "refresh: no pinned FROM fails" fail fail; fi

# --- hc-ping.sh with a fake curl: the URL goes in on stdin and the key must never be printed ---
cat > "$tmp/bin/curl" <<'FAKE'
#!/usr/bin/env bash
cat > "$CURL_LOG.stdin"
printf '%s\n' "$*" > "$CURL_LOG.args"
[ -z "${CURL_FAIL:-}" ]
FAKE
chmod +x "$tmp/bin/curl"
export CURL_LOG="$tmp/curl"
ping_out="$(PATH="$tmp/bin:$PATH" HC_PING_KEY=SECRETKEY123 ./hc-ping.sh ptt-weekly ok "run ok" 2>&1)"
check "ping: ok URL" 'url = "https://hc-ping.com/SECRETKEY123/ptt-weekly"' "$(cat "$tmp/curl.stdin")"
check "ping: output never has the key" 0 "$(grep -c SECRETKEY123 <<< "$ping_out" || true)"
check "ping: key not in the arguments" 0 "$(grep -c SECRETKEY123 "$tmp/curl.args" || true)"
PATH="$tmp/bin:$PATH" HC_PING_KEY=SECRETKEY123 ./hc-ping.sh ptt-security fail "boom" > /dev/null 2>&1
check "ping: fail URL" 'url = "https://hc-ping.com/SECRETKEY123/ptt-security/fail"' "$(cat "$tmp/curl.stdin")"
ping_out="$(CURL_FAIL=1 PATH="$tmp/bin:$PATH" HC_PING_KEY=SECRETKEY123 ./hc-ping.sh ptt-weekly fail "x" 2>&1)"
check "ping: failure warns, no key"  0 "$(grep -c SECRETKEY123 <<< "$ping_out" || true)"
check "ping: failure warns" 1 "$(grep -c '::warning::' <<< "$ping_out")"
check "ping: no key skips" 1 "$(env -u HC_PING_KEY PATH="$tmp/bin:$PATH" ./hc-ping.sh ptt-weekly ok 2>&1 | grep -c 'is not set')"
if HC_PING_KEY=k ./hc-ping.sh 'bad/slug' ok > /dev/null 2>&1; then check "ping: bad slug" rejected accepted
else check "ping: bad slug" rejected rejected; fi

# --- nightly-security.py ---
if python3 -I nightly-security-test.py > "$tmp/py.out" 2>&1; then
  echo "ok    nightly-security.py ($(tail -n 1 "$tmp/py.out"))"
else
  cat "$tmp/py.out"
  fails=$((fails + 1))
fi

if [ "$fails" -ne 0 ]; then
  echo "$fails check(s) failed."
  exit 1
fi
echo "All scheduled-workflow script tests passed."
