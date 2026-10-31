package ws

import (
	"context"
	"log/slog"
)

// revokedCheck re-reads the device's revoked_at (hello did at authentication; join and every
// floor_request do again, protocol section 11.9). If it is revoked, the socket is closed
// 4403 and its session ended, and true is returned.
func (c *conn) revokedCheck(ctx context.Context) bool {
	n, err := c.h.q.IsDeviceRevoked(ctx, c.deviceID)
	if err != nil {
		if ctx.Err() == nil {
			c.h.logger.LogAttrs(ctx, slog.LevelError, "websocket revocation check", slog.String("error", err.Error()))
		}
		c.closeNow(codeServerErr)
		return true
	}
	if n == 0 {
		return false
	}
	c.h.revoke(c)
	return true
}

// CloseDevice closes the device's socket, if it has one, because it was revoked (the REST
// revoke calls this, protocol section 11.9).
func (h *Hub) CloseDevice(deviceID string) {
	h.mu.Lock()
	c := h.socks[deviceID]
	h.mu.Unlock()
	if c != nil {
		h.revoke(c)
	}
}

// revoke closes c with 4403, which also ends its session: the peer hears peer_left, and for an
// answered page session_end ended_by_peer (section 11.5). A revoked sender whose page is still
// ringing keeps its page: whether revocation ends ringing pages is not changed by the
// WebSocket.
func (h *Hub) revoke(c *conn) {
	h.mu.Lock()
	var pageID string
	if c.sess != nil {
		pageID = c.sess.pageID
	}
	c.closeNow(codeRevoked)
	h.detachLocked(c)
	h.mu.Unlock()

	if pageID != "" && h.ender != nil {
		if err := h.ender.EndRevoked(h.ctx, c.deviceID, pageID); err != nil && h.ctx.Err() == nil {
			h.logger.LogAttrs(h.ctx, slog.LevelError, "end revoked session", slog.String("error", err.Error()))
		}
	}
}

// sweepLoop checks every 5 s which connected devices were revoked since (by `pttd admin
// revoke` or `bootstrap --reset`, which run in another process and write revoked_at only).
func (h *Hub) sweepLoop() {
	for {
		select {
		case <-h.ctx.Done():
			return
		case <-h.clk.After(sweepInterval):
		}
		h.Sweep(h.ctx)
	}
}

// Sweep closes the socket of every connected device that has been revoked, with one query.
func (h *Hub) Sweep(ctx context.Context) {
	h.mu.Lock()
	ids := make([]string, 0, len(h.socks))
	for id := range h.socks {
		ids = append(ids, id)
	}
	h.mu.Unlock()
	if len(ids) == 0 {
		return
	}
	revoked, err := h.q.ListRevokedAmong(ctx, ids)
	if err != nil {
		if ctx.Err() == nil {
			h.logger.LogAttrs(ctx, slog.LevelError, "websocket revocation sweep", slog.String("error", err.Error()))
		}
		return
	}
	for _, id := range revoked {
		h.CloseDevice(id)
	}
}
