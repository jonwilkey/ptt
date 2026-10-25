// Package roster parses the family roster payload (docs/protocol.md section 4) and applies the
// rules under which the server accepts a new roster.
package roster

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

const (
	// Format is the only payload format this server understands.
	Format = 1
	// maxNameRunes bounds display and device names, as at enrollment.
	maxNameRunes = 64
)

// Device is one roster entry.
type Device struct {
	ID          string
	UserID      string
	DisplayName string
	DeviceName  string
	Admin       bool
	Ed25519     []byte
	X25519      []byte
}

// Roster is a parsed, structurally valid roster payload.
type Roster struct {
	Version  int64
	IssuedAt int64
	Devices  []Device
}

// Find returns the entry for device id.
func (r Roster) Find(id string) (Device, bool) {
	for _, d := range r.Devices {
		if d.ID == id {
			return d, true
		}
	}
	return Device{}, false
}

type payloadJSON struct {
	Format   int             `json:"format"`
	Version  int64           `json:"version"`
	IssuedAt int64           `json:"issued_at"`
	Devices  json.RawMessage `json:"devices"`
}

type deviceJSON struct {
	ID          string `json:"id"`
	UserID      string `json:"user_id"`
	DisplayName string `json:"display_name"`
	DeviceName  string `json:"device_name"`
	Admin       bool   `json:"admin"`
	Ed25519     string `json:"ed25519"`
	X25519      string `json:"x25519"`
}

// Parse decodes and validates a roster payload: format 1, a positive version and issued_at,
// devices strictly sorted by id (so unique) with canonical UUIDs, 32-byte keys, valid names,
// and at least one admin (otherwise nobody could publish again). Field names are exact and
// case-sensitive, none repeats, none is missing or null, and there are no unknown fields or
// trailing data (see wire.StrictObject).
func Parse(payload []byte) (Roster, error) {
	top, err := wire.StrictObject(payload, "format", "version", "issued_at", "devices")
	if err != nil {
		return Roster{}, fmt.Errorf("roster payload: %w", err)
	}
	var rawDevices []json.RawMessage
	if err := json.Unmarshal(top["devices"], &rawDevices); err != nil {
		return Roster{}, fmt.Errorf("roster payload: devices: %w", err)
	}
	// Every key is now known to be present exactly once under its exact name, so the lenient
	// struct decode below cannot disagree with a case-sensitive parser.
	var p payloadJSON
	if err := json.Unmarshal(payload, &p); err != nil {
		return Roster{}, fmt.Errorf("roster payload: %w", err)
	}
	if p.Format != Format {
		return Roster{}, fmt.Errorf("roster payload: unsupported format %d", p.Format)
	}
	if p.Version <= 0 || p.IssuedAt <= 0 {
		return Roster{}, errors.New("roster payload: version and issued_at must be positive")
	}
	r := Roster{Version: p.Version, IssuedAt: p.IssuedAt, Devices: make([]Device, 0, len(rawDevices))}
	admins := 0
	for i, rawDev := range rawDevices {
		if _, err := wire.StrictObject(rawDev, "id", "user_id", "display_name", "device_name", "admin", "ed25519", "x25519"); err != nil {
			return Roster{}, fmt.Errorf("roster payload: device %d: %w", i, err)
		}
		var d deviceJSON
		if err := json.Unmarshal(rawDev, &d); err != nil {
			return Roster{}, fmt.Errorf("roster payload: device %d: %w", i, err)
		}
		if !wire.ValidUUID(d.ID) || !wire.ValidUUID(d.UserID) {
			return Roster{}, fmt.Errorf("roster payload: device %d: bad id", i)
		}
		if i > 0 && d.ID <= r.Devices[i-1].ID {
			return Roster{}, fmt.Errorf("roster payload: device %d: not sorted by id or duplicate", i)
		}
		if !wire.ValidText(d.DisplayName, maxNameRunes) || !wire.ValidText(d.DeviceName, maxNameRunes) {
			return Roster{}, fmt.Errorf("roster payload: device %d: bad name", i)
		}
		edKey, ok1 := wire.DecodeB64u(d.Ed25519, ed25519.PublicKeySize)
		xKey, ok2 := wire.DecodeB64u(d.X25519, 32)
		if !ok1 || !ok2 {
			return Roster{}, fmt.Errorf("roster payload: device %d: bad key", i)
		}
		if d.Admin {
			admins++
		}
		r.Devices = append(r.Devices, Device{
			ID: d.ID, UserID: d.UserID, DisplayName: d.DisplayName, DeviceName: d.DeviceName,
			Admin: d.Admin, Ed25519: edKey, X25519: xKey,
		})
	}
	if admins == 0 {
		return Roster{}, errors.New("roster payload: no admin device")
	}
	return r, nil
}

