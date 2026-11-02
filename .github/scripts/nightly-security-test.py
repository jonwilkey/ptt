#!/usr/bin/env python3
"""Tests for nightly-security.py: normalizing scanner output and the issue decisions, then the
whole `sync` against a fake `gh`. Run through scheduled-test.sh (python3 -I, stdlib only)."""
import importlib.util
import sys

sys.dont_write_bytecode = True
import json
import os
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "nightly-security.py")
spec = importlib.util.spec_from_file_location("nightly_security", SCRIPT)
ns = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ns)

TRIVY = {"Results": [
    {"Target": "x", "Type": "debian", "Vulnerabilities": [
        {"VulnerabilityID": "CVE-2026-0001", "PkgName": "libc6", "InstalledVersion": "2.41-1",
         "FixedVersion": "2.41-2", "Severity": "HIGH"},
        {"VulnerabilityID": "CVE-2026-0002", "PkgName": "libc6", "InstalledVersion": "2.41-1", "Severity": "MEDIUM"},
        {"VulnerabilityID": "CVE-2026-0003", "PkgName": "zlib", "InstalledVersion": "1", "Severity": "CRITICAL"},
    ]},
    {"Target": "pttd", "Type": "gobinary", "Vulnerabilities": None},
]}

OSV = {"results": [
    {"source": {"path": "/w/server/go.mod"}, "packages": [
        {"package": {"name": "golang.org/x/net", "version": "0.1.0", "ecosystem": "Go"}, "groups": [
            {"ids": ["GHSA-aaaa", "CVE-2026-1111"], "max_severity": "8.1"},
            {"ids": ["GHSA-low"], "max_severity": "5.0"},
            {"ids": ["GHSA-unscored-go"], "max_severity": ""},
        ]}]},
    {"source": {"path": "/w/android/gradle.lockfile"}, "packages": [
        {"package": {"name": "com.example:lib", "version": "1.0", "ecosystem": "Maven"}, "groups": [
            {"ids": ["GHSA-maven-unscored"], "max_severity": ""},
            {"ids": ["GHSA-maven-high"], "max_severity": "9.8"},
        ]}]},
    {"source": {"path": "/w/other/gradle.lockfile"}, "packages": [
        {"package": {"name": "com.example:lib", "version": "1.0", "ecosystem": "Maven"}, "groups": [
            {"ids": ["GHSA-maven-high"], "max_severity": "9.8"}]}]},
]}

GOVULN = "\n".join(json.dumps(m, indent=1) for m in [
    {"config": {"protocol_version": "v1.0.0"}},
    {"osv": {"id": "GO-2026-0001"}},
    {"finding": {"osv": "GO-2026-0001", "fixed_version": "v1.2.3",
                 "trace": [{"module": "stdlib", "version": "v1.27.3", "package": "net/http", "function": "Serve"}]}},
    {"finding": {"osv": "GO-2026-0002", "trace": [{"module": "example.com/m", "version": "v1"}]}},
    {"finding": {"osv": "GO-2026-0003", "trace": [{"module": "example.com/m", "version": "v1", "package": "p"}]}},
])


def read(path):
    with open(path) as fh:
        return fh.read()


def jload(path):
    with open(path) as fh:
        return json.load(fh)


def jdump(obj, path):
    with open(path, "w") as fh:
        json.dump(obj, fh)


def f(vid, pkg="Go/m", eco_pkg=None):
    return {"id": vid, "package": pkg, "scanner": "t", "severity": "HIGH", "installed": "1",
            "fixed": "", "where": ["somewhere"]}


