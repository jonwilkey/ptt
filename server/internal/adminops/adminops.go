// Package adminops implements the operator actions behind `pttd admin bootstrap`, `devices` and
// `revoke` (docs/protocol.md section 10). They talk to the database directly, because they run
// over SSH inside the container and must work before any device exists to authenticate.
package adminops

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

// CodeTTL is how long a bootstrap enrollment code stays valid.
const CodeTTL = 24 * time.Hour

// ErrAdminExists is returned by Bootstrap when an admin already exists and reset is false.
var ErrAdminExists = errors.New("an admin user already exists; use --reset to revoke every device and start over")

// ErrUnknownDevice is returned by Revoke for an ID that matches no device.
var ErrUnknownDevice = errors.New("no such device")

// Device is one row of `pttd admin devices`.
type Device struct {
	ID         string     `json:"id"`
	Person     string     `json:"person"`
	Admin      bool       `json:"admin"`
	Name       string     `json:"name"`
	Version    string     `json:"version,omitempty"`
	IsTest     bool       `json:"is_test"`
	LastSeenAt *time.Time `json:"last_seen_at,omitempty"`
	RevokedAt  *time.Time `json:"revoked_at,omitempty"`
}

// withTx runs f in a transaction, committing only if f succeeds.
func withTx(ctx context.Context, db *sql.DB, f func(q *sqlcdb.Queries) error) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	if err := f(sqlcdb.New(tx)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}
	return nil
}

func audit(ctx context.Context, q *sqlcdb.Queries, now time.Time, action string, detail any) error {
	b, err := json.Marshal(detail)
	if err != nil {
		return fmt.Errorf("encode audit detail: %w", err)
	}
	// SSH admin actions have no acting device.
	err = q.InsertAuditLog(ctx, sqlcdb.InsertAuditLogParams{
		At:     now.UnixMilli(),
		Action: action,
		Detail: sql.NullString{String: string(b), Valid: true},
	})
	if err != nil {
		return fmt.Errorf("write audit log: %w", err)
	}
	return nil
}

// Bootstrap mints a 24-hour enrollment code that makes the first admin, named name, and returns
// it. Without reset it fails with ErrAdminExists if any admin user exists. With reset it first
// revokes every device, deletes the roster and retires outstanding codes, so the family
// re-enrolls from scratch. The code itself is never logged; only its public ID is.
func Bootstrap(ctx context.Context, db *sql.DB, name string, reset bool, now time.Time) (code string, err error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", errors.New("name must not be empty")
	}
	code, hash, err := auth.NewEnrollmentCode()
	if err != nil {
		return "", err
	}

	nowMs := now.UnixMilli()
	err = withTx(ctx, db, func(q *sqlcdb.Queries) error {
		var retired int64
		if reset {
			revoked, err := q.RevokeAllDevices(ctx, sql.NullInt64{Int64: nowMs, Valid: true})
			if err != nil {
				return fmt.Errorf("revoke devices: %w", err)
			}
			if err := q.DeleteRoster(ctx); err != nil {
				return fmt.Errorf("delete roster: %w", err)
			}
			// Expired, not marked used: used_at means a device redeemed the code.
			if retired, err = q.ExpireOutstandingEnrollmentCodes(ctx, nowMs); err != nil {
				return fmt.Errorf("retire enrollment codes: %w", err)
			}
			if err := audit(ctx, q, now, "bootstrap_reset", map[string]any{"revoked_devices": revoked}); err != nil {
				return err
			}
		} else {
			admins, err := q.CountAdminUsers(ctx)
			if err != nil {
				return fmt.Errorf("count admins: %w", err)
			}
			if admins > 0 {
				return ErrAdminExists
			}
			// A repeated bootstrap must not leave an earlier (possibly lost) admin QR valid.
			if retired, err = q.ExpireOutstandingSSHEnrollmentCodes(ctx, nowMs); err != nil {
				return fmt.Errorf("retire enrollment codes: %w", err)
			}
		}

		err := q.InsertEnrollmentCode(ctx, sqlcdb.InsertEnrollmentCodeParams{
			CodeHash:    hash,
			DisplayName: name,
			MakeAdmin:   1,
			CreatedAt:   nowMs,
			ExpiresAt:   now.Add(CodeTTL).UnixMilli(),
		})
		if err != nil {
			return fmt.Errorf("insert enrollment code: %w", err)
		}
		return audit(ctx, q, now, "bootstrap", map[string]any{
			"display_name":  name,
			"code_id":       auth.CodeID(hash),
			"reset":         reset,
			"retired_codes": retired,
		})
	})
	if err != nil {
		return "", err
	}
	return code, nil
}

// ListDevices returns every device, revoked ones included, grouped by person.
func ListDevices(ctx context.Context, db *sql.DB) ([]Device, error) {
	rows, err := sqlcdb.New(db).ListDevicesWithUsers(ctx)
	if err != nil {
		return nil, fmt.Errorf("list devices: %w", err)
	}
	out := make([]Device, 0, len(rows))
	for _, r := range rows {
		out = append(out, Device{
			ID:         r.ID,
			Person:     r.UserDisplayName,
			Admin:      r.IsAdmin == 1,
			Name:       r.Name,
			Version:    r.AppVersion.String,
			IsTest:     r.IsTest == 1,
			LastSeenAt: millis(r.LastSeenAt),
			RevokedAt:  millis(r.RevokedAt),
		})
	}
	return out, nil
}

func millis(n sql.NullInt64) *time.Time {
	if !n.Valid {
		return nil
	}
	t := time.UnixMilli(n.Int64).UTC()
	return &t
}

// Revoke revokes one device. It returns ErrUnknownDevice for an ID that matches nothing, and
// changed=false (with no audit entry) when the device was already revoked.
func Revoke(ctx context.Context, db *sql.DB, deviceID string, now time.Time) (changed bool, err error) {
	err = withTx(ctx, db, func(q *sqlcdb.Queries) error {
		n, err := q.DeviceExists(ctx, deviceID)
		if err != nil {
			return fmt.Errorf("look up device: %w", err)
		}
		if n == 0 {
			return fmt.Errorf("%w: %s", ErrUnknownDevice, deviceID)
		}
		rows, err := q.RevokeDevice(ctx, sqlcdb.RevokeDeviceParams{
			RevokedAt: sql.NullInt64{Int64: now.UnixMilli(), Valid: true},
			ID:        deviceID,
		})
		if err != nil {
			return fmt.Errorf("revoke device: %w", err)
		}
		if rows == 0 {
			return nil
		}
		changed = true
		return audit(ctx, q, now, "device_revoked", map[string]any{"device_id": deviceID})
	})
	return changed, err
}
