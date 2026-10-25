-- name: DeleteRoster :exec
DELETE FROM roster;

-- name: GetRoster :one
SELECT id, version, signed_roster, signer_device, updated_at FROM roster WHERE id = 1;

-- The version guard makes a lost race (or a stale version) change no rows; callers treat zero
-- rows affected as roster_stale.
-- name: UpsertRoster :execrows
INSERT INTO roster (id, version, signed_roster, signer_device, updated_at)
VALUES (1, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET
  version       = excluded.version,
  signed_roster = excluded.signed_roster,
  signer_device = excluded.signer_device,
  updated_at    = excluded.updated_at
WHERE excluded.version > roster.version;
