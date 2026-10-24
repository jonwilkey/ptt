# shellcheck shell=bash
# ptt-hooks: shared library for the ptt git hooks.
#
# Sourced by the hooks in this directory. scripts/setup-hooks.sh copies this file next to the
# hooks in the clone's shared hooks directory, so it must stay self-contained. Written for
# bash 3.2+ (the macOS default) and GNU or BSD userland.

PTT_NOREPLY_SUFFIX='@users.noreply.github.com'
PTT_WEB_COMMITTER='noreply@github.com'

ptt_err() { printf '%s\n' "$*" >&2; }

ptt_lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# ---------------------------------------------------------------------------------------------
# Scratch space
# ---------------------------------------------------------------------------------------------

# ptt_init: create the scratch directory and the scan buffers. The scan buffers are two files
# kept line-aligned: line N of "content" is text to scan, line N of "loc" says where it came from.
ptt_init() {
  PTT_TMP=$(mktemp -d "${TMPDIR:-/tmp}/ptt-hook.XXXXXX") || {
    ptt_err "ptt hooks: error: cannot create a temporary directory"
    exit 1
  }
  trap 'rm -rf "$PTT_TMP"' EXIT
  : >"$PTT_TMP/content"
  : >"$PTT_TMP/loc"
  : >"$PTT_TMP/notes"
}

# ptt_add_text <location> <text>: queue one line of text for the denylist scan.
ptt_add_text() {
  local text=$2
  text=${text//$'\n'/ }
  printf '%s\n' "$text" >>"$PTT_TMP/content"
  printf '%s\n' "$1" >>"$PTT_TMP/loc"
}

ptt_note() { printf '%s\n' "$*" >>"$PTT_TMP/notes"; }

ptt_print_notes() {
  if [ -s "$PTT_TMP/notes" ]; then
    cat "$PTT_TMP/notes" >&2
  fi
}

# ---------------------------------------------------------------------------------------------
# Hook freshness
# ---------------------------------------------------------------------------------------------

# ptt_check_drift: warn (never fail) when the installed hooks differ from .githooks/ in the
# current worktree. PTT_HOOK_DIR must be the directory the running hook lives in.
ptt_check_drift() {
  local top src here f name
  top=$(git rev-parse --show-toplevel 2>/dev/null) || return 0
  src="$top/.githooks"
  [ -d "$src" ] || return 0
  here=$(cd "$PTT_HOOK_DIR" && pwd -P) || return 0
  [ "$(cd "$src" && pwd -P)" = "$here" ] && return 0
  for f in "$src"/*; do
    [ -f "$f" ] || continue
    name=${f##*/}
    if ! cmp -s "$f" "$here/$name"; then
      ptt_err "ptt hooks: warning: installed hooks in $here differ from this worktree's"
      ptt_err "ptt hooks: warning: .githooks/ ($src)."
      ptt_err "ptt hooks: warning: re-run scripts/setup-hooks.sh to update them."
      return 0
    fi
  done
  return 0
}

# ---------------------------------------------------------------------------------------------
# Denylist
# ---------------------------------------------------------------------------------------------

ptt_denylist_path() {
  printf '%s\n' "${PTT_DENYLIST:-${XDG_CONFIG_HOME:-$HOME/.config}/ptt/denylist}"
}

ptt_allowed_emails_path() {
  local d
  d=$(ptt_denylist_path)
  printf '%s/allowed-emails\n' "$(dirname -- "$d")"
}

# ptt_read_list <file>: print the entries of a one-per-line list file, with surrounding
# whitespace and CRs trimmed and blank lines and lines starting with # dropped.
ptt_read_list() {
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    line=${line#"${line%%[![:space:]]*}"}
    line=${line%"${line##*[![:space:]]}"}
    [ -n "$line" ] || continue
    case $line in '#'*) continue ;; esac
    printf '%s\n' "$line"
  done <"$1"
}

# ptt_load_denylist: load the terms into $PTT_TMP/terms. Fails closed: exits the hook when the
# denylist is missing, unreadable or empty.
ptt_load_denylist() {
  local f
  f=$(ptt_denylist_path)
  if [ ! -f "$f" ] || [ ! -r "$f" ]; then
    ptt_err "ptt hooks: error: personal-term denylist not found: $f"
    ptt_err "ptt hooks: error: run scripts/setup-hooks.sh, then add your private terms to it."
    exit 1
  fi
  ptt_read_list "$f" >"$PTT_TMP/terms"
  if [ ! -s "$PTT_TMP/terms" ]; then
    ptt_err "ptt hooks: error: personal-term denylist has no terms: $f"
    ptt_err "ptt hooks: error: run scripts/setup-hooks.sh, then add your private terms to it"
    ptt_err "ptt hooks: error: (one per line: names, personal emails, the real domain, IDs, IPs)."
    exit 1
  fi
}

