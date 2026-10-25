#!/usr/bin/env bash
# Self-test for the ptt git hooks. Builds a throwaway repo and bare remote in a temp directory,
# installs .githooks/ there with scripts/setup-hooks.sh and a synthetic denylist, and checks
# that the hooks accept clean commits and reject denylisted terms, secrets and personal emails.
# Needs git and gitleaks. It never touches this clone, your git config or your real denylist.

set -u

root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ptt-hook-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

pass=0
fail=0
out="$tmp/out"

# Isolate from the caller's git environment and config.
while IFS= read -r v; do
  unset "$v"
done < <(env | sed -n 's/^\(GIT_[A-Za-z_]*\)=.*/\1/p')
unset PTT_GITLEAKS
export HOME="$tmp/home" XDG_CONFIG_HOME="$tmp/home/.config" GIT_CONFIG_NOSYSTEM=1
mkdir -p "$HOME"
git config --global init.defaultBranch main
git config --global user.name "Test User"
git config --global user.email "12345+tester@users.noreply.github.com"
git config --global commit.gpgsign false
git config --global tag.gpgsign false

# Synthetic denylist; the real one is never read.
export PTT_DENYLIST="$tmp/cfg/denylist"
mkdir -p "$tmp/cfg"
cat >"$PTT_DENYLIST" <<'EOF'
# test denylist
Zorblax

secret-example-term
EOF

ok() {
  pass=$((pass + 1))
  printf 'PASS  %s\n' "$1"
}

ko() {
  fail=$((fail + 1))
  printf 'FAIL  %s\n' "$1"
  sed 's/^/      | /' "$out"
}

# expect_pass <name> <cmd...>: the command must succeed.
expect_pass() {
  local name=$1
  shift
  if "$@" >"$out" 2>&1; then ok "$name"; else ko "$name (expected success)"; fi
}

# expect_fail <name> <fixed-string expected in output> <cmd...>: the command must fail and
# print the expected text.
expect_fail() {
  local name=$1 want=$2
  shift 2
  if "$@" >"$out" 2>&1; then
    ko "$name (expected failure)"
  elif ! grep -q -F -e "$want" "$out"; then
    ko "$name (failed, but output lacks: $want)"
  else
    ok "$name"
  fi
}

# reset_index: drop whatever a rejected commit left staged.
reset_index() {
  git reset -q --hard
  git clean -q -fdx
}

repo="$tmp/repo"
remote="$tmp/remote.git"
git init -q --bare "$remote"
git init -q "$repo"
cp -R "$root/.githooks" "$repo/.githooks"
mkdir -p "$repo/scripts"
cp "$root/scripts/setup-hooks.sh" "$repo/scripts/"
cp "$root/.gitleaks.toml" "$repo/"
cd "$repo" || exit 1
git remote add origin "$remote"

expect_pass "setup-hooks.sh installs the hooks" scripts/setup-hooks.sh
expect_pass "setup-hooks.sh is idempotent" scripts/setup-hooks.sh
for h in pre-commit commit-msg pre-push lib.sh; do
  if [ -x ".git/hooks/$h" ] && cmp -s ".githooks/$h" ".git/hooks/$h"; then
    ok "installed $h"
  else
    printf 'missing or different\n' >"$out"
    ko "installed $h"
  fi
done

git add -A
expect_pass "clean commit passes" git commit -q -m "chore: add hooks"

echo "nothing to see" >clean.txt
git add clean.txt
expect_pass "second clean commit passes" git commit -q -m "docs: add a clean file"

expect_pass "push of clean commits passes" git push -q origin main

# --- pre-commit: denylist ---------------------------------------------------------------------
printf 'line one\nsay hi to zORBLAX here\n' >notes.txt
git add notes.txt
expect_fail "denylisted term in content is rejected" 'notes.txt:2: matched term "Zorblax"' \
  git commit -q -m "docs: notes"
reset_index

mkdir -p "dir with spaces"
echo "clean content" >"dir with spaces/my zorblax file.txt"
git add -A
expect_fail "denylisted term in a file name (with spaces) is rejected" \
  'dir with spaces/my zorblax file.txt (file path): matched term "Zorblax"' \
  git commit -q -m "docs: add file"
reset_index

echo "clean content" >renamed-src.txt
git add renamed-src.txt
git commit -q -m "docs: add file to rename" >/dev/null 2>&1
git mv renamed-src.txt "renamed-Zorblax.txt"
expect_fail "denylisted term in a rename target is rejected" \
  'renamed-Zorblax.txt (file path): matched term "Zorblax"' git commit -q -m "docs: rename"
reset_index

