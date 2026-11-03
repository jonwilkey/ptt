#!/usr/bin/env bash
# Refresh the Gradle lockfiles and verification metadata, exactly as documented in
# android/README.md ("Dependency locking and verification").
#
# Usage: scripts/refresh-locks.sh [--force]
#   Uses empty Gradle homes (each seeded with android/gradle/cold-home.properties as its
#   gradle.properties BEFORE any build, so memory is capped), an empty ANDROID_USER_HOME for the
#   strict confirmation, and deletes every temp home on exit (6-8 GB each) and kills Kotlin daemons.
#   Refuses to start when a Gradle daemon or build is already running, because two Gradle builds
#   at once can exhaust the machine; --force overrides that guard.
# Ends by printing `git status --short` and `git diff --stat` for the lock/verification files.
# Exit codes: 0 refreshed and confirmed, 1 a step failed, 2 usage error or Gradle already running.

set -euo pipefail

force=0
case "${1:-}" in
  "") ;;
  --force) force=1 ;;
  *) echo "usage: $0 [--force]" >&2; exit 2 ;;
esac
[ $# -le 1 ] || { echo "usage: $0 [--force]" >&2; exit 2; }

if [ "$force" = 0 ] && pgrep -f '[G]radleDaemon|[G]radleWrapperMain' >/dev/null; then
  echo "refusing to start: a Gradle daemon or build is running (stop it, or pass --force)" >&2
  exit 2
fi

top=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
cd "$top/android"

homes=()
cleanup() {
  pkill -f '[K]otlinCompileDaemon' || true
  if [ "${#homes[@]}" -gt 0 ]; then
    rm -rf "${homes[@]}"
  fi
}
trap cleanup EXIT

newgh() {
  local d
  d=$(mktemp -d)
  homes+=("$d")
  cp gradle/cold-home.properties "$d/gradle.properties"
  echo "$d"
}

GH=$(newgh)
FLAGS=(--gradle-user-home "$GH" --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks)
TASKS=(ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug assembleRelease)

echo "1/3 removing stale checksums and locks"
rm -f gradle/verification-metadata.xml buildSrc/gradle/verification-metadata.xml
find . -name '*.lockfile' -not -path '*/build/*' -delete

echo "2/3 writing checksums, then locks"
./gradlew "${FLAGS[@]}" --write-verification-metadata sha256 "${TASKS[@]}"
./gradlew "${FLAGS[@]}" "${TASKS[@]}" --write-locks
# buildSrc is a separate build with its own verification file: use ANOTHER empty Gradle home.
rm -f buildSrc/gradle/verification-metadata.xml buildSrc/gradle.lockfile
GH2=$(newgh)
./gradlew --gradle-user-home "$GH2" --no-daemon --no-configuration-cache -p buildSrc test \
  --rerun-tasks --write-locks --write-verification-metadata sha256

echo "3/3 confirming with a second empty Gradle home, strict, as CI runs it"
GH3=$(newgh)
ANDROID_USER_HOME=$(mktemp -d)
homes+=("$ANDROID_USER_HOME")
export ANDROID_USER_HOME
./gradlew --gradle-user-home "$GH3" --no-daemon -p buildSrc test
./gradlew --gradle-user-home "$GH3" --no-daemon ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug

echo "done; changes to lock/verification files:"
files=(gradle/verification-metadata.xml buildSrc/gradle/verification-metadata.xml)
while IFS= read -r f; do files+=("$f"); done < <(git ls-files -o -c --exclude-standard -- '*.lockfile' | grep -v /build/)
git status --short -- "${files[@]}"
git diff --stat -- "${files[@]}"
