package pages

import (
	"encoding/json"
	"errors"
	"fmt"
	"slices"

	"github.com/jonwilkey/ptt/server/internal/wire"
)

const (
	// EnvelopeFormat is the only envelope payload format this server understands.
	EnvelopeFormat = 1
	// WrappedKeyBytes is an HPKE-wrapped session key: enc(32) || ct(32+16).
	WrappedKeyBytes = 80
	// maxRecipients bounds the keys map. A family has a handful of phones; the limit keeps a
	// crafted envelope from fanning out (the 4096-byte push limit bites well before this).
	maxRecipients = 64
)

// Page levels.
const (
	LevelNormal = "normal"
	LevelUrgent = "urgent"
)

// Envelope is a parsed page envelope payload (docs/protocol.md section 7). The server never
// verifies the signature or decrypts the keys; it only reads who the page is for.
type Envelope struct {
	PageID     string
	SessionID  string
	FromDevice string
	ToUser     string
	Level      string
	CreatedAt  int64
	// Keys are the recipient device ids, sorted. Their wrapped keys were checked for size only.
	Keys []string
}

// ParseEnvelope strictly parses the envelope payload: exact, case-sensitive field names with
// no duplicate, unknown, missing or null field and no trailing data; `keys` an object whose
// keys are canonical device ids (no duplicates) and whose values are strict b64u of exactly 80
// bytes. A lenient parse here could disagree with the phones about what the sender signed.
func ParseEnvelope(payload []byte) (Envelope, error) {
	top, err := wire.StrictObject(payload,
		"format", "page_id", "session_id", "from_device", "to_user", "level", "created_at", "keys")
	if err != nil {
		return Envelope{}, fmt.Errorf("envelope payload: %w", err)
	}
	var env Envelope
	var format int64
	fields := []struct {
		name string
		dst  any
	}{
		{"format", &format}, {"page_id", &env.PageID}, {"session_id", &env.SessionID},
		{"from_device", &env.FromDevice}, {"to_user", &env.ToUser}, {"level", &env.Level},
		{"created_at", &env.CreatedAt},
	}
	for _, f := range fields {
		if err := json.Unmarshal(top[f.name], f.dst); err != nil {
			return Envelope{}, fmt.Errorf("envelope payload: %s: %w", f.name, err)
		}
	}
	if format != EnvelopeFormat {
		return Envelope{}, fmt.Errorf("envelope payload: unsupported format %d", format)
	}
	for name, id := range map[string]string{
		"page_id": env.PageID, "session_id": env.SessionID, "from_device": env.FromDevice, "to_user": env.ToUser,
	} {
		if !wire.ValidUUID(id) {
			return Envelope{}, fmt.Errorf("envelope payload: %s is not a canonical uuid", name)
		}
	}
	if env.Level != LevelNormal && env.Level != LevelUrgent {
		return Envelope{}, errors.New("envelope payload: bad level")
	}
	if env.CreatedAt <= 0 {
		return Envelope{}, errors.New("envelope payload: created_at must be positive")
	}

	keys, err := wire.StrictMap(top["keys"], wire.ValidUUID)
	if err != nil {
		return Envelope{}, fmt.Errorf("envelope payload: keys: %w", err)
	}
	if len(keys) == 0 || len(keys) > maxRecipients {
		return Envelope{}, errors.New("envelope payload: keys must have 1 to 64 entries")
	}
	for id, raw := range keys {
		var s string
		if err := json.Unmarshal(raw, &s); err != nil {
			return Envelope{}, fmt.Errorf("envelope payload: keys: value is not a string: %w", err)
		}
		if _, ok := wire.DecodeB64u(s, WrappedKeyBytes); !ok {
			return Envelope{}, errors.New("envelope payload: keys: bad wrapped key")
		}
		env.Keys = append(env.Keys, id)
	}
	slices.Sort(env.Keys)
	return env, nil
}
