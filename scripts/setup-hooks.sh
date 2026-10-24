#!/usr/bin/env bash
# Install the ptt git hooks for this clone. Run once per clone, and again whenever .githooks/
# changes (the hooks warn when the installed copy is out of date). Safe to re-run.
#
# The hooks are COPIED into the clone's shared hooks directory ($(git rev-parse
# --git-common-dir)/hooks), so every worktree gets them, including worktrees on branches that
# do not contain .githooks/. core.hooksPath is deliberately not used.
#
# It also creates the private personal-term denylist (outside the repo) if it is missing:
#   ${PTT_DENYLIST:-${XDG_CONFIG_HOME:-$HOME/.config}/ptt/denylist}
# An existing denylist is never modified. Terms match case-insensitively; non-ASCII letters
# fold only under a UTF-8 locale. An existing hook that is not ours is moved aside to a unique
# <name>.pre-ptt.<timestamp> backup (git no longer runs it).

set -eu

script_dir=$(cd -- "$(dirname -- "$0")" && pwd -P)
top=$(git -C "$script_dir" rev-parse --show-toplevel)
src="$top/.githooks"
common_dir=$(cd -- "$top" && cd -- "$(git rev-parse --git-common-dir)" && pwd -P)
hooks_dir="$common_dir/hooks"
denylist=${PTT_DENYLIST:-${XDG_CONFIG_HOME:-$HOME/.config}/ptt/denylist}
denylist_dir=$(dirname -- "$denylist")
problems=0

say() { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }

if [ ! -d "$src" ]; then
  printf 'error: %s not found\n' "$src" >&2
  exit 1
fi

# 1. Make sure git looks in the default hooks directory.
if git -C "$top" config --local --get core.hooksPath >/dev/null 2>&1; then
  git -C "$top" config --local --unset-all core.hooksPath
  say "Unset core.hooksPath in this repo's config."
fi
for scope in --global --system; do
  if hp=$(git config "$scope" --get core.hooksPath 2>/dev/null); then
    warn "core.hooksPath is set in $scope config ($hp), so git will not run the hooks"
    warn "installed in $hooks_dir. Remove it: git config $scope --unset core.hooksPath"
    problems=1
  fi
done

# 2. Copy the hooks.
mkdir -p "$hooks_dir"
for f in "$src"/*; do
  [ -f "$f" ] || continue
  name=${f##*/}
  dest="$hooks_dir/$name"
  if [ -e "$dest" ] && ! grep -q 'ptt-hooks' "$dest" 2>/dev/null; then
    base="$dest.pre-ptt.$(date +%Y%m%dT%H%M%S)"
    backup=$base
    n=1
    while [ -e "$backup" ]; do
      backup="$base.$n"
      n=$((n + 1))
    done
    mv "$dest" "$backup"
    say "Moved existing $name hook aside to $backup; git no longer runs it."
  fi
  cp "$f" "$dest.tmp.$$"
  chmod 0755 "$dest.tmp.$$"
  mv -f "$dest.tmp.$$" "$dest"
done
say "Installed hooks from $src into $hooks_dir"

# 3. Create the private denylist if it is missing.
if [ -e "$denylist" ]; then
  say "Keeping existing denylist $denylist"
else
  if [ ! -d "$denylist_dir" ]; then
    (umask 077 && mkdir -p "$denylist_dir")
    chmod 0700 "$denylist_dir"
  fi
  (
    umask 077
    cat >"$denylist" <<'EOF'
# ptt personal-term denylist. PRIVATE: it lives outside the repo and must never be committed.
#
# The ptt git hooks reject any commit or push whose added lines, file paths or commit
# messages contain one of these terms. One term per line; matching is case-insensitive and
# literal (no regex). Blank lines and lines starting with # are ignored. Non-ASCII letters
# match case-insensitively only under a UTF-8 locale.
#
# Add the things that must never appear in the public repo, for example:
#   family members' names and nicknames, personal email addresses,
#   the real domain and hostnames, cloud account IDs, home or server IP addresses.
EOF
  )
  chmod 0600 "$denylist"
  say "Created denylist template $denylist (mode 0600)"
fi

terms=0
if [ -r "$denylist" ]; then
  terms=$(grep -c -v -E '^[[:space:]]*(#|$)' "$denylist" || true)
fi
if [ "$terms" -eq 0 ]; then
  warn "the denylist has no terms yet, so every commit will be rejected until you add some."
  problems=1
else
  say "Denylist has $terms term(s)."
fi

# 4. gitleaks.
if gl=$(command -v gitleaks 2>/dev/null); then
  say "Found $("$gl" version 2>/dev/null | head -n 1 | sed 's/^/gitleaks /') at $gl"
else
  warn "gitleaks is not installed; the hooks fail closed without it. Install v8.30 or later:"
  warn "  brew install gitleaks"
  warn "  go install github.com/zricethezav/gitleaks/v8@v8.30.1"
  warn "  or a release binary from https://github.com/gitleaks/gitleaks/releases"
  problems=1
fi

# 5. Commit identity.
email=$(git -C "$top" config --get user.email 2>/dev/null || true)
lc_email=$(printf '%s' "$email" | tr '[:upper:]' '[:lower:]')
allowed="$denylist_dir/allowed-emails"
case $lc_email in
  ?*@users.noreply.github.com)
    say "user.email is a GitHub noreply address."
    ;;
  *)
    if [ -n "$lc_email" ] && [ -r "$allowed" ] &&
      sed 's/^[[:space:]]*//;s/[[:space:]]*$//' "$allowed" | tr '[:upper:]' '[:lower:]' |
      grep -q -x -F -e "$lc_email"; then
      say "user.email is listed in $allowed."
    else
      warn "user.email is '${email}', not a GitHub noreply address; commits will be rejected"
      warn "unless it is listed in $allowed. Set it for this repo with:"
      warn "  git config user.email <id>+<login>@users.noreply.github.com"
      problems=1
    fi
    ;;
esac

say ""
say "Next steps:"
say "  1. Put your private terms in $denylist (one per line)."
say "  2. Optionally list extra allowed commit emails in $denylist_dir/allowed-emails."
say "  3. Re-run scripts/setup-hooks.sh whenever .githooks/ changes."
say "  If a hook rejects a commit or push, fix the content; never use --no-verify."

if [ "$problems" -ne 0 ]; then
  exit 1
fi
