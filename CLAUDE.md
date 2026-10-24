# Family Push-to-Talk

This repo is for a family push-to-talk Andriod app. The full spec is in docs/spec.md. Follow its
decisions table; record any deviation as an ADR in docs/adr/ before making it. Work milestone by
milestone, in small PRs.

The implementation is over-seen by an orchestrator. If you are the orchestrator, you dispatch
subagents to accomplish each task and have the full spec in context. You create two types of
subagents:

1. implementer: receives a fully scoped task to implement with just the context needed to
  to accomplish that task
2. reviewer: checks the implementers work, has all of the context that the orchestrator deems
  necessary to rigorously review the implementers work and ensure that it meets all reqwuirements

The implementer iterates on feedback from the reviewer until the reviewer is satisfied with the
quality of the implementation.
