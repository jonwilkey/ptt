-- name: ListDevicesWithUsers :many
SELECT d.id, u.display_name AS user_display_name, u.is_admin, d.name, d.app_version,
       d.last_seen_at, d.revoked_at, d.is_test
FROM devices d
JOIN users u ON u.id = d.user_id
ORDER BY u.display_name, u.id, d.created_at, d.id;

-- name: RevokeDevice :execrows
UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL;

-- name: RevokeAllDevices :execrows
UPDATE devices SET revoked_at = ? WHERE revoked_at IS NULL;

-- name: DeviceExists :one
SELECT COUNT(*) FROM devices WHERE id = ?;

-- The authentication lookup. Revoked devices never match, and the result is never cached, so a
-- revocation takes effect on the very next request.
-- name: GetActiveDeviceByTokenHash :one
SELECT d.id, d.user_id, u.is_admin, d.is_test, d.last_seen_at
FROM devices d
JOIN users u ON u.id = d.user_id
WHERE d.token_hash = ? AND d.revoked_at IS NULL;

-- name: TouchDeviceLastSeen :exec
UPDATE devices SET last_seen_at = ? WHERE id = ?;

-- name: InsertDevice :exec
INSERT INTO devices
  (id, user_id, name, token_hash, fcm_token, app_version, created_at, last_seen_at, ed25519_pub, x25519_pub)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- Absent (NULL) fields keep their current value.
-- name: UpdateDeviceSelf :exec
UPDATE devices SET
  fcm_token        = COALESCE(sqlc.narg(fcm_token), fcm_token),
  app_version      = COALESCE(sqlc.narg(app_version), app_version),
  name             = COALESCE(sqlc.narg(name), name),
  checklist_status = COALESCE(sqlc.narg(checklist_status), checklist_status)
WHERE id = sqlc.arg(id);

-- A redeemed code's device with its person, for the admin's enrollment poll.
-- name: GetDeviceWithUser :one
SELECT d.id, d.user_id, u.display_name, u.is_admin, d.name, d.ed25519_pub, d.x25519_pub
FROM devices d
JOIN users u ON u.id = d.user_id
WHERE d.id = ?;

-- The inputs of GET /v1/contacts: one row per visible device. Presence is derived in Go.
-- name: ListContactDevices :many
SELECT u.id AS user_id, u.display_name, u.is_admin, d.last_seen_at, d.checklist_status
FROM devices d
JOIN users u ON u.id = d.user_id
WHERE d.revoked_at IS NULL AND d.is_test = 0
ORDER BY u.display_name, u.id, d.id;

-- Everything the roster acceptance rules compare against, for any device (revoked and test
-- devices included, so the rules can reject them explicitly).
-- name: GetDeviceForRoster :one
SELECT d.id, d.user_id, u.display_name, u.is_admin, d.ed25519_pub, d.x25519_pub,
       d.revoked_at, d.is_test
FROM devices d
JOIN users u ON u.id = d.user_id
WHERE d.id = ?;

-- Push targets for broadcasts: live, non-test devices that registered an FCM token.
-- name: ListPushTargets :many
SELECT id, fcm_token
FROM devices
WHERE revoked_at IS NULL AND is_test = 0 AND fcm_token IS NOT NULL AND fcm_token <> ''
ORDER BY id;

-- name: GetDevicePushInfo :one
SELECT id, fcm_token, revoked_at
FROM devices
WHERE id = ?;

-- Clears a token FCM reported as unregistered. The token comparison keeps a token the phone
-- registered while the push was in flight.
-- name: ClearFCMToken :exec
UPDATE devices SET fcm_token = NULL WHERE id = ? AND fcm_token = ?;

-- WebSocket revocation re-checks (docs/protocol.md section 11.9).
-- name: IsDeviceRevoked :one
SELECT COUNT(*) FROM devices WHERE id = ? AND revoked_at IS NOT NULL;

-- The 5 s sweep: which of the connected devices have been revoked, in one query.
-- name: ListRevokedAmong :many
SELECT id FROM devices WHERE revoked_at IS NOT NULL AND id IN (sqlc.slice('ids'));