# ptt_collect_diff <label-prefix> <git-diff-args...>: queue the paths and the added lines of a
# diff (for example "--cached", or "<parent> <commit>") for the denylist scan. Deleted files are
# ignored; renames are treated as an add of the new path. Binary files have their path scanned
# and their content skipped with a note.
ptt_collect_diff() {
  local prefix=$1 rec added path disp
  shift
  if ! git diff --numstat -z --no-renames --no-ext-diff --no-textconv --diff-filter=d "$@" \
    >"$PTT_TMP/numstat"; then
    ptt_err "ptt hooks: error: git diff failed for ${prefix:-the staged changes}; refusing to continue"
    exit 1
  fi
  while IFS= read -r -d '' rec; do
    added=${rec%%$'\t'*}
    path=${rec#*$'\t'}
    path=${path#*$'\t'}
    disp=${path//$'\n'/?}
    ptt_add_text "$prefix$disp (file path)" "$path"
    if [ "$added" = "-" ]; then
      ptt_note "ptt hooks: note: $prefix$disp: binary file, content not scanned for denylisted terms"
      continue
    fi
    [ "$added" = "0" ] && continue
    git -c core.quotePath=false diff -U0 --no-color --no-ext-diff --no-textconv --no-renames \
      "$@" -- ":(literal)$path" |
      PTT_LOC="$prefix$disp" awk -v cf="$PTT_TMP/content" -v lf="$PTT_TMP/loc" '
        BEGIN { loc = ENVIRON["PTT_LOC"]; inhunk = 0; n = 0 }
        /^diff --git / { inhunk = 0; next }
        /^@@ / {
          s = $0
          sub(/^@@ -[0-9,]+ \+/, "", s)
          sub(/[ ,].*$/, "", s)
          n = s + 0
          inhunk = 1
          next
        }
        inhunk && /^\+/ {
          print substr($0, 2) >> cf
          print loc ":" n >> lf
          n++
        }
      '
    if [ "${PIPESTATUS[0]}" -ne 0 ]; then
      ptt_err "ptt hooks: error: git diff failed for $prefix$disp; refusing to continue"
      exit 1
    fi
  done <"$PTT_TMP/numstat"
}

# ptt_collect_message <location> <file> [raw]: queue a commit message. Comment lines are
# scanned too, because git keeps them with -m/-F or --cleanup=verbatim. Unless "raw" is given
# (for messages already stored in a commit or tag), everything below a scissors line (the
# "git commit -v" diff, which git always cuts) is skipped.
ptt_collect_message() {
  local cc=''
  if [ "${3:-}" != raw ]; then
    cc=$(git config --get core.commentChar 2>/dev/null)
    case $cc in
      ?) ;;
      *) cc='#' ;;
    esac
  fi
  PTT_LOC=$1 PTT_CC=$cc awk -v cf="$PTT_TMP/content" -v lf="$PTT_TMP/loc" '
    BEGIN { loc = ENVIRON["PTT_LOC"]; cc = ENVIRON["PTT_CC"] }
    cc != "" && index($0, cc " ------------------------ >8 ------------------------") == 1 { exit }
    { print >> cf; print loc ":" NR >> lf }
  ' "$2"
}

# ptt_scan: match the queued text against the denylist (case-insensitive, fixed strings).
# Case folding is done by grep -i in the current locale: ASCII always folds, but non-ASCII
# letters (for example accented names) fold only under a UTF-8 locale such as en_US.UTF-8.
# Prints one line per finding and returns 1 if anything matched.
ptt_scan() {
  local term
  [ -s "$PTT_TMP/content" ] || return 0
  : >"$PTT_TMP/findings"
  while IFS= read -r term; do
    grep -a -n -F -i -e "$term" -- "$PTT_TMP/content" | cut -d: -f1 |
      PTT_TERM=$term awk -v lf="$PTT_TMP/loc" '
        BEGIN { t = ENVIRON["PTT_TERM"] }
        { want[$1 + 0] = 1 }
        END {
          while ((getline line < lf) > 0) {
            i++
            if (i in want) printf "%d\t%s: matched term \"%s\"\n", i, line, t
          }
        }
      ' >>"$PTT_TMP/findings"
  done <"$PTT_TMP/terms"
  [ -s "$PTT_TMP/findings" ] || return 0
  sort -n -k1,1 "$PTT_TMP/findings" | cut -f2- >&2
  return 1
}

# ---------------------------------------------------------------------------------------------
# Identity
# ---------------------------------------------------------------------------------------------

# ptt_name_from_ident "<Name <email> 123 +0000>": print the name part.
ptt_name_from_ident() {
  printf '%s' "${1%% <*}"
}

# ptt_email_from_ident "<Name <email> 123 +0000>": print the email part.
ptt_email_from_ident() {
  local id=$1
  id=${id#*<}
  id=${id%%>*}
  printf '%s' "$id"
}

# ptt_email_ok <email> <author|committer>: succeed if the email may appear in a public commit.
ptt_email_ok() {
  local email allowed entry
  email=$(ptt_lower "$1")
  case $email in
    ?*"$PTT_NOREPLY_SUFFIX") return 0 ;;
  esac
  if [ "$2" = committer ] && [ "$email" = "$PTT_WEB_COMMITTER" ]; then
    return 0
  fi
  allowed=$(ptt_allowed_emails_path)
  if [ -f "$allowed" ] && [ -r "$allowed" ]; then
    while IFS= read -r entry; do
      [ "$(ptt_lower "$entry")" = "$email" ] && return 0
    done < <(ptt_read_list "$allowed")
  fi
  return 1
}

# ptt_email_error <what> <email>: report a rejected identity (see ptt_email_help).
ptt_email_error() {
  ptt_err "ptt hooks: error: $1 email <$2> is not a GitHub noreply address."
  PTT_EMAIL_HELP=1
}

# ptt_email_help: explain, once, how to fix rejected identities.
ptt_email_help() {
  [ -n "${PTT_EMAIL_HELP:-}" ] || return 0
  ptt_err "  This repo is public, so commits must not carry a personal email address. Use your"
  ptt_err "  GitHub noreply address (https://github.com/settings/emails) for this repo:"
  ptt_err "    git config user.email <id>+<login>@users.noreply.github.com"
  ptt_err "  and fix existing commits, e.g. git commit --amend --reset-author --no-edit."
  ptt_err "  To allow another address on purpose, list it in $(ptt_allowed_emails_path)."
}

# ---------------------------------------------------------------------------------------------
# gitleaks
# ---------------------------------------------------------------------------------------------

# ptt_find_gitleaks: set PTT_GITLEAKS to the gitleaks binary or exit (fail closed).
ptt_find_gitleaks() {
  local c
  PTT_GITLEAKS=${PTT_GITLEAKS:-}
  if [ -n "$PTT_GITLEAKS" ] && [ -x "$PTT_GITLEAKS" ]; then
    return 0
  fi
  PTT_GITLEAKS=$(command -v gitleaks 2>/dev/null)
  if [ -n "$PTT_GITLEAKS" ]; then
    return 0
  fi
  for c in "$HOME/.local/bin/gitleaks" "$HOME/go/bin/gitleaks" /opt/homebrew/bin/gitleaks \
    /usr/local/bin/gitleaks; do
    if [ -x "$c" ]; then
      PTT_GITLEAKS=$c
      return 0
    fi
  done
  ptt_err "ptt hooks: error: gitleaks is not installed, so secrets cannot be checked."
  ptt_err "  Install gitleaks v8.30 or later and make sure it is on PATH, for example:"
  ptt_err "    brew install gitleaks"
  ptt_err "    go install github.com/zricethezav/gitleaks/v8@v8.30.1"
  ptt_err "  or download a release binary into ~/.local/bin from"
  ptt_err "    https://github.com/gitleaks/gitleaks/releases"
  exit 1
}

# ptt_gitleaks <repo-dir> <gitleaks-git-args...>: run "gitleaks git" with the repo config.
ptt_gitleaks() {
  local repo=$1
  shift
  set -- git --redact --no-banner --verbose --log-level warn "$@"
  if [ -f "$repo/.gitleaks.toml" ]; then
    set -- "$@" --config "$repo/.gitleaks.toml"
  fi
  "$PTT_GITLEAKS" "$@" "$repo"
}
