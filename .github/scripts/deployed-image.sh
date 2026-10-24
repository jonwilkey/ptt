#!/usr/bin/env bash
# Prints the image currently deployed on ptt-prod (PTTD_IMAGE in /opt/ptt/.env), or nothing if
# no release has been deployed yet. Needs `ssh ptt-prod` to work (ssh-setup.sh).
#
#   image="$(.github/scripts/deployed-image.sh)"
#
# Fails if SSH or sudo fails, so "nothing deployed" is never confused with "could not look".

set -euo pipefail

env_file="$(ssh ptt-prod 'if sudo -n test -e /opt/ptt/.env; then sudo -n cat /opt/ptt/.env; fi')"
sed -n 's/^PTTD_IMAGE=//p' <<< "$env_file" | tail -n 1
