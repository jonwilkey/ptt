#!/usr/bin/env python3
"""nightly-security's findings and issues (docs/operations.md, "nightly-security"; ADR 0014).

  nightly-security.py normalize --out findings.json [--trivy FILE --image-ref REF]
                                [--osv FILE --osv-outcome OUTCOME] [--govulncheck FILE]
  nightly-security.py sync --findings findings.json [--dry-run]

normalize reads the scanners' JSON and keeps what would fail a release gate: Trivy HIGH or
CRITICAL, OSV CVSS >= 7.0 or unscored outside Go (the ci / osv-scanner rule), and every
reachable govulncheck finding. Waived findings never arrive here: the scanners read the waiver
files themselves. A scanner whose file is not given counts as "did not run", and the result is
marked incomplete.

sync opens, closes and reopens GitHub issues (via `gh`) so each finding has one issue:
  - no issue with its marker: open one;
  - open issue, finding reported: nothing;
  - open issue, finding gone: comment and close as completed (only when every scanner ran);
  - closed issue, finding reported again: reopen, only if github-actions[bot] closed it.
It exits 1 when the scan was incomplete (after doing the opens and reopens it can do safely).
"""
import argparse
import json
import os
import re
import subprocess
import sys

BOT = "github-actions[bot]"
LABEL = "security"
MARKER_RE = re.compile(r"<!-- nightly-security: (\S+) (\S+) -->")
MAX_NEW_ISSUES = 25  # a parser gone wrong must not flood the tracker
HIGH = {"HIGH", "CRITICAL"}


def clean(value):
    """Keeps advisory ids and package names inert in titles and in the HTML-comment marker."""
    return re.sub(r"[^A-Za-z0-9._/@+:~-]", "_", str(value))


def make(scanner, vuln_id, ecosystem, package, severity, installed, fixed, where):
    return {
        "scanner": scanner,
        "id": clean(vuln_id),
        "package": f"{clean(ecosystem)}/{clean(package)}",
        "severity": str(severity),
        "installed": str(installed or ""),
        "fixed": str(fixed or ""),
        "where": [where],
    }


def merge(findings):
    """One finding per (id, package); the places it was found in are joined."""
    merged = {}
    for f in findings:
        key = (f["id"], f["package"])
        if key in merged:
            for w in f["where"]:
                if w not in merged[key]["where"]:
                    merged[key]["where"].append(w)
        else:
            merged[key] = f
    return sorted(merged.values(), key=lambda f: (f["id"], f["package"]))


def trivy_findings(doc, image_ref):
    found, below = [], 0
    for result in doc.get("Results") or []:
        for v in result.get("Vulnerabilities") or []:
            if v.get("Severity") in HIGH:
                found.append(make("Trivy", v.get("VulnerabilityID", "?"), result.get("Type", "?"),
                                  v.get("PkgName", "?"), v["Severity"], v.get("InstalledVersion"),
                                  v.get("FixedVersion"), f"deployed image {image_ref}"))
            else:
                below += 1
    return found, below


def osv_findings(doc):
    """Same rule as ci / osv-scanner: >= 7.0, or unscored and not Go. Returns (findings, warnings)."""
    found, warnings = [], []
    for res in doc.get("results") or []:
        src = (res.get("source") or {}).get("path", "?")
        for pkg in res.get("packages") or []:
            p = pkg.get("package") or {}
            for group in pkg.get("groups") or []:
                ids = sorted(group.get("ids") or [])
                if not ids:
                    continue
                raw = group.get("max_severity") or ""
                try:
                    score = float(raw) if raw != "" else None
                except ValueError:
                    score = None
                eco = p.get("ecosystem", "?")
                if score is None and eco == "Go":
                    warnings.append(f"Unscored Go advisory {ids[0]} in Go/{p.get('name')} (govulncheck gates these)")
                    continue
                if score is not None and score < 7.0:
                    continue
                fixed = ""
                found.append(make("OSV-Scanner", ids[0], eco, p.get("name", "?"),
                                  raw or "unscored", p.get("version"), fixed, f"lockfile {src}"))
                found[-1]["aliases"] = ids
    return found, warnings


