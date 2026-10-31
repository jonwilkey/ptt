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
`crypto/hpke`, and for `audio_frames` `internal/audioframe` plus `crypto/hkdf`, `crypto/aes` and `crypto/cipher` (test code only; the server never encrypts). The Android unit tests (`android/app/src/test/.../crypto/ProtocolVectorsTest.kt`)
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
