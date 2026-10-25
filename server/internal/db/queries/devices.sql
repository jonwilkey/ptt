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