def govulncheck_findings(text):
    """Reachable findings only (symbol-level), the ones `govulncheck ./...` exits 3 for."""
    found, dec, i = [], json.JSONDecoder(), 0
    text = text.strip()
    while i < len(text):
        msg, end = dec.raw_decode(text, i)
        i = end
        while i < len(text) and text[i].isspace():
            i += 1
        f = msg.get("finding")
        if not f:
            continue
        trace = f.get("trace") or []
        if not trace or not trace[0].get("function"):
            continue
        t = trace[0]
        found.append(make("govulncheck", f.get("osv", "?"), "Go", t.get("module", "?"),
                          "reachable", t.get("version"), f.get("fixed_version"), "server module (main)"))
    return found


def load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def normalize(args):
    findings, notes, ran, missing = [], [], [], []
    if args.trivy:
        f, below = trivy_findings(load_json(args.trivy), args.image_ref or "?")
        findings += f
        ran.append("trivy")
        notes.append(f"Trivy: {len(f)} high or critical, {below} lower severity (not reported).")
    else:
        missing.append("trivy (no deployed image was scanned)")
    if args.osv:
        failed = args.osv_outcome == "failure"
        if not os.path.exists(args.osv) or os.path.getsize(args.osv) == 0:
            missing.append("osv-scanner (wrote no results)")
        else:
            f, warnings = osv_findings(load_json(args.osv))
            raw_any = any(g for r in load_json(args.osv).get("results") or []
                          for p in r.get("packages") or [] for g in p.get("groups") or [])
            if failed and not raw_any:
                missing.append("osv-scanner (failed but reported no findings)")
            else:
                findings += f
                ran.append("osv-scanner")
                notes += [f"OSV-Scanner: {len(f)} at the release-gate level."] + warnings
    else:
        missing.append("osv-scanner")
    if args.govulncheck:
        with open(args.govulncheck, encoding="utf-8") as fh:
            f = govulncheck_findings(fh.read())
        findings += f
        ran.append("govulncheck")
        notes.append(f"govulncheck: {len(f)} reachable.")
    else:
        missing.append("govulncheck")
    findings = merge(findings)
    out = {"complete": not missing, "ran": ran, "missing": missing, "notes": notes, "findings": findings}
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)
    print("### nightly-security scan")
    print()
    for n in notes:
        print(f"- {n}")
    for m in missing:
        print(f"- **Did not run or failed:** {m}")
    print()
    if findings:
        print("| Advisory | Package | Scanner | Severity | Installed | Fixed | Found in |")
        print("| --- | --- | --- | --- | --- | --- | --- |")
        for f in findings:
            print(f"| {f['id']} | {f['package']} | {f['scanner']} | {f['severity']} | "
                  f"{f['installed'] or '-'} | {f['fixed'] or '-'} | {'; '.join(f['where'])} |")
    else:
        print("No findings at the release-gate level.")
    return 0


def title(f):
    return f"security: {f['id']} in {f['package']}"


def marker(f):
    return f"<!-- nightly-security: {f['id']} {f['package']} -->"


def body(f, run_url):
    aliases = f.get("aliases") or []
    rows = [
        ("Advisory", f["id"] + (f" (also {', '.join(a for a in aliases if a != f['id'])})" if len(aliases) > 1 else "")),
        ("Package", f["package"]),
        ("Scanner", f["scanner"]),
        ("Severity", f["severity"]),
        ("Installed", f["installed"] or "unknown"),
        ("Fixed in", f["fixed"] or "no fix known"),
        ("Found in", "; ".join(f["where"])),
    ]
    table = "\n".join(f"| {k} | {v} |" for k, v in rows)
    return (
        f"{marker(f)}\n"
        "The nightly security scan found a vulnerability that would fail a release gate "
        "(ADR 0014, `docs/adr/0014-nightly-security-reports-release-gate-findings.md`).\n\n"
        f"| | |\n| --- | --- |\n{table}\n\n"
        f"First seen in {run_url}\n\n"
        "What to do: update the dependency or base image (Renovate may already have a pull request), "
        "or, if it cannot be fixed and does not matter here, waive it in `.trivyignore.yaml` or "
        "`osv-scanner.toml` by pull request, with a reason and an expiry date. The scan closes this "
        "issue when the finding stops being reported. Closing it by hand means \"accepted\": the scan "
        "will not reopen an issue a person closed.\n"
    )


