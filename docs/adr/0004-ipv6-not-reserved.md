# 0004. The AAAA record follows the instance's IPv6 address (no reserved IPv6)

- Status: Accepted
- Date: 2026-10-24

## Context

The spec's "Cloud resources (OpenTofu)" table lists a reserved IP "so the server can be rebuilt
without changing DNS", and the VPS row requires IPv4 + IPv6. Read literally, both the A and the
AAAA record would point at reserved addresses that survive a rebuild.

On Vultr, an instance can be created with exactly one reserved IP as its **main** IP
(`reserved_ip_id`); the platform then configures that address for the guest automatically.
A reserved IPv6 address is a /64 subnet. Attaching a second reserved IP (the /64) to an instance
that already uses the reserved IPv4 as its main IP does not configure it in the guest: the OS
network configuration has to be edited by hand (or by Ansible) on every rebuild, and a mistake
there leaves the server without working IPv6 while the AAAA record still points at it.

## Decision

- Reserve only the IPv4 address and use it as the instance's main IP. The A record points at it
  and never changes.
- Do not reserve IPv6. The instance gets Vultr's automatically configured IPv6 address
  (`enable_ipv6 = true`), and the AAAA record points at the instance's `v6_main_ip`.
- On a rebuild, the new instance gets a new IPv6 address and the same `tofu apply` that creates
  it updates the AAAA record.

## Consequences

- No hand-maintained IPv6 network configuration on the host.
- After a rebuild, resolvers may serve the old AAAA record for up to its TTL, 300 seconds.
  IPv6-preferring clients fall back to IPv4 (Happy Eyeballs), and the rebuild runbook already
  allows far longer than that.
- A rebuild done outside OpenTofu (for example restoring a Vultr backup onto a new instance)
  needs a `tofu apply` or a manual AAAA update afterwards; the VPS-rebuild runbook must say so.
- If Vultr later supports a reserved IPv6 as the main address, this can be revisited with a new
  ADR.