printf 'zorblax in an old line\n' >old.txt
git add old.txt
git commit -q --no-verify -m "docs: seed old line"
git rm -q old.txt
expect_pass "deleting lines and files that contain a term passes" git commit -q -m "docs: drop it"

printf 'PNG\000\001\002 zorblax \000binary' >blob.bin
git add blob.bin
expect_fail "binary file content is skipped with a note" "blob.bin: binary file, content not scanned" \
  sh -c 'git commit -q -m "chore: add blob" && false'
reset_index

# --- commit-msg -------------------------------------------------------------------------------
echo "more clean content" >>clean.txt
git add clean.txt
expect_fail "denylisted term in the commit message is rejected" \
  'commit message:3: matched term "secret-example-term"' \
  git commit -q -m "docs: tweak" -m "mentions SECRET-EXAMPLE-TERM"
expect_fail "denylisted term in a # line is only a warning at commit time" \
  'warning: commit message:3: matched term "Zorblax"' \
  sh -c 'git commit -q --cleanup=strip -m "docs: tweak" -m "# zorblax" && false'
echo "even more clean content" >>clean.txt
git add clean.txt
expect_pass "clean -m message passes" git commit -q -m "docs: tweak" -m "# a clean comment"

# Editor commits: git's template lists the staged paths as "#<TAB>deleted: <path>".
printf 'old\n' >"zorblax-notes.txt"
git add zorblax-notes.txt
git commit -q --no-verify -m "docs: seed badly named file"
git rm -q zorblax-notes.txt
cat >"$tmp/editor-ok" <<'SH'
#!/bin/sh
{ echo "docs: remove a badly named file"; cat "$1"; } >"$1.new" && mv "$1.new" "$1"
SH
cat >"$tmp/editor-bad" <<'SH'
#!/bin/sh
{ echo "docs: remove the zorblax file"; cat "$1"; } >"$1.new" && mv "$1.new" "$1"
SH
chmod +x "$tmp/editor-ok" "$tmp/editor-bad"
expect_fail "editor commit with a term on a non-comment line is rejected" \
  'commit message:1: matched term "Zorblax"' env GIT_EDITOR="$tmp/editor-bad" git commit -q
expect_fail "editor commit deleting a badly named file passes with a warning" \
  "appear only on '#' comment lines" \
  sh -c "GIT_EDITOR='$tmp/editor-ok' git commit -q && false"
if git log -1 --format=%B | grep -q -i zorblax; then
  git log -1 --format=%B >"$out"
  ko "git stripped the template lines from the editor commit"
else
  ok "git stripped the template lines from the editor commit"
fi

# --- pre-commit: gitleaks ---------------------------------------------------------------------
# Built at runtime so this file itself never contains something that looks like a key.
key_id="AKIA""QYLPMN5HHHFPZAM2"
key_secret="wJalrXUtnFEMI""/K7MDENG/bPxRfiCYzk3vX9pQ"
printf 'aws_access_key_id = %s\naws_secret_access_key = %s\n' "$key_id" "$key_secret" >creds.ini
git add creds.ini
expect_fail "fake AWS key is rejected by gitleaks" "gitleaks found a possible secret" \
  git commit -q -m "chore: add creds"
reset_index

# --- pre-commit: identity ---------------------------------------------------------------------
echo "identity" >>clean.txt
git add clean.txt
expect_fail "non-noreply author (user.email) is rejected" "is not a GitHub noreply address" \
  git -c user.email=someone@example.org commit -q -m "docs: identity"
expect_fail "non-noreply author (--author) is rejected" "commit author email <someone@example.org>" \
  git commit -q --author="Some One <someone@example.org>" -m "docs: identity"
printf '# allowed\nSomeone@Example.org\n' >"$tmp/cfg/allowed-emails"
expect_pass "author listed in allowed-emails passes" \
  git -c user.email=someone@example.org commit -q -m "docs: identity"
rm -f "$tmp/cfg/allowed-emails"
expect_fail "denylisted author name is rejected" 'commit author name: matched term "Zorblax"' \
  git commit -q --author="Ann Zorblax <1+ann@users.noreply.github.com>" -m "docs: identity"
expect_fail "denylisted committer name is rejected" \
  'commit committer name: matched term "Zorblax"' \
  env GIT_COMMITTER_NAME="Ann Zorblax" git commit -q -m "docs: identity"

# --- fail closed ------------------------------------------------------------------------------
echo "fail closed" >>clean.txt
git add clean.txt
expect_fail "missing denylist fails closed" "run scripts/setup-hooks.sh" \
  env PTT_DENYLIST="$tmp/cfg/nope" git commit -q -m "docs: x"
printf '# only comments\n\n' >"$tmp/cfg/empty"
expect_fail "denylist with no terms fails closed" "has no terms" \
  env PTT_DENYLIST="$tmp/cfg/empty" git commit -q -m "docs: x"
