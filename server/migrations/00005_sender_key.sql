-- The sender's own wrapped session key (docs/protocol.md sections 7 and 13.7, ADR 0010): the
-- signed sender-key object, stored as the wire JSON {payload, signer, sig} like the envelope and
-- opaque to the server. It lives and dies with the page row; the tombstone keeps only the id.
--
-- Expand-only: the column is nullable, so the previous server version keeps working against the
-- migrated schema (it never reads or writes it).

-- +goose Up
ALTER TABLE active_pages ADD COLUMN sender_key BLOB;

-- +goose Down
ALTER TABLE active_pages DROP COLUMN sender_key;
