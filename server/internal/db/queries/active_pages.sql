-- name: CountActivePages :one
SELECT COUNT(*) FROM active_pages;

-- name: GetActivePage :one
SELECT id, session_id, from_device, to_user, level, envelope, state, created_at, delivered_at,
       push_attempts, recipients, answered_by, answered_at
FROM active_pages
WHERE id = ?;

-- name: InsertActivePage :exec
INSERT INTO active_pages
  (id, session_id, from_device, to_user, level, envelope, state, created_at, push_attempts, recipients)
VALUES (?, ?, ?, ?, ?, ?, 'ringing', ?, 1, ?);

-- name: PageTombstoneExists :one
SELECT COUNT(*) FROM page_tombstones WHERE id = ?;

-- name: InsertPageTombstone :exec
INSERT OR IGNORE INTO page_tombstones (id, expires_at) VALUES (?, ?);

-- name: DeleteActivePage :execrows
DELETE FROM active_pages WHERE id = ?;

-- name: MarkPageDelivered :execrows
UPDATE active_pages SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL;

-- name: MarkPageAcknowledged :execrows
UPDATE active_pages SET state = 'acknowledged' WHERE id = ? AND state = 'ringing';

-- name: MarkPageAnswered :execrows
UPDATE active_pages SET state = 'answered', answered_by = ?, answered_at = ?
WHERE id = ? AND state IN ('ringing', 'acknowledged');

-- name: SetPagePushAttempts :exec
UPDATE active_pages SET push_attempts = ? WHERE id = ?;

-- The scheduler's input: the few columns every timer is derived from.
-- name: ListPageTimers :many
SELECT id, level, state, created_at, delivered_at, push_attempts, answered_at
FROM active_pages;

-- name: DeleteExpiredPageTombstones :execrows
DELETE FROM page_tombstones WHERE expires_at <= ?;
