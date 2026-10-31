package ws

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/pages"
)

// role is a participant's part in a page.
type role int

const (
	roleSender role = iota + 1
	roleAnswerer
)

// session is the in-memory state of one page's live talk (protocol section 11.5): who is
// joined and the idle timer. The page row is the only durable part. All fields are guarded by
// the hub's mu.
type session struct {
	h         *Hub
	pageID    string
	sessionID string
	sender    string
	// answerer is the answering device, once known (it joined, or Answered said so). For a
	// self page it equals sender.
	answerer string
	// members are the sockets attached to the session, by device id: joined ones, and one that
	// is partway through its join (not joined yet).
	members map[string]*conn
	// everBoth is set the first time both participants are joined together. It arms the idle
	// timer and releases the 15-minute answered-page cap.
	everBoth bool
	idle     timer
}

func newSession(h *Hub, p sqlcdb.ActivePage) *session {
	return &session{
		h: h, pageID: p.ID, sessionID: p.SessionID, sender: p.FromDevice,
		members: map[string]*conn{}, idle: timer{clk: h.clk},
	}
}

func (s *session) self() bool { return s.answerer != "" && s.answerer == s.sender }

// peerOf is the other participant's joined socket, or nil.
func (s *session) peerOf(c *conn) *conn {
	for dev, m := range s.members {
		if dev != c.deviceID && m.joined {
			return m
		}
	}
	return nil
}

func (s *session) bothJoined() bool {
	a, b := s.members[s.sender], s.members[s.answerer]
	return a != nil && b != nil && a != b && a.joined && b.joined
}

func (s *session) stopTimers() { s.idle.stop() }

// resetIdleLocked restarts the 2-minute idle timer if it is running. Task 14c calls it, with
// the hub lock held, on every floor_granted and floor_released; pings, frames and joins do not
// reset it (protocol section 11.5).
func (s *session) resetIdleLocked() {
	if s.everBoth {
		s.idle.reset(idleTimeout, func() { s.h.idleFired(s) })
	}
}

// armIdleLocked arms the idle timer when both participants are joined for the first time.
func (s *session) armIdleLocked() {
	if !s.everBoth && s.bothJoined() {
		s.everBoth = true
		s.idle.reset(idleTimeout, func() { s.h.idleFired(s) })
	}
}

// idleFired ends the session after 2 minutes without floor activity.
func (h *Hub) idleFired(s *session) {
	h.mu.Lock()
	current := h.rooms[s.pageID] == s
	h.mu.Unlock()
	if !current || h.ender == nil {
		return
	}
	if err := h.ender.EndIdle(h.ctx, s.pageID); err != nil {
		if h.ctx.Err() == nil {
			h.logger.LogAttrs(h.ctx, slog.LevelError, "end idle session", slog.String("error", err.Error()))
			// Try again soon: the session is held, so nothing else would ever end this page.
			h.mu.Lock()
			if h.rooms[s.pageID] == s && !h.closed {
				s.idle.reset(idleRetry, func() { h.idleFired(s) })
			}
			h.mu.Unlock()
		}
		return
	}
	// EndIdle reports the end to Ended itself. If the session is still here, the page was gone
	// or not answered (a row removed behind the server's back): do not leave the sockets and the
	// session behind.
	h.Ended(pages.Ending{
		PageID: s.pageID, SessionID: s.sessionID, Sender: s.sender, Answerer: s.answerer,
		Reason: pages.ReasonIdle,
	})
}

// timer is a restartable one-shot timer on the injectable clock. reset registers with the
// clock before it returns, so tests that advance a fake clock right after see it.
type timer struct {
	clk clock.Clock
	mu  sync.Mutex
	cur chan struct{} // closed to cancel the pending firing
}

func (t *timer) reset(d time.Duration, fire func()) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.cur != nil {
		close(t.cur)
	}
	stop := make(chan struct{})
	t.cur = stop
	elapsed := t.clk.After(d)
	go func() {
		select {
		case <-elapsed:
			select {
			case <-stop:
			default:
				fire()
			}
		case <-stop:
		}
	}()
}

func (t *timer) stop() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.cur != nil {
		close(t.cur)
		t.cur = nil
	}
}

// ---- join --------------------------------------------------------------------------------

