#!/usr/bin/env bash
# Prints a public-safe summary of a saved OpenTofu plan: one line of counts, then one
# "<actions> <address>" line per changed resource. No attribute values.
#
#   tofu-plan-summary.sh <plan file>     (run from the module directory)

set -euo pipefail

json="$(mktemp)"
trap 'rm -f "$json"' EXIT
tofu show -json "$1" > "$json"
jq -r '
  [ .resource_changes[]?
    | { a: (.change.actions | join(",")), addr: .address }
    | select(.a != "no-op" and .a != "read") ] as $c
  | [ (.output_changes // {})[] | select(.actions != ["no-op"]) ] as $o
  | ( "create: \([$c[] | select(.a == "create")] | length), "
    + "update: \([$c[] | select(.a == "update")] | length), "
    + "delete: \([$c[] | select(.a == "delete")] | length), "
    + "replace: \([$c[] | select(.a == "delete,create" or .a == "create,delete")] | length), "
    + "outputs: \($o | length)" ),
    ( $c[] | "\(.a) \(.addr)" )
' "$json"
