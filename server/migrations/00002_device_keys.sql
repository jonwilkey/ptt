-- Device public keys and enrollment bookkeeping (docs/protocol.md, sections 2 and 5).
--
-- Expand-only: every new column is nullable, so the previous server version keeps working
-- against the migrated schema. The added columns carry no REFERENCES: SQLite cannot DROP a
-- column that a foreign key uses, and the Down section below must work.

-- +goose Up
-- Raw 32-byte keys; NULL for rows that predate enrollment.
ALTER TABLE devices ADD COLUMN ed25519_pub BLOB CHECK (ed25519_pub IS NULL OR length(ed25519_pub) = 32);
ALTER TABLE devices ADD COLUMN x25519_pub  BLOB CHECK (x25519_pub IS NULL OR length(x25519_pub) = 32);

-- The existing person a new device joins (users.id); NULL means the code creates a new person.
ALTER TABLE enrollment_codes ADD COLUMN user_id TEXT;
-- The device that redeemed the code (devices.id); NULL while unused.
ALTER TABLE enrollment_codes ADD COLUMN device_id TEXT;
-- The admin-enrollment MAC the new phone sent; NULL for bootstrap codes.
ALTER TABLE enrollment_codes ADD COLUMN enroll_mac BLOB;

-- +goose Down
ALTER TABLE enrollment_codes DROP COLUMN enroll_mac;
ALTER TABLE enrollment_codes DROP COLUMN device_id;
ALTER TABLE enrollment_codes DROP COLUMN user_id;
ALTER TABLE devices DROP COLUMN x25519_pub;
ALTER TABLE devices DROP COLUMN ed25519_pub;