// onJoin handles join {session_id} (protocol section 11.4).
func (c *conn) onJoin(sessionID string) {
	h := c.h
	ctx, cancel := context.WithTimeout(c.ctx, dbTimeout)
	defer cancel()

	if c.revokedCheck(ctx) {
		return
	}
	p, err := h.q.GetActivePageBySession(ctx, sessionID)
	if errors.Is(err, sql.ErrNoRows) {
		c.send(errorMsg{Type: "error", Code: errNotFound})
		return
	}
	if err != nil {
		h.joinFailed(ctx, c, err)
		return
	}
	r, ok := roleIn(p, c.deviceID)
	if !ok {
		c.send(errorMsg{Type: "error", Code: errNotFound})
		return
	}

	// Attach provisionally (not joined yet), so an end that commits from here on reaches this
	// socket. Progress is not offered to a socket that is still joining; it goes by FCM.
	h.mu.Lock()
	if c.sess != nil || h.closed || c.authed && h.socks[c.deviceID] != c {
		h.mu.Unlock()
		return
	}
	s := h.rooms[p.ID]
	if s == nil {
		s = newSession(h, p)
		h.rooms[p.ID] = s
	}
	s.members[c.deviceID] = c
	c.sess, c.role, c.joined = s, r, false
	h.mu.Unlock()

	// Read the page again: the snapshot must be at least as new as the attach, and a page that
	// ended before it (its end found no session to close) must not leave this one behind.
	p, err = h.q.GetActivePage(ctx, p.ID)
	if err == nil {
		if r, ok = roleIn(p, c.deviceID); !ok {
			err = sql.ErrNoRows
		}
	}
	if err != nil {
		h.mu.Lock()
		h.leaveLocked(c, s)
		h.mu.Unlock()
		if errors.Is(err, sql.ErrNoRows) {
			c.send(errorMsg{Type: "error", Code: errNotFound})
			return
		}
		h.joinFailed(ctx, c, err)
		return
	}

	h.mu.Lock()
	defer h.mu.Unlock()
	if c.sess != s { // the session ended, or this socket was replaced, meanwhile
		return
	}
	c.joined, c.everJoined, c.role = true, true, r
	if r == roleAnswerer {
		s.answerer = c.deviceID
	} else if p.AnsweredBy.Valid {
		s.answerer = p.AnsweredBy.String
	}
	c.send(pageStateMsg{Type: "page_state", PageID: p.ID, State: snapshotState(p, h.clk.Now())})
	if peer := s.peerOf(c); peer != nil {
		c.send(peerMsg{Type: "peer_joined", DeviceID: peer.deviceID})
		peer.send(peerMsg{Type: "peer_joined", DeviceID: c.deviceID})
	}
	s.armIdleLocked()
	s.floorSnapshotLocked(c) // task 14c: floor_granted while the floor is held
}

// roleIn is the device's part in the active page: the sender at any time, the answering device
// once it has answered. Any other device is not a participant; a self page is sender-only.
func roleIn(p sqlcdb.ActivePage, deviceID string) (role, bool) {
	switch {
	case p.FromDevice == deviceID:
		return roleSender, true
	case p.State == pages.StateAnswered && p.AnsweredBy.Valid && p.AnsweredBy.String == deviceID:
		return roleAnswerer, true
	}
	return 0, false
}

// snapshotState is the join acknowledgement of protocol section 11.4.
func snapshotState(p sqlcdb.ActivePage, now time.Time) string {
	switch {
	case p.State == pages.StateAnswered:
		return "answered"
	case p.State == pages.StateAcknowledged:
		return "acknowledged"
	case p.DeliveredAt.Valid:
		return "delivered"
	case now.Sub(time.UnixMilli(p.CreatedAt)) >= notDeliveredAfter:
		return "not_delivered"
	}
	return "ringing"
}

// joinFailed handles an unexpected error during a join: the socket closes 4500.
func (h *Hub) joinFailed(ctx context.Context, c *conn, err error) {
	if !errors.Is(err, context.Canceled) {
		h.logger.LogAttrs(ctx, slog.LevelError, "websocket join", slog.String("error", err.Error()))
	}
	c.closeNow(codeServerErr)
}

// leaveLocked removes a provisional or joined member without telling the peer anything
// (used by a refused join); detachLocked is the version for a socket that is going away.
func (h *Hub) leaveLocked(c *conn, s *session) {
	if c.sess != s {
		return
	}
	if s.members[c.deviceID] == c {
		delete(s.members, c.deviceID)
	}
	c.sess, c.joined = nil, false
	h.forgetEmptyLocked(s)
}

// forgetEmptyLocked drops a session nobody is attached to, unless its idle timer is running:
// that one outlives its sockets so a rejoin within the window keeps the session.
func (h *Hub) forgetEmptyLocked(s *session) {
	if len(s.members) == 0 && !s.everBoth && h.rooms[s.pageID] == s {
		delete(h.rooms, s.pageID)
		s.stopTimers()
	}
}

