-- Page lifecycle state (docs/protocol.md, section 8).
--
-- Expand-only: every new column is nullable, so the previous server version keeps working
-- against the migrated schema. The added columns carry no REFERENCES: SQLite cannot DROP a
-- column that a foreign key uses, and the Down section below must work.
--
-- There is deliberately no next_push_at / next_deadline column. Every timer is a pure function
-- of columns that already exist (created_at, level, state, delivered_at, push_attempts) plus
-- answered_at, so the scheduler recomputes deadlines from the rows after a restart and a stored
-- deadline can never disagree with the state it was derived from.

-- +goose Up
-- JSON array of the device ids the page was pushed to (the non-revoked devices in the
-- envelope's keys). Fixed at creation: it is also the list of devices allowed to call the
-- delivered, acknowledge and answer actions.
ALTER TABLE active_pages ADD COLUMN recipients TEXT;
-- The recipient device that answered (active_pages.state = 'answered'), and when.
ALTER TABLE active_pages ADD COLUMN answered_by TEXT;
ALTER TABLE active_pages ADD COLUMN answered_at INTEGER;

-- +goose Down
ALTER TABLE active_pages DROP COLUMN answered_at;
ALTER TABLE active_pages DROP COLUMN answered_by;
ALTER TABLE active_pages DROP COLUMN recipients;