// Outcomes of Publish other than success or a database failure.
var (
	// ErrInvalid is roster_invalid: bad signature, format or content.
	ErrInvalid = errors.New("roster invalid")
	// ErrStale is roster_stale: the version is not higher than the stored one.
	ErrStale = errors.New("roster stale")
)

// Published describes an accepted roster.
type Published struct {
	Version int64
	Devices int
}

// Publish applies the acceptance rules of docs/protocol.md section 4 to obj and, if they hold,
// stores it as the current roster, recording the audit row, all in one transaction. The checks
// read the stored roster inside that transaction (writers are serialised), so two concurrent
// publishes cannot both win. actor is the authenticated device making the request.
//
// Test devices (is_test) never sign or appear in a roster.
func Publish(ctx context.Context, d *sql.DB, now time.Time, actor string, obj signed.Object) (Published, error) {
	var out Published
	err := db.InTx(ctx, d, func(q *sqlcdb.Queries) error {
		signer, err := q.GetDeviceForRoster(ctx, obj.Signer)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrInvalid
		}
		if err != nil {
			return err
		}
		if signer.RevokedAt.Valid || signer.IsTest == 1 {
			return ErrInvalid
		}
		if signed.Verify(signer.Ed25519Pub, signed.ContextRoster, obj) != nil {
			return ErrInvalid
		}
		next, err := Parse(obj.Payload)
		if err != nil {
			return ErrInvalid
		}

		current, err := q.GetRoster(ctx)
		hasCurrent := err == nil
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if hasCurrent {
			prev, perr := parseStored(current.SignedRoster)
			if perr != nil {
				return fmt.Errorf("stored roster: %w", perr)
			}
			if e, ok := prev.Find(obj.Signer); !ok || !e.Admin {
				return ErrInvalid
			}
			if next.Version <= prev.Version || next.Version <= current.Version {
				return ErrStale
			}
		} else {
			if signer.IsAdmin != 1 {
				return ErrInvalid
			}
			if e, ok := next.Find(obj.Signer); !ok || !e.Admin {
				return ErrInvalid
			}
		}

		for _, e := range next.Devices {
			if err := checkListed(ctx, q, e); err != nil {
				return err
			}
		}

		stored, err := json.Marshal(obj.Encode())
		if err != nil {
			return err
		}
		n, err := q.UpsertRoster(ctx, sqlcdb.UpsertRosterParams{
			Version: next.Version, SignedRoster: stored, SignerDevice: obj.Signer, UpdatedAt: now.UnixMilli(),
		})
		if err != nil {
			return err
		}
		if n == 0 {
			return ErrStale
		}
		out = Published{Version: next.Version, Devices: len(next.Devices)}
		return db.Audit(ctx, q, now, actor, "roster_published", map[string]int64{
			"version": next.Version, "devices": int64(len(next.Devices)),
		})
	})
	return out, err
}

// checkListed requires a listed device to exist, be live, be a real (non-test) device, and
// match the server's records.
func checkListed(ctx context.Context, q *sqlcdb.Queries, e Device) error {
	row, err := q.GetDeviceForRoster(ctx, e.ID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrInvalid
	}
	if err != nil {
		return err
	}
	if row.RevokedAt.Valid || row.IsTest == 1 ||
		row.UserID != e.UserID ||
		row.DisplayName != e.DisplayName ||
		(row.IsAdmin == 1) != e.Admin ||
		!bytes.Equal(row.Ed25519Pub, e.Ed25519) ||
		!bytes.Equal(row.X25519Pub, e.X25519) {
		return ErrInvalid
	}
	return nil
}

// parseStored decodes the stored signed object and its roster payload. Both were validated by
// Publish before they were stored, so an error means a corrupt database.
func parseStored(stored []byte) (Roster, error) {
	var w signed.Wire
	if err := json.Unmarshal(stored, &w); err != nil {
		return Roster{}, err
	}
	obj, err := signed.Decode(w)
	if err != nil {
		return Roster{}, err
	}
	return Parse(obj.Payload)
}
