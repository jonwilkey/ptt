-- name: InsertUser :exec
INSERT INTO users (id, display_name, is_admin, created_at)
VALUES (?, ?, ?, ?);

-- name: GetUser :one
SELECT * FROM users WHERE id = ?;

-- name: CountAdminUsers :one
SELECT COUNT(*) FROM users WHERE is_admin = 1;
