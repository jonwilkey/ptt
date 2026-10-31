-- A session id is unique among active pages (docs/protocol.md section 11.4): the WebSocket
-- `join` finds the page by session id alone, so two live pages must never share one.
--
-- Expand-only in effect: no column changes, and the previous server version keeps working
-- against this schema (it never creates two active pages with one session id: phones generate
-- random UUIDs). The unique index replaces the plain one on the same column, which it serves
-- equally well for lookups. An ended page's row is deleted, so a tombstoned page does not hold
-- its session id.

-- +goose Up
DROP INDEX active_pages_session_id;
CREATE UNIQUE INDEX active_pages_session_id ON active_pages (session_id);

-- +goose Down
DROP INDEX active_pages_session_id;
CREATE INDEX active_pages_session_id ON active_pages (session_id);
