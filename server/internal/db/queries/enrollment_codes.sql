-- name: InsertEnrollmentCode :exec
INSERT INTO enrollment_codes
  (code_hash, display_name, make_admin, created_by_device, user_id, created_at, expires_at)
VALUES (?, ?, ?, ?, ?, ?, ?);

-- Expire (never "use": used_at means redeemed by a device) every unredeemed code, whoever
-- minted it. Codes that expire sooner keep their earlier expiry.
-- name: ExpireOutstandingEnrollmentCodes :execrows
UPDATE enrollment_codes SET expires_at = MIN(expires_at, CAST(sqlc.arg(now) AS INTEGER))
WHERE used_at IS NULL AND expires_at > sqlc.arg(now);

-- Expire the codes minted over SSH (created_by_device IS NULL) that are still outstanding.
-- name: ExpireOutstandingSSHEnrollmentCodes :execrows
UPDATE enrollment_codes SET expires_at = sqlc.arg(now)
WHERE created_by_device IS NULL AND used_at IS NULL AND expires_at > sqlc.arg(now);