class Normalize(unittest.TestCase):
    def test_trivy_keeps_high_and_critical(self):
        found, below = ns.trivy_findings(TRIVY, "ghcr.io/o/r/pttd@sha256:x")
        self.assertEqual([(x["id"], x["package"], x["severity"]) for x in found],
                         [("CVE-2026-0001", "debian/libc6", "HIGH"), ("CVE-2026-0003", "debian/zlib", "CRITICAL")])
        self.assertEqual(below, 1)
        self.assertEqual(found[0]["fixed"], "2.41-2")

    def test_osv_same_rule_as_ci(self):
        found, warnings = ns.osv_findings(OSV)
        merged = ns.merge(found)
        self.assertEqual([(x["id"], x["package"]) for x in merged], [
            ("CVE-2026-1111", "Go/golang.org/x/net"),  # lowest sorted alias is the primary id
            ("GHSA-maven-high", "Maven/com.example:lib"),
            ("GHSA-maven-unscored", "Maven/com.example:lib"),
        ])
        self.assertEqual(len(warnings), 1)  # unscored Go advisory: a warning, govulncheck gates it
        self.assertEqual(len(merged[1]["where"]), 2)  # found in two lockfiles, one issue

    def test_govulncheck_only_reachable(self):
        found = ns.govulncheck_findings(GOVULN)
        self.assertEqual([(x["id"], x["package"], x["installed"], x["fixed"]) for x in found],
                         [("GO-2026-0001", "Go/stdlib", "v1.27.3", "v1.2.3")])

    def test_markers_are_inert(self):
        x = ns.make("t", "CVE-1", "Go", "evil-->pkg <script>", "HIGH", "1", "", "w")
        self.assertNotIn(">", x["package"])
        self.assertNotIn("<", x["package"])
        self.assertIn("-->", ns.marker(x).rsplit("pkg", 1)[-1])  # only the real terminator


def issue(n, state, vid, pkg="Go/m"):
    return {"number": n, "state": state, "body": f"<!-- nightly-security: {vid} {pkg} -->\ntext"}


class Plan(unittest.TestCase):
    def run_plan(self, findings, issues, bot=(), complete=True):
        return ns.plan(findings, issues, lambda n: n in bot, complete)

    def test_new_finding_opens(self):
        actions, _ = self.run_plan([f("A")], [])
        self.assertEqual([(a["action"]) for a in actions], ["open"])

    def test_known_open_finding_is_quiet(self):
        actions, skipped = self.run_plan([f("A")], [issue(1, "OPEN", "A")])
        self.assertEqual((actions, skipped), ([], []))

    def test_gone_finding_closes(self):
        actions, _ = self.run_plan([], [issue(1, "OPEN", "A")])
        self.assertEqual([(a["action"], a["number"]) for a in actions], [("close", 1)])

    def test_gone_finding_not_closed_when_scan_incomplete(self):
        actions, skipped = self.run_plan([], [issue(1, "OPEN", "A")], complete=False)
        self.assertEqual(actions, [])
        self.assertEqual(len(skipped), 1)

    def test_bot_closed_reopens_person_closed_stays(self):
        issues = [issue(1, "CLOSED", "A"), issue(2, "CLOSED", "B")]
        actions, skipped = self.run_plan([f("A"), f("B")], issues, bot={1})
        self.assertEqual([(a["action"], a["number"]) for a in actions], [("reopen", 1)])
        self.assertIn("#2", skipped[0])

    def test_closed_issue_without_finding_is_left_alone(self):
        actions, skipped = self.run_plan([], [issue(1, "CLOSED", "A")])
        self.assertEqual((actions, skipped), ([], []))

    def test_other_package_same_id_is_a_different_issue(self):
        actions, _ = self.run_plan([f("A", "Go/x")], [issue(1, "OPEN", "A", "Go/y")])
        self.assertEqual(sorted(a["action"] for a in actions), ["close", "open"])

    def test_unmarked_issues_are_ignored(self):
        actions, _ = self.run_plan([], [{"number": 9, "state": "OPEN", "body": "a person's security issue"}])
        self.assertEqual(actions, [])

    def test_duplicate_markers_prefer_open(self):
        actions, _ = self.run_plan([f("A")], [issue(1, "OPEN", "A"), issue(2, "CLOSED", "A")], bot={2})
        self.assertEqual(actions, [])


FAKE_GH = r"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["GH_LOG"], "a") as fh:
    fh.write(json.dumps(args) + "\n")
if args[:2] == ["issue", "list"]:
    print(open(os.environ["GH_ISSUES"]).read())
elif args[0] == "api":
    n = args[2].split("/")[4]
    print(open(os.environ["GH_EVENTS_" + n]).read())
