#!/usr/bin/env python3
"""Fail if a security waiver lacks its justification or expires too far out.

Usage: check-waivers.py [TRIVYIGNORE_YAML OSV_TOML]  (defaults: the repo-root files)
Trivy waivers need a statement and expired_at within 30 days; OSV waivers need a reason and
ignoreUntil within 90 days (docs/operations.md, "Security scans as hard gates").
"""
import datetime
import sys
import tomllib

import yaml

trivy_path, osv_path = sys.argv[1:3] if len(sys.argv) > 2 else (".trivyignore.yaml", "osv-scanner.toml")
today = datetime.date.today()
errors = []


def check(path, entries, reason_key, date_key, max_days):
    for e in entries or []:
        name = f"{path}: {e.get('id', '?')}"
        if not str(e.get("id") or "").strip():
            errors.append(f"{name}: missing id")
        if not str(e.get(reason_key) or "").strip():
            errors.append(f"{name}: missing {reason_key}")
        d = e.get(date_key)
        if isinstance(d, datetime.datetime):
            d = d.date()
        if not isinstance(d, datetime.date):
            errors.append(f"{name}: missing or invalid {date_key}")
        elif d > today + datetime.timedelta(days=max_days):
            errors.append(f"{name}: {date_key} {d} is more than {max_days} days out")


with open(trivy_path) as f:
    check(trivy_path, (yaml.safe_load(f) or {}).get("vulnerabilities"), "statement", "expired_at", 30)
with open(osv_path, "rb") as f:
    check(osv_path, tomllib.load(f).get("IgnoredVulns"), "reason", "ignoreUntil", 90)

for e in errors:
    print(f"::error::{e}")
sys.exit(1 if errors else 0)
