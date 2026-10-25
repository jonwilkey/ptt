-- name: InsertAuditLog :exec
INSERT INTO audit_log (at, actor_device, action, detail)
VALUES (?, ?, ?, ?);

-- name: DeleteAuditLogBefore :execrows
DELETE FROM audit_log WHERE at < ?;