"""


class EndToEnd(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        gh = os.path.join(self.d, "gh")
        with open(gh, "w") as fh:
            fh.write(FAKE_GH)
        os.chmod(gh, os.stat(gh).st_mode | stat.S_IXUSR)
        self.env = dict(os.environ, PATH=self.d + os.pathsep + os.environ["PATH"],
                        GH_LOG=os.path.join(self.d, "log"), GH_ISSUES=os.path.join(self.d, "issues"),
                        GITHUB_REPOSITORY="o/r", GITHUB_RUN_ID="42", GITHUB_SERVER_URL="https://github.com")

    def write(self, name, text):
        p = os.path.join(self.d, name)
        with open(p, "w") as fh:
            fh.write(text)
        return p

    def sync(self, findings, issues, events, complete=True, missing=()):
        self.write("issues", json.dumps(issues))
        for n, actors in events.items():
            self.env[f"GH_EVENTS_{n}"] = self.write(f"events{n}", "\n".join(actors))
        fp = self.write("findings.json", json.dumps({"complete": complete, "missing": list(missing), "findings": findings}))
        r = subprocess.run([sys.executable, "-I", SCRIPT, "sync", "--findings", fp], env=self.env,
                           capture_output=True, text=True)
        log = [json.loads(line) for line in read(self.env["GH_LOG"]).splitlines()] if os.path.exists(self.env["GH_LOG"]) else []
        return r, log

    def test_full_run(self):
        issues = [issue(1, "OPEN", "KEEP"), issue(2, "OPEN", "GONE"), issue(3, "CLOSED", "BOT"),
                  issue(4, "CLOSED", "HUMAN")]
        events = {3: ["jonwilkey", "github-actions[bot]"], 4: ["github-actions[bot]", "jonwilkey"]}
        r, log = self.sync([f("KEEP"), f("NEW"), f("BOT"), f("HUMAN")], issues, events)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        writes = [a for a in log if a[0] == "issue" and a[1] in ("create", "close", "reopen")]
        self.assertEqual(sorted((a[1], a[2] if a[1] != "create" else a[a.index("--title") + 1]) for a in writes),
                         [("close", "2"), ("create", "security: NEW in Go/m"), ("reopen", "3")])
        create = next(a for a in writes if a[1] == "create")
        body = create[create.index("--body") + 1]
        self.assertIn("<!-- nightly-security: NEW Go/m -->", body)
        self.assertEqual(create[create.index("--label") + 1], "security")
        close = next(a for a in writes if a[1] == "close")
        self.assertEqual(close[close.index("--reason") + 1], "completed")
        listing = next(a for a in log if a[:2] == ["issue", "list"])
        self.assertIn("--state", listing)
        self.assertEqual(listing[listing.index("--state") + 1], "all")

    def test_incomplete_scan_opens_but_does_not_close_and_fails(self):
        r, log = self.sync([f("NEW")], [issue(2, "OPEN", "GONE")], {}, complete=False, missing=["trivy"])
        self.assertEqual(r.returncode, 1)
        verbs = sorted(a[1] for a in log if a[0] == "issue" and a[1] != "list")
        self.assertEqual(verbs, ["create"])

    def test_quiet_run_makes_no_writes(self):
        r, log = self.sync([f("A")], [issue(1, "OPEN", "A")], {})
        self.assertEqual(r.returncode, 0)
        self.assertEqual([a for a in log if a[0] == "issue" and a[1] != "list"], [])

    def test_cap_on_new_issues(self):
        r, log = self.sync([f(f"V{i:03}") for i in range(40)], [], {})
        self.assertEqual(r.returncode, 1)
        self.assertEqual(len([a for a in log if a[:2] == ["issue", "create"]]), ns.MAX_NEW_ISSUES)


class NormalizeCli(unittest.TestCase):
    def test_normalize_marks_missing_scanners(self):
        with tempfile.TemporaryDirectory() as d:
            t = os.path.join(d, "t.json")
            jdump(TRIVY, t)
            o = os.path.join(d, "out.json")
            r = subprocess.run([sys.executable, "-I", SCRIPT, "normalize", "--out", o, "--trivy", t,
                                "--image-ref", "ghcr.io/o/r/pttd@sha256:x"], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            out = jload(o)
            self.assertFalse(out["complete"])
            self.assertEqual(len(out["findings"]), 2)
            self.assertEqual(len(out["missing"]), 2)

    def test_osv_failed_without_findings_is_a_broken_scan(self):
        with tempfile.TemporaryDirectory() as d:
            e = os.path.join(d, "osv.json")
            jdump({"results": []}, e)
            o = os.path.join(d, "out.json")
            subprocess.run([sys.executable, "-I", SCRIPT, "normalize", "--out", o, "--osv", e,
                            "--osv-outcome", "failure"], capture_output=True, text=True, check=True)
            self.assertTrue(any("osv-scanner" in m for m in jload(o)["missing"]))


if __name__ == "__main__":
    unittest.main(verbosity=0)
