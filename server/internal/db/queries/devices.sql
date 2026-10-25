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
