#!/usr/bin/env python3
"""Summarize Claude Code token cost for this repo from local transcripts.

Usage: scripts/agent-usage.py [--since YYYY-MM-DD] [--project-dir DIR]

Reads ~/.claude/projects/<repo path with / replaced by ->/**/*.jsonl (subagents live in
<session>/subagents/ with a .meta.json), dedupes assistant messages by message.id, prices usage
from the table below and prints: total, by component, by model x main/subagent, by role, top agents,
cache rebuilds after idle gaps, and tool result sizes. Read-only; writes nothing. Stdlib only.
Exit codes: 0 ok, 2 no transcripts found.
"""
import argparse
import collections as C
import datetime as D
import glob
import json
import os
import subprocess
import sys

# family substring -> (input, output, cache read) USD per million tokens.
# Cache writes: 5m at 1.25x input, 1h at 2x input. Unknown models are priced 0 and counted.
PRICES = {
    "opus": (4.0, 20.0, 0.20),
    "sonnet": (2.0, 10.0, 0.20),
}
REBUILD_MIN_TOKENS = 30000
TTL_5M = 300
TTL_1H = 3600


def family(model):
    for k in PRICES:
        if k in (model or ""):
            return k
    return None


def components(model, u):
    """Return {component: usd} for one usage dict, or None for an unpriced model."""
    f = family(model)
    if not f:
        return None
    i, o, r = PRICES[f]
    cc = u.get("cache_creation") or {}
    w5 = cc.get("ephemeral_5m_input_tokens", 0)
    w1 = cc.get("ephemeral_1h_input_tokens", 0)
    if not cc:
        w5 = u.get("cache_creation_input_tokens", 0) or 0
    return {
        "input": (u.get("input_tokens", 0) or 0) * i / 1e6,
        "cache_write_5m": w5 * i * 1.25 / 1e6,
        "cache_write_1h": w1 * i * 2 / 1e6,
        "cache_read": (u.get("cache_read_input_tokens", 0) or 0) * r / 1e6,
        "output": (u.get("output_tokens", 0) or 0) * o / 1e6,
    }


def parse_ts(s):
    try:
        return D.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None


def role_of(sub, meta):
    if not sub:
        return "main"
    desc = (meta.get("description") or "").lower()
    if "review" in desc:
        return "review"
    if desc:
        return "implement"
    return "other"


def load_meta(path):
    mf = path[: -len(".jsonl")] + ".meta.json"
    try:
        with open(mf) as fh:
            m = json.load(fh)
            return m if isinstance(m, dict) else {}
    except (OSError, ValueError):
        return {}


def result_text(c):
    s = c.get("content")
    return s if isinstance(s, str) else json.dumps(s)


def analyze(path, since, st):
    sub = "/subagents/" in path
    meta = load_meta(path) if sub else {}
    seen = {}
    idmap = {}
    prev_ts = None
    rebuilds = []
    turns = 0
    cost = 0.0
    model_of_file = None
    try:
        fh = open(path, errors="replace")
    except OSError:
        return
    with fh:
        for line in fh:
            try:
                d = json.loads(line)
            except ValueError:
                st["bad_lines"] += 1
                continue
            if not isinstance(d, dict):
                continue
            ts = parse_ts(d.get("timestamp"))
            if since and ts and ts.date() < since:
                continue
            m = d.get("message") if isinstance(d.get("message"), dict) else {}
            content = m.get("content")
            if d.get("type") == "assistant" and isinstance(m.get("usage"), dict):
                mid = m.get("id") or d.get("uuid")
                first_time = mid not in seen
                seen[mid] = True
                if first_time:
                    u = m["usage"]
                    model = m.get("model")
                    comp = components(model, u)
                    turns += 1
                    model_of_file = model_of_file or model
                    if comp is None:
                        st["unknown_models"][model] += 1
                    else:
                        c = sum(comp.values())
                        cost += c
                        for k, v in comp.items():
                            st["comp"][(sub, k)] += v
                        st["model"][(model, sub)] += c
                        cw = u.get("cache_creation_input_tokens", 0) or 0
                        cr = u.get("cache_read_input_tokens", 0) or 0
                        if prev_ts and ts and cw > REBUILD_MIN_TOKENS and cr < cw:
                            gap = (ts - prev_ts).total_seconds()
                            rebuilds.append((gap, comp["cache_write_5m"] + comp["cache_write_1h"]))
                if ts:
                    prev_ts = ts
                if isinstance(content, list):
                    for b in content:
                        if isinstance(b, dict) and b.get("type") == "tool_use":
                            inp = b.get("input") if isinstance(b.get("input"), dict) else {}
                            idmap[b.get("id")] = (b.get("name") or "?", inp.get("command", ""))
            elif d.get("type") == "user" and isinstance(content, list):
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        name, cmd = idmap.get(b.get("tool_use_id"), ("?", ""))
                        n = len(result_text(b))
                        st["tool_bytes"][name] += n
                        if name == "Bash":
                            st["bash"].append((n, " ".join(str(cmd).split())[:100]))
    if not seen:
        return
    st["files"] += 1
    st["calls"] += turns
    st["role"][role_of(sub, meta)] += cost
    st["role_n"][role_of(sub, meta)] += 1
    for gap, c in rebuilds:
        st["rebuilds"].append((sub, gap, c))
    if sub:
        st["agents"].append((cost, turns, meta.get("model") or model_of_file or "?",
                             meta.get("description") or "(no meta)"))


