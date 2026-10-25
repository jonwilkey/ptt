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

-- name: GetEnrollmentCode :one
SELECT * FROM enrollment_codes WHERE code_hash = ?;

-- Redeem a code. The conditions repeat the validity check so that, whatever else ran in the
-- transaction, a used or expired code can never be consumed.
-- name: RedeemEnrollmentCode :execrows
UPDATE enrollment_codes SET used_at = sqlc.arg(now), device_id = sqlc.arg(device_id), enroll_mac = sqlc.arg(enroll_mac)
WHERE code_hash = sqlc.arg(code_hash) AND used_at IS NULL AND expires_at > sqlc.arg(now);

-- Expire the unredeemed codes a device minted, when that device is revoked.
-- name: ExpireEnrollmentCodesCreatedBy :execrows
UPDATE enrollment_codes SET expires_at = sqlc.arg(now)
WHERE created_by_device = sqlc.arg(device_id) AND used_at IS NULL AND expires_at > sqlc.arg(now);

-- Nightly cleanup: a code goes 24 h after it expired or 24 h after it was used, whichever
-- applies. The cutoff is now - 24 h.
-- name: DeleteStaleEnrollmentCodes :execrows
DELETE FROM enrollment_codes WHERE expires_at < ? OR used_at < ?;
