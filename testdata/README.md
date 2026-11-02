# testdata/

## protocol-vectors.json

Cross-language test vectors for `docs/protocol.md`, shared by the Go server and the Android app.
Every value is synthetic (seeds are `SHA-256("ptt-test-vector/<label>")`; ids, names and the
`example.com` URL are made up).

**Generated, not hand-edited.** The Go test `server/internal/vectors` writes it:

```sh
cd server && go test ./internal/vectors -update   # regenerate
cd server && go test ./internal/vectors           # check: file is current and Go agrees with every vector
```

Everything is deterministic except HPKE's ephemeral `enc`; regeneration reuses the ciphertexts
already in the committed file (it checks they still open), so the file only changes when a vector
does. The Go side checks every vector with the server's own parsers (`internal/signed`,
`internal/roster`, `internal/wire`, `internal/pages`) and `crypto/ed25519`, `crypto/hmac`,
`crypto/hpke`, and for `audio_frames` and `clip_fallback` `internal/audioframe` plus `crypto/hkdf`, `crypto/aes` and `crypto/cipher` (test code only; the server never encrypts). The Android unit tests (`android/app/src/test/.../crypto/ProtocolVectorsTest.kt`)
read the same file (path passed as the `ptt.protocolVectors` system property) and must reach the
same verdict on each one.

| Section | Contents |
| --- | --- |
| `signed` | Signed-object wire JSON with an expected `valid` / `malformed` / `bad_signature`; valid ones carry the Ed25519 seed, payload and signature, which must be reproduced exactly |
| `rosters` | Roster payloads (b64u of the raw bytes, so invalid UTF-8 is representable): `valid` with the parsed fields, or `invalid` |
| `enrollment_macs` | Secret, code, device id, keys, MAC, and whether the MAC is correct |
| `qrs` | QR fields and the exact `pttenroll1:` text (bootstrap and admin forms) |
| `hpke` | Recipient X25519 key pair, `info` inputs, session key and the 80-byte wrapped key made by Go |
| `envelope_payloads` | Envelope payloads: `valid` with fields, or `invalid` |
| `envelope_receive` | Full receiver scenarios (roster, signed envelope, push fields, receiver key): `ok` with the session key, or `reject` with the reason |
| `roster_accept` | Phone-side roster acceptance (sections 4 and 5): current roster or QR admins plus own identity, a signed candidate, and the expected accept or first failing rule |
| `audio_frames` | Section 12: `transmissions` (session key, ids, HKDF `info` and key, and for each frame the Opus packet, nonce and complete wire frame), `reject` (frames a receiver must drop, with the first failing check), `replay` (one transmission delivered in order; per-frame verdict `ok` = authenticated and first time that seq, `replay` = authenticated but seq already accepted, `drop` = failed a section 12.5 check and used up no seq) and `sessions` (stateful scenarios across floor events: `granted` is `accept`/`refuse`, `frame` is `ok`/`replay`/`drop`; `reconnected` marks the receiver's own socket closing; covers the rejoin snapshot, suspended transmissions and the same seq under a new transmission id) |
| `clip_fallback` | Section 13 (protocol 2), kept apart from `audio_frames` so milestone-4 receivers' checks of that section stay valid: `transmissions` (one contiguous transmission, seq 0 to 5), `resume` (given `resume_from`, the exact frames the talker resends: its stored bytes for every seq above it) and `sessions` (the `audio_frames` session steps plus `clip`, with `talker`, `transmission_id`, `last_seq` and expect `accept`/`refuse`, and `heard`, the cumulative `last_seq` the receiver's next `heard` for that id would carry at that point: expect `send` with `last_seq`, or `none` (it pins the value, not when a `heard` is sent: the timing rules of section 13.5 are checked by the Android tests of 15c); and `peer_joined` with `device_id`, expect `accept`/`refuse`; each session names its `receiver`, the phone's `device_id` and `role`, `answerer` with `from_device` or `sender` with `keys`; covers clip replay after a release, a clip of an unseen transmission, a clip ending a current or suspended transmission, dedup of seqs already accepted, a resealed frame refused, a resumed talker's resend, a wrong clip talker, a clip naming the phone itself, the clip talkers each role accepts, a recovered sender, and live and clip frames interleaved) |
| `sender_keys` | Sections 7 and 13.7: `hpke` (the sender's own wrap, `info` with the `ptt-sender-session-key-v1` label), `recover` (the sender's recovery from `GET /v1/pages/{id}`: its own device id, Ed25519 public key and X25519 private key, the push page id, the envelope and sender-key wire JSON; `ok` with the session key, or `reject` with the first failing check: `envelope_not_own` (also an envelope the sender signed whose `from_device` names another device), `envelope_bad_signature`, `envelope_bad_payload` (an envelope the sender signed that does not parse), `page_id_mismatch`, `sender_key_not_own`, `sender_key_bad_signature`, `sender_key_bad_payload`, `sender_key_mismatch`, `decrypt_failed`) and `receive` (`envelope_receive` scenarios on a self page: a sender-key wrap moved into `keys` is `decrypt_failed`) |