gitleaks_hidden=yes
for d in /usr/bin /bin /usr/local/bin /opt/homebrew/bin "$HOME/.local/bin" "$HOME/go/bin"; do
  [ -x "$d/gitleaks" ] && gitleaks_hidden=no
done
if [ "$gitleaks_hidden" = yes ]; then
  expect_fail "missing gitleaks fails closed" "gitleaks is not installed" \
    env PATH="/usr/bin:/bin" git commit -q -m "docs: x"
else
  printf 'SKIP  missing gitleaks fails closed (gitleaks is in a system directory)\n'
fi
reset_index

# --- drift warning ----------------------------------------------------------------------------
echo "# local edit" >>.githooks/pre-commit
echo "drift" >>clean.txt
git add clean.txt
expect_fail "outdated installed hooks produce a warning" "differ from" \
  sh -c 'git commit -q -m "docs: drift" && false'
git checkout -q -- .githooks/pre-commit
git commit -q --amend --no-edit >/dev/null 2>&1

# --- pre-push ---------------------------------------------------------------------------------
# Start from what the remote has (drops the --no-verify seed commit made above).
git fetch -q origin
git reset -q --hard origin/main
echo "sneaky zorblax" >sneaky.txt
git add sneaky.txt
git commit -q --no-verify -m "docs: sneaky"
expect_fail "pre-push catches a --no-verify denylisted term" \
  'sneaky.txt:1: matched term "Zorblax"' git push -q origin main
if git -C "$remote" rev-parse -q --verify "refs/heads/main" >/dev/null &&
  [ "$(git -C "$remote" rev-parse main)" = "$(git rev-parse HEAD)" ]; then
  echo "remote received the commit" >"$out"
  ko "rejected push did not reach the remote"
else
  ok "rejected push did not reach the remote"
fi
git reset -q --hard HEAD~1

git checkout -q -b feature
echo "feature work" >feature.txt
git add feature.txt
git commit -q --no-verify -m "feat: Zorblax feature"
expect_fail "pre-push checks commit messages on a new branch" \
  'message:1: matched term "Zorblax"' git push -q origin feature
git commit -q --amend --no-verify -m "feat: feature"
git -c user.email=someone@example.org commit -q --amend --no-verify --reset-author --no-edit
expect_fail "pre-push checks author email on a new branch" \
  "author email <someone@example.org>" git push -q origin feature
git commit -q --amend --no-verify --reset-author --no-edit
git -c user.name="Ann Zorblax" commit -q --amend --no-verify --reset-author --no-edit
expect_fail "pre-push checks author names" 'author name: matched term "Zorblax"' \
  git push -q origin feature
git commit -q --amend --no-verify --reset-author --no-edit
GIT_COMMITTER_NAME="Ann Zorblax" git commit -q --amend --no-verify --no-edit
expect_fail "pre-push checks committer names" 'committer name: matched term "Zorblax"' \
  git push -q origin feature
git commit -q --amend --no-verify --reset-author --no-edit

printf 'aws_access_key_id = %s\naws_secret_access_key = %s\n' "$key_id" "$key_secret" >creds.ini
git add creds.ini
git commit -q --no-verify -m "chore: creds"
expect_fail "pre-push catches a --no-verify secret" "gitleaks found a possible secret" \
  git push -q origin feature
git reset -q --hard HEAD~1

expect_pass "pre-push passes for a clean new branch" git push -q origin feature
expect_pass "pre-push passes for a branch delete" git push -q origin --delete feature

git checkout -q main
git checkout -q -b mergetest
echo "branch side" >side.txt
git add side.txt
git commit -q -m "docs: side" >/dev/null 2>&1
git checkout -q main
git merge -q --no-ff --no-verify --no-edit mergetest
echo "evil zorblax" >evil.txt
git add evil.txt
git commit -q --amend --no-verify --no-edit
expect_fail "pre-push catches a term added in a merge commit" \
  'evil.txt:1: matched term "Zorblax"' git push -q origin main

git reset -q --hard origin/main

# --- pre-push: merges, ref names, non-commit refs, remote scoping ------------------------------
git checkout -q -b secretmerge
echo "side two" >side2.txt
git add side2.txt
git commit -q -m "docs: side two" >/dev/null 2>&1
git checkout -q main
git merge -q --no-ff --no-verify --no-edit secretmerge
printf 'aws_access_key_id = %s\naws_secret_access_key = %s\n' "$key_id" "$key_secret" >creds.ini
git add creds.ini
git commit -q --amend --no-verify --no-edit
expect_fail "pre-push gitleaks scans content added in a merge commit" \
  "gitleaks found a possible secret" git push -q origin main
