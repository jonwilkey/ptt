# 0007. The outbox abandons rows after a send window

- Status: Accepted
- Date: 2026-10-26

## Context

The spec's "Reliable sending (outbox)" says every outgoing action is retried and "rows are deleted
once the server accepts them". Taken literally, a row that is never accepted lives forever. That is
harmful for pages:

- The server keeps a tombstone for an ended page for only 24 hours (protocol section 8). A `page` row
  retried after more than 24 hours offline would no longer match a tombstone, so the server would
  create a new page and ring someone for a page that died long ago.
- A page queued while the sender is offline can ring hours later, after the sender has given up or
  moved on.
- Rows of other kinds (`delivered`, `acknowledge`, `answer`, `cancel`) are useless long after the page
  is over, and an unbounded table is a liability.

## Decision

- Before every send the outbox records the attempt (`attempts + 1`, with the next retry time) in the
  row, so `attempts == 0` means the request was never sent, even if the process died mid-request. An
  attempt that did not leave the phone because the device is not enrolled is not counted.
- A `page` row has a send window of 15 minutes from its creation, for both levels
  (`Outbox.PAGE_SEND_WINDOW_MS`). When a drain finds an expired page row it deletes the page's `page`
  and `cancel` rows and publishes `OutboxOutcome.Result.Expired`. If the page row had `attempts > 0` it
  may exist on the server, so a fresh `cancel` for that page id is queued (cancel is idempotent, and a
  404 is final). With `attempts == 0` nothing is queued: the page never reached the server. The
  phone's own receiver rows for the page (`delivered`, `acknowledge`, `answer`, as in a Test alert to
  itself) are left alone.
- `enqueue(Cancel)` only inserts the row, with no lock, so the cancel is durable at once and the caller
  never waits. When a drain reaches a page whose `page` row has `attempts == 0` and a `cancel` row
  exists, it deletes both, queues nothing, and publishes `Result.CancelledLocally`. With
  `attempts > 0` the cancel goes out after the page row, as usual.
- Rows of every other kind are dropped, with `Result.Expired`, 24 hours after creation
  (`Outbox.MAX_ROW_AGE_MS`).
- The outbox honours `Retry-After` for at most 5 minutes (`RETRY_AFTER_CAP_MS`), so one rate-limited row
  cannot hold back the rest of a page's rows for an hour.

## Consequences

- A page that cannot be sent within 15 minutes fails visibly (the outcome event) instead of ringing
  late; the user can page again. A flaky connection that recovers after 15 minutes needs a manual retry.
- A sender cancelling an offline page never causes a ring-then-cancel on the recipient.
- What prevents a replay against a forgotten page is the 15-minute `page` window: the server's 24-hour
  tombstone runs from the page's end, not from the row's creation, so a page sent within its window
  is never replayed after its tombstone is gone. The 24-hour cap on other kinds only bounds the table.
- A cancel is never lost to a cancelled caller or a process death between `enqueue` and the next
  drain, and never blocks the caller behind a drain in progress.
- A cancel may be sent for a page whose first request did not actually reach the server (the process
  died before sending); the server answers 404, which is final.
- The windows are constants in code; changing them needs no migration.
