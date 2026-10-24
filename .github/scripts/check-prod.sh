#!/usr/bin/env bash
# Checks production from the outside after a deploy or a host change (spec, "Server deploys"
# step 5): waits up to 2 minutes for https://<domain>/healthz to return 200, then runs
# deploy/smoke.sh trusting only ISRG Root X1 and X2, as the app does.
#
#   PTT_DOMAIN=<domain> .github/scripts/check-prod.sh
#
# Run from the repository root. The domain comes from a secret, so it is masked in the log.
# TODO(milestone 2): page, answer, and relay one audio frame with the two test devices.

set -euo pipefail

: "${PTT_DOMAIN:?PTT_DOMAIN must be set}"
url="https://${PTT_DOMAIN}"

deadline=$((SECONDS + ${HEALTH_TIMEOUT:-120}))
code=""
until code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "${url}/healthz" 2> /dev/null)" &&
	[ "$code" = 200 ]; do
	if [ "$SECONDS" -ge "$deadline" ]; then
		echo "::error::/healthz did not return 200 within ${HEALTH_TIMEOUT:-120} s (last: ${code:-no response})."
		exit 1
	fi
	sleep 5
done
echo "/healthz is 200."

# The runner's CA store (Ubuntu ca-certificates) has both ISRG roots.
roots="$(mktemp)"
trap 'rm -f "$roots"' EXIT
cat /usr/share/ca-certificates/mozilla/ISRG_Root_X1.crt \
	/usr/share/ca-certificates/mozilla/ISRG_Root_X2.crt > "$roots"
deploy/smoke.sh "$url" --cacert "$roots"
