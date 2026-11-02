# 0010. The sender wraps the session key for itself

- Status: Accepted
- Date: 2026-11-01

## Context

The spec's "Server" page lifecycle step 5 says that when the sender's phone is not connected at
the answer, the `page_answered` push reopens its talk screen "and anything the receiver already
said plays as a clip". The spec's Security section says the session key is wrapped with HPKE
"for each of the receiver's roster devices", and another of its bullets keeps session keys in
memory only, discarded when the session ends. Together these fail in the common case the step
exists for: a sender whose app was swiped away has lost the session key, nothing on the phone
or in the envelope lets it unwrap the key again, and the receiver's clips (and any further talk)
cannot be decrypted.

Three ways to give the sender a copy were considered:

1. **An entry for the sender's device in the envelope's `keys`.** The server checks that every
   `keys` id is a device of the recipient user, pushes the page to every `keys` device, and the
   phones take `keys` as the talk peers. The sender would become its own peer and be paged.
2. **A new signed field in the envelope (envelope format 2).** Milestone-4 phones parse the
   envelope strictly (exactly the format-1 fields, `format` 1) and drop anything else, so a
   format-2 page to a phone that has not updated would never ring. The sender cannot know which
   app version each recipient device runs, and the protocol's 30-day rule requires a new app to
   keep working with phones that have not updated yet.
3. **A new field in `POST /v1/pages`.** The server's request parser is strict as well, so a
   server rolled back to milestone 4 would refuse every page, which the same 30-day rule forbids.

## Decision

The sender wraps the session key for its own device in a separate object, the **sender key**,
signed with its Ed25519 key under the context `ptt-sender-key-v1` and HPKE-wrapped to its own
X25519 key with an `info` label (`ptt-sender-session-key-v1`) that differs from the receivers'.
It sends the sender key over the protocol-2 WebSocket after each join (`sender_key`); the server
stores it with the page row and returns it, with the envelope, from `GET /v1/pages/{id}` to the
sender only. A sender without the key in memory fetches both, verifies its own two signatures,
unwraps the key and joins. The envelope and its receivers are unchanged (format 1).
`docs/protocol.md` §7 and §13.7 hold the details.

The WebSocket carries it because `hello` already negotiates the protocol: a server that answers
protocol 1 never receives a sender key and has no `GET /v1/pages/{id}`, and the sender simply
cannot recover, as in milestone 4. The Security bullet "session keys live only in memory" still
holds on the phones: the copy lives on the server, wrapped, only while the page exists.

## Consequences

- A sender whose process died hears the receiver's clips after the answer and can talk again.
- Someone who captured a session's traffic and later steals the **sender's** X25519 private key
  can now also decrypt that session (before, only a receiver device's key could). This is the
  same class of risk the spec already accepts for v1 by having no ratchet, and the server keeps
  the wrapped copy only while the page exists.
- The server can withhold the sender key (the sender then cannot recover) but cannot substitute
  one: the sender checks its own signature, and the server cannot sign as the sender.
- Server work (15b): a nullable column on the active page row, the `sender_key` message, and
  `GET /v1/pages/{id}`. Android work (15c/15d): build and send the sender key, and the recovery
  path from the `page_answered` notification.
- A sender whose process dies before its first `sender_key` reaches the server (within about a
  second of paging) still cannot recover.