// detachLocked is the socket going away (closed, dropped or replaced): its participation
// ends and the peer sees peer_left. The session itself continues (protocol section 11.5).
func (h *Hub) detachLocked(c *conn) {
	s := c.sess
	if s == nil {
		return
	}
	wasJoined := c.joined
	if wasJoined {
		s.floorDetachLocked(c) // task 14c: release the floor if c held it
	}
	h.leaveLocked(c, s)
	if wasJoined {
		for _, m := range s.members {
			if m.joined {
				m.send(peerMsg{Type: "peer_left", DeviceID: c.deviceID})
			}
		}
	}
}

// drop unregisters a socket whose read loop has ended.
func (h *Hub) drop(c *conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.conns, c)
	if c.counted {
		c.counted = false
		h.unauth--
	}
	if c.authed && h.socks[c.deviceID] == c {
		delete(h.socks, c.deviceID)
	}
	h.detachLocked(c)
}

// ---- session end -------------------------------------------------------------------------

// onSessionEnd is a client's session_end: the same Cancel call as REST cancel (section 11.11).
// The sockets hear about it, this one included, through Ended once the deletion has committed.
func (c *conn) onSessionEnd() {
	h := c.h
	h.mu.Lock()
	s := c.sess
	h.mu.Unlock()
	if s == nil || h.ender == nil {
		c.closeNow(codeProtocol)
		return
	}
	ctx, cancel := context.WithTimeout(c.ctx, dbTimeout)
	defer cancel()
	if err := h.ender.Cancel(ctx, c.deviceID, s.pageID); err != nil {
		if !errors.Is(err, context.Canceled) {
			h.logger.LogAttrs(ctx, slog.LevelError, "websocket session_end", slog.String("error", err.Error()))
		}
		c.closeNow(codeServerErr)
	}
}

// Ended implements pages.Notifier: the page's row is deleted, so every attached socket hears
// session_end and is closed 1000 after it is written (protocol section 11.5), and the session's
// state is discarded. It reports who was live, which tells the page lifecycle whether FCM must
// still cover a participant.
func (h *Hub) Ended(e pages.Ending) pages.Liveness {
	h.mu.Lock()
	defer h.mu.Unlock()
	s := h.rooms[e.PageID]
	if s == nil {
		return pages.Liveness{}
	}
	now := h.clk.Now()
	lv := pages.Liveness{
		Sender:   s.members[e.Sender] != nil && s.members[e.Sender].liveLocked(now),
		Answerer: e.Answerer != "" && s.members[e.Answerer] != nil && s.members[e.Answerer].liveLocked(now),
	}
	delete(h.rooms, e.PageID)
	s.stopTimers()
	for dev, m := range s.members {
		reason := e.Reason
		if e.Initiator != "" {
			reason = "ended_by_peer"
			if dev == e.Initiator {
				reason = "ended_by_you"
			}
		}
		m.send(sessionEndMsg{Type: "session_end", Reason: reason})
		m.closeAfterFlush(codeNormal)
		m.sess, m.joined = nil, false
	}
	s.members = map[string]*conn{}
	return lv
}

// liveLocked reports whether the socket is joined and was heard from within liveWindow.
func (c *conn) liveLocked(now time.Time) bool {
	return c.joined && now.Sub(time.Unix(0, c.lastMsg.Load())) <= liveWindow
}

// Progress implements pages.Notifier.
func (h *Hub) Progress(sessionID, deviceID, pageID, state string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	c := h.socks[deviceID]
	if c == nil || c.sess == nil || c.sess.sessionID != sessionID || !c.liveLocked(h.clk.Now()) {
		return false
	}
	c.send(pageStateMsg{Type: "page_state", PageID: pageID, State: state})
	return true
}

// Answered implements pages.Notifier.
func (h *Hub) Answered(sessionID, pageID, answererID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s := h.rooms[pageID]; s != nil && s.sessionID == sessionID {
		s.answerer = answererID
	}
}

// Held implements pages.Notifier.
func (h *Hub) Held(pageID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s := h.rooms[pageID]; s != nil && s.everBoth {
		return true
	}
	// Session state is memory only: just after a restart every answered page looks unjoined
	// although its participants are about to rejoin, and the 15-minute cap must not end it first.
	// If they do rejoin, everBoth takes over; if not, the cap applies once the grace is over.
	return h.clk.Now().Before(h.started.Add(restartGrace))
}

var _ pages.Notifier = (*Hub)(nil)
