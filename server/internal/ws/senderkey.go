package ws

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/signed"
)

// onSenderKey handles sender_key {key} (protocol sections 7 and 13.7). Only the page's sender
// may send it, signed by itself, naming the joined page and session; anything else is a protocol
// error. The server does not verify the signature. The first accepted key is stored with the
// page row and every later one is ignored, so the resend after each join needs no reply.
func (c *conn) onSenderKey(key signed.Object) {
	h := c.h
	ctx, cancel := context.WithTimeout(c.ctx, dbTimeout)
	defer cancel()
	if c.revokedCheck(ctx) {
		return
	}
	h.mu.Lock()
	s, r := c.sess, c.role
	h.mu.Unlock()
	if s == nil || r != roleSender || key.Signer != c.deviceID {
		c.closeNow(codeProtocol)
		return
	}
	sk, err := pages.ParseSenderKey(key.Payload)
	if err != nil || sk.PageID != s.pageID || sk.SessionID != s.sessionID || sk.FromDevice != c.deviceID {
		c.closeNow(codeProtocol)
		return
	}
	stored, _ := json.Marshal(key.Encode()) // plain strings: cannot fail
	// A page that has ended meanwhile has no row to update: nothing is stored, nothing is wrong.
	_, err = h.q.SetPageSenderKey(ctx, sqlcdb.SetPageSenderKeyParams{SenderKey: stored, ID: s.pageID})
	if err != nil {
		if !errors.Is(err, context.Canceled) {
			h.logger.LogAttrs(ctx, slog.LevelError, "websocket sender_key", slog.String("error", err.Error()))
		}
		c.closeNow(codeServerErr)
	}
}