def plan(findings, issues, closed_by_bot, complete):
    """Pure decisions. issues: [{number, state, body}]; closed_by_bot(number) -> bool."""
    by_key = {}
    for issue in sorted(issues, key=lambda i: i["number"]):
        m = MARKER_RE.search(issue.get("body") or "")
        if not m:
            continue
        key = (m.group(1), m.group(2))
        prev = by_key.get(key)
        # Several issues with one marker (should not happen): an open one wins, else the newest.
        if prev is None or prev["state"] != "OPEN" or issue["state"] == "OPEN":
            by_key[key] = issue
    actions, skipped = [], []
    reported = {(f["id"], f["package"]): f for f in findings}
    for key, f in sorted(reported.items()):
        issue = by_key.get(key)
        if issue is None:
            actions.append({"action": "open", "finding": f})
        elif issue["state"] == "CLOSED":
            if closed_by_bot(issue["number"]):
                actions.append({"action": "reopen", "number": issue["number"], "finding": f})
            else:
                skipped.append(f"#{issue['number']} {key[0]} in {key[1]}: closed by a person, stays closed")
    for key, issue in sorted(by_key.items()):
        if issue["state"] == "OPEN" and key not in reported:
            if complete:
                actions.append({"action": "close", "number": issue["number"], "key": key})
            else:
                skipped.append(f"#{issue['number']} {key[0]} in {key[1]}: not closed, the scan was incomplete")
    return actions, skipped


def gh(*argv, check=True):
    return subprocess.run(["gh", *argv], check=check, capture_output=True, text=True).stdout


def list_issues():
    # --limit pages through the API; the search API is not used (its index lags and may not match
    # an HTML comment).
    out = gh("issue", "list", "--label", LABEL, "--state", "all", "--limit", "5000",
             "--json", "number,state,body")
    return json.loads(out or "[]")


def closed_by_bot(number):
    out = gh("api", "--paginate", f"repos/{{owner}}/{{repo}}/issues/{number}/events",
             "--jq", '.[] | select(.event == "closed") | .actor.login')
    actors = [line for line in out.splitlines() if line.strip()]
    return bool(actors) and actors[-1] == BOT


def sync(args):
    data = load_json(args.findings)
    run_url = "{}/{}/actions/runs/{}".format(os.environ.get("GITHUB_SERVER_URL", "https://github.com"),
                                             os.environ.get("GITHUB_REPOSITORY", "OWNER/REPO"),
                                             os.environ.get("GITHUB_RUN_ID", "0"))
    actions, skipped = plan(data["findings"], list_issues(), closed_by_bot, data["complete"])
    opens = [a for a in actions if a["action"] == "open"]
    over = max(0, len(opens) - MAX_NEW_ISSUES)
    if over:
        keep = {id(a) for a in opens[:MAX_NEW_ISSUES]}
        actions = [a for a in actions if a["action"] != "open" or id(a) in keep]
        skipped.append(f"{over} more new findings not opened (limit {MAX_NEW_ISSUES} per run)")
    for a in actions:
        what = a.get("finding") or {"id": a["key"][0], "package": a["key"][1]}
        print(f"{a['action']:7} {a.get('number', '')} {what['id']} in {what['package']}")
    for s in skipped:
        print(f"skipped {s}")
    if not actions:
        print("No issue changes.")
    if args.dry_run:
        return 0 if data["complete"] and not over else 1
    if opens:
        gh("label", "create", LABEL, "--description", "Security findings from the nightly scan",
           "--color", "D93F0B", check=False)
    for a in actions:
        if a["action"] == "open":
            f = a["finding"]
            gh("issue", "create", "--title", title(f), "--body", body(f, run_url), "--label", LABEL)
        elif a["action"] == "reopen":
            gh("issue", "reopen", str(a["number"]), "--comment",
               f"The nightly scan reports this finding again ({run_url}). Reopened because the scan closed it.")
        else:
            gh("issue", "close", str(a["number"]), "--reason", "completed", "--comment",
               "No longer reported by the nightly scan.")
    if not data["complete"]:
        print("::error::The scan was incomplete: " + "; ".join(data["missing"]))
        return 1
    return 1 if over else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    n = sub.add_parser("normalize")
    n.add_argument("--out", required=True)
    n.add_argument("--trivy")
    n.add_argument("--image-ref")
    n.add_argument("--osv")
    n.add_argument("--osv-outcome", default="success")
    n.add_argument("--govulncheck")
    s = sub.add_parser("sync")
    s.add_argument("--findings", required=True)
    s.add_argument("--dry-run", action="store_true")
    args = p.parse_args()
    return normalize(args) if args.cmd == "normalize" else sync(args)


if __name__ == "__main__":
    sys.exit(main())