def default_project_dir():
    top = subprocess.run(["git", "-C", os.path.dirname(os.path.abspath(__file__)), "rev-parse",
                          "--show-toplevel"], capture_output=True, text=True).stdout.strip()
    # Worktrees live under <repo>/.claude/worktrees/<name>; transcripts key on the main checkout.
    marker = os.sep + ".claude" + os.sep + "worktrees" + os.sep
    if marker in top:
        top = top.split(marker)[0]
    return os.path.join(os.path.expanduser("~/.claude/projects"), top.replace("/", "-"))


def main():
    ap = argparse.ArgumentParser(description="Summarize Claude Code token cost for this repo.")
    ap.add_argument("--since", help="only count messages on or after YYYY-MM-DD")
    ap.add_argument("--project-dir", help="transcript directory (default: derived from the repo path)")
    a = ap.parse_args()
    since = None
    if a.since:
        try:
            since = D.date.fromisoformat(a.since)
        except ValueError:
            ap.error("--since must be YYYY-MM-DD")
    root = os.path.expanduser(a.project_dir) if a.project_dir else default_project_dir()
    files = sorted(glob.glob(os.path.join(root, "**", "*.jsonl"), recursive=True))
    if not files:
        print("no transcripts found under %s" % root, file=sys.stderr)
        return 2

    st = dict(bad_lines=0, files=0, calls=0, unknown_models=C.Counter(), comp=C.Counter(),
              model=C.Counter(), role=C.Counter(), role_n=C.Counter(), agents=[], rebuilds=[],
              tool_bytes=C.Counter(), bash=[])
    for f in files:
        analyze(f, since, st)

    total = sum(st["comp"].values())
    print("TOTAL $%.2f over %d transcripts, %d API calls%s" % (
        total, st["files"], st["calls"], " since " + a.since if since else ""))
    if st["unknown_models"]:
        print("unpriced (counted as $0): %s" % dict(st["unknown_models"]))
    if st["bad_lines"]:
        print("skipped %d malformed lines" % st["bad_lines"])

    print("\nby component")
    names = ["input", "cache_write_5m", "cache_write_1h", "cache_read", "output"]
    for k in names:
        v = st["comp"][(False, k)] + st["comp"][(True, k)]
        print("  %-15s $%8.2f (main %.2f, sub %.2f)" % (k, v, st["comp"][(False, k)], st["comp"][(True, k)]))

    print("\nby model x main/subagent")
    for (model, sub), v in st["model"].most_common():
        print("  $%8.2f  %s  %s" % (v, model, "subagent" if sub else "main"))

    print("\nby role")
    for r, v in st["role"].most_common():
        print("  $%8.2f  %s (%d transcripts)" % (v, r, st["role_n"][r]))

    print("\ntop 15 agents")
    for cost, turns, model, desc in sorted(st["agents"], key=lambda x: -x[0])[:15]:
        print("  $%7.2f  %4d turns  %-22s %s" % (cost, turns, model, desc[:70]))

    print("\ncache rebuilds after idle gaps (cache write >%d tokens, little read)" % REBUILD_MIN_TOKENS)
    buckets = C.defaultdict(lambda: [0, 0.0])
    for sub, gap, c in st["rebuilds"]:
        b = "gap<=5m" if gap <= TTL_5M else "5m<gap<=1h" if gap <= TTL_1H else "gap>1h"
        buckets[b][0] += 1
        buckets[b][1] += c
    print("  total %d, $%.2f" % (len(st["rebuilds"]), sum(c for _, _, c in st["rebuilds"])))
    for b in ("gap<=5m", "5m<gap<=1h", "gap>1h"):
        print("  %-11s %5d  $%.2f" % (b, buckets[b][0], buckets[b][1]))

    print("\ntool result bytes by tool")
    for k, v in st["tool_bytes"].most_common(12):
        print("  %8.1f KB  %s" % (v / 1e3, k))
    print("\n10 largest Bash outputs")
    for n, cmd in sorted(st["bash"], reverse=True)[:10]:
        print("  %8d  %s" % (n, cmd))
    return 0


if __name__ == "__main__":
    sys.exit(main())
