-- name: InsertAuditLog :exec
INSERT INTO audit_log (at, actor_device, action, detail)
VALUES (?, ?, ?, ?);
