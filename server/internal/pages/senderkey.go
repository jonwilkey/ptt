package pages

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// SenderKeyFormat is the only sender key payload format this server understands.
const SenderKeyFormat = 1

// SenderKey is a parsed sender key payload (docs/protocol.md section 7): the session key wrapped
// for the sender's own device. The server never opens or verifies it; it only checks whom it
// names.
type SenderKey struct {
	PageID     string
	SessionID  string
	FromDevice string
}

// ParseSenderKey strictly parses the sender key payload with the rules of ParseEnvelope: exactly
// the five fields, `format` 1, three canonical ids and `key` strict b64u of exactly 80 bytes.
func ParseSenderKey(payload []byte) (SenderKey, error) {
	top, err := wire.StrictObject(payload, "format", "page_id", "session_id", "from_device", "key")
	if err != nil {
		return SenderKey{}, fmt.Errorf("sender key payload: %w", err)
	}
	var (
		sk     SenderKey
		format int64
		key    string
	)
	fields := []struct {
		name string
		dst  any
	}{
		{"format", &format}, {"page_id", &sk.PageID}, {"session_id", &sk.SessionID},
		{"from_device", &sk.FromDevice}, {"key", &key},
	}
	for _, f := range fields {
		if err := json.Unmarshal(top[f.name], f.dst); err != nil {
			return SenderKey{}, fmt.Errorf("sender key payload: %s: %w", f.name, err)
		}
	}
	if format != SenderKeyFormat {
		return SenderKey{}, fmt.Errorf("sender key payload: unsupported format %d", format)
	}
	for name, id := range map[string]string{"page_id": sk.PageID, "session_id": sk.SessionID, "from_device": sk.FromDevice} {
		if !wire.ValidUUID(id) {
			return SenderKey{}, fmt.Errorf("sender key payload: %s is not a canonical uuid", name)
		}
	}
	if _, ok := wire.DecodeB64u(key, WrappedKeyBytes); !ok {
		return SenderKey{}, fmt.Errorf("sender key payload: bad wrapped key")
	}
	return sk, nil
}

// Recovery is the body of GET /v1/pages/{id}: the signed envelope and sender key as stored.
type Recovery struct {
	Envelope  signed.Wire `json:"envelope"`
	SenderKey signed.Wire `json:"sender_key"`
}

// SenderRecovery handles GET /v1/pages/{id} (protocol section 6). It checks in the order the
// section lists: unknown (ErrNotFound), ended (ErrEnded, for any caller), not the sender
// (ErrForbidden), no sender key stored yet (ErrNotFound). It changes nothing.
func (s *Service) SenderRecovery(ctx context.Context, deviceID, pageID string) (Recovery, error) {
	p, err := load(ctx, s.q, pageID)
	if err != nil {
		return Recovery{}, err
	}
	if p.FromDevice != deviceID {
		return Recovery{}, ErrForbidden
	}
	if p.SenderKey == nil {
		return Recovery{}, ErrNotFound
	}
	return recoveryOf(p)
}

func recoveryOf(p sqlcdb.ActivePage) (Recovery, error) {
	var r Recovery
	if err := json.Unmarshal(p.Envelope, &r.Envelope); err != nil {
		return Recovery{}, fmt.Errorf("stored envelope: %w", err)
	}
	if err := json.Unmarshal(p.SenderKey, &r.SenderKey); err != nil {
		return Recovery{}, fmt.Errorf("stored sender key: %w", err)
	}
	return r, nil
}