git reset -q --hard origin/main

expect_fail "pre-push rejects a non-delete push to a badly named remote ref" \
  'remote ref name: matched term "Zorblax"' git push -q origin "HEAD:refs/heads/zorblax-branch"
git branch zorblax-local
expect_fail "pre-push rejects pushing a badly named local branch" \
  'local ref name: matched term "Zorblax"' git push -q origin "zorblax-local:refs/heads/fine"
git branch -D zorblax-local >/dev/null

git commit -q --allow-empty --cleanup=verbatim -m "docs: kept comment" -m "# zorblax" \
  >/dev/null 2>&1
expect_fail "pre-push catches a # line that git kept in a stored message" \
  'message:3: matched term "Zorblax"' git push -q origin main
git reset -q --hard origin/main
git -C "$remote" update-ref refs/heads/old-zorblax "$(git rev-parse origin/main)"
expect_pass "pre-push allows deleting a badly named remote ref" \
  git push -q origin --delete old-zorblax

git tag treetag "HEAD^{tree}"
expect_fail "pre-push rejects a ref that points at a tree" "does not point at a commit" \
  git push -q origin refs/tags/treetag
git tag -d treetag >/dev/null

mirror="$tmp/mirror.git"
other="$tmp/other.git"
git init -q --bare "$mirror"
git init -q --bare "$other"
git remote add mirror "$mirror"
git checkout -q -b mirrored
echo "mirror zorblax" >mirror.txt
git add mirror.txt
git commit -q --no-verify -m "docs: mirror"
git push -q --no-verify mirror mirrored
echo "on top" >top.txt
git add top.txt
git commit -q -m "docs: on top" >/dev/null 2>&1
expect_fail "pre-push does not trust commits that are only on another remote" \
  'mirror.txt:1: matched term "Zorblax"' git push -q origin mirrored
expect_fail "pre-push to a URL checks everything reachable" \
  'mirror.txt:1: matched term "Zorblax"' git push -q "$other" mirrored
git checkout -q main

# A rebase onto main re-pushes main's commits on an existing branch. Those already on the
# remote (here a squash merge GitHub made under a denylisted name) are skipped; new ones are not.
git checkout -q -b rebased
echo "rebased work" >rebased.txt
git add rebased.txt
git commit -q -m "docs: rebased work" >/dev/null 2>&1
git push -q origin rebased >/dev/null 2>&1
git checkout -q main
git -c user.name="Ann Zorblax" commit -q --allow-empty --no-verify -m "docs: merged on GitHub"
git push -q --no-verify origin main
git checkout -q rebased
git rebase -q main
expect_pass "pre-push skips commits already on the remote when updating a branch" \
  git push -q --force origin rebased
echo "late zorblax" >late.txt
git add late.txt
git commit -q --no-verify -m "docs: late"
expect_fail "pre-push still checks new commits when updating a branch" \
  'late.txt:1: matched term "Zorblax"' git push -q origin rebased
git reset -q --hard HEAD~1
git checkout -q main

# --- setup-hooks.sh: existing hooks are backed up, never overwritten ---------------------------
repo2="$tmp/repo2"
git init -q "$repo2"
cp -R "$root/.githooks" "$repo2/.githooks"
mkdir -p "$repo2/scripts"
cp "$root/scripts/setup-hooks.sh" "$repo2/scripts/"
printf '#!/bin/sh\n# custom one\n' >"$repo2/.git/hooks/pre-commit"
expect_fail "setup-hooks.sh moves a foreign hook aside" "git no longer runs it" \
  sh -c "cd '$repo2' && scripts/setup-hooks.sh && false"
printf '#!/bin/sh\n# custom two\n' >"$repo2/.git/hooks/pre-commit"
(cd "$repo2" && scripts/setup-hooks.sh >/dev/null 2>&1)
printf '#!/bin/sh\n# custom three\n' >"$repo2/.git/hooks/pre-commit"
(cd "$repo2" && scripts/setup-hooks.sh >/dev/null 2>&1)
backups=$(find "$repo2/.git/hooks" -name 'pre-commit.pre-ptt.*' | wc -l | tr -d ' ')
if [ "$backups" -eq 3 ] &&
  cat "$repo2"/.git/hooks/pre-commit.pre-ptt.* | grep -q 'custom one' &&
  cat "$repo2"/.git/hooks/pre-commit.pre-ptt.* | grep -q 'custom two' &&
  cat "$repo2"/.git/hooks/pre-commit.pre-ptt.* | grep -q 'custom three'; then
  ok "setup-hooks.sh keeps every earlier backup"
else
  ls -la "$repo2/.git/hooks" >"$out"
  ko "setup-hooks.sh keeps every earlier backup"
fi

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
