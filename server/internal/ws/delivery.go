package ws

import (
	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
)

// Delivery of protocol-2 transmissions (protocol sections 13.3 to 13.6): accepting the talker's
// frames into the buffer, live relay or gap toward the listener, clip replay, heard, and the
// open state with its resume window. Everything runs with the hub's mu held and enqueues on the
// sockets' ordered queues, like floor.go.

type listenerGapMsg struct {
	Type           string `json:"type"`
	TransmissionID string `json:"transmission_id"`
}

type clipMsg struct {
	Type           string `json:"type"`
	Talker         string `json:"talker"`
	TransmissionID string `json:"transmission_id"`
	LastSeq        int    `json:"last_seq"`
}

// joinedMember is dev's joined socket in the session, or nil.
func (s *session) joinedMember(dev string) *conn {
	if m := s.members[dev]; m != nil && m.joined {
		return m
	}
	return nil
}

// otherOf is the participant who is not dev: a transmission's listener.
func (s *session) otherOf(dev string) string {
	if dev == s.sender {
		return s.answerer
	}
	return s.sender
}

// acceptFrameLocked takes a frame from t's talker (section 13.3, Accepting a frame).
func (s *session) acceptFrameLocked(t *transmission, seq int, frame []byte) {
	h := s.h
	if seq != t.next || seq >= maxTxFrames {
		h.dropped.Add(1)
		return
	}
	if !t.unbuffered && !s.buf.hold(t, frame) {
		// Nothing evictable is left: closed as if it had reached 3000 frames.
		h.dropped.Add(1)
		s.closeTxLocked(t)
		return
	}
	t.next++
	if t.state == txLive {
		s.floorFrameLocked()
	} else {
		t.windowFrom = h.clk.Now()
	}

	l := s.joinedMember(t.listener)
	switch {
	case t.unbuffered || l != nil && l.protocol < 2:
		// Relayed as section 11.7 (section 13.1); never gapped, sent not tracked.
		if l != nil && l.sendFrame(frame) {
			h.relayed.Add(1)
		} else {
			h.dropped.Add(1)
		}
	case l != nil && t.state == txLive && !t.gapped && seq == t.sent+1 && s.cursorOf(t.listener) == t && l.sendFrame(frame):
		t.sent = seq
		h.relayed.Add(1)
	default:
		t.gapped = true
		s.gapLocked(t)
	}
	if t.next == maxTxFrames {
		s.closeTxLocked(t)
	}
}

// cursorOf is the listener's cursor (section 13.4): its first held transmission, in grant
// order, that is unfinished or not yet queued up to its last frame. Unbuffered ones are not
// delivered from the buffer and are skipped.
func (s *session) cursorOf(listener string) *transmission {
	for _, t := range s.buf.txs {
		if t.listener == listener && !t.unbuffered && (t.state != txClosed || t.sent < t.last()) {
			return t
		}
	}
	return nil
}

// gapLocked sends listener_gap to t's talker, once, when section 13.4 allows it: not for a
// clip or unbuffered transmission, and only while the listener's last joined socket in the
// session was protocol 2 or it has not joined yet, and only to a protocol-2 talker socket
// (section 11.1: protocol-2 messages never go to a protocol-1 socket).
func (s *session) gapLocked(t *transmission) {
	if t.gapSent || t.clip || t.unbuffered || s.lastProto[t.listener] == 1 {
		return
	}
	if c := s.joinedMember(t.talker); c != nil && c.protocol >= 2 {
		t.gapSent = true
		c.send(listenerGapMsg{Type: "listener_gap", TransmissionID: t.idStr})
		s.h.clips.listenerGaps.Add(1)
	}
}

// openTxLocked is t's floor released by the server (silence, max_length, disconnected): the
// transmission stays open for its talker to finish, for the resume window.
func (s *session) openTxLocked(t *transmission) {
	h := s.h
	t.state = txOpen
	t.windowFrom = h.clk.Now()
	if h.closed {
		return // shutting down: the buffers are gone, no timer behind Shutdown
	}
	t.window.clk = h.clk
	t.window.reset(resumeWindow, func() { h.windowFired(s, t) })
}

// windowFired closes an open transmission 30 s after the later of entering open, its last
// accepted frame and its last accepted resume (rule 4 of section 13.3).
func (h *Hub) windowFired(s *session, t *transmission) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.rooms[s.pageID] != s || t.state != txOpen || s.buf.find(t.id) != t {
		return
	}
	if wait := t.windowFrom.Add(resumeWindow).Sub(h.clk.Now()); wait > 0 {
		t.window.reset(wait, func() { h.windowFired(s, t) })
		return
	}
	s.closeTxLocked(t)
}

// closeTxLocked closes a live or open transmission. A live one releases the floor with
// released to both; an open one tells its talker released, if joined (section 13.3).
func (s *session) closeTxLocked(t *transmission) {
	switch t.state {
	case txLive:
		s.releaseLocked(reasonReleased) // t is the floor's transmission; this finishes it
	case txOpen:
		s.resetIdleLocked() // every floor_released does (section 11.5)
		if c := s.joinedMember(t.talker); c != nil {
			c.send(floorReleasedMsg{Type: "floor_released", Talker: t.talker, TransmissionID: t.idStr, Reason: reasonReleased})
		}
		s.finishTxLocked(t)
	}
}

// finishTxLocked marks t closed and then either removes it (section 13.3, Freeing) or starts
// its replay if the listener is joined on protocol 2.
func (s *session) finishTxLocked(t *transmission) {
	t.state = txClosed
	t.window.stop()
	l := s.joinedMember(t.listener)
	if t.unbuffered || l != nil && l.protocol < 2 || t.heard >= t.last() {
		s.buf.remove(t)
		return
	}
	if l != nil {
		s.pumpLocked(l)
	}
}

// closeOpenOfLocked closes dev's open transmission, if any: its next floor_request does
// (rule 2 of section 13.3), whatever the answer.
func (s *session) closeOpenOfLocked(dev string) {
	for _, t := range s.buf.txs {
		if t.talker == dev && t.state == txOpen {
			s.closeTxLocked(t)
			return
		}
	}
}

// pumpLocked replays the listener's closed transmissions from its cursor (section 13.4):
// clip, then the frames sent+1 ... last as received, while fewer than 25 frames are queued.
// The writer calls it again (topUp) as the socket drains.
func (s *session) pumpLocked(l *conn) {
	for _, t := range s.buf.txs {
		if t.listener != l.deviceID || t.unbuffered {
			continue
		}
		if t.state != txClosed {
			return // the cursor is unfinished: live relay or a clip after it closes
		}
		if t.sent >= t.last() {
			continue
		}
		if !t.clipQueued {
			t.clipQueued = true
			l.send(clipMsg{Type: "clip", Talker: t.talker, TransmissionID: t.idStr, LastSeq: t.last()})
			if !t.clipped {
				t.clipped = true
				s.h.clips.clipTransmissions.Add(1)
			}
			s.resetIdleLocked()
		}
		for t.sent < t.last() {
			if !l.sendFrame(t.frames[t.sent+1]) {
				return
			}
			t.sent++
		}
	}
}

// topUp continues a replay to c after its writer took a frame off the queue.
func (h *Hub) topUp(c *conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if s := c.sess; s != nil && c.joined && c.protocol >= 2 {
		s.pumpLocked(c)
	}
}

// listenerJoinedLocked runs after c's join snapshot: every transmission held for c's device
// restarts from heard on the new socket; a protocol-1 socket removes the closed ones unplayed
// and unbuffers the unfinished one (sections 13.1, 13.3); a protocol-2 socket gets its clips.
func (s *session) listenerJoinedLocked(c *conn) {
	s.lastProto[c.deviceID] = c.protocol
	for _, t := range append([]*transmission(nil), s.buf.txs...) {
		if t.listener != c.deviceID {
			continue
		}
		t.sent, t.clipQueued = t.heard, false
		switch {
		case c.protocol >= 2 || t.unbuffered:
		case t.state == txClosed:
			s.buf.remove(t)
		default:
			s.buf.unbuffer(t)
		}
	}
	if c.protocol >= 2 {
		s.pumpLocked(c)
	}
}

// listenerLeftLocked runs when dev's joined socket goes: nothing is in replay to it any more.
func (s *session) listenerLeftLocked(dev string) {
	for _, t := range s.buf.txs {
		if t.listener == dev {
			t.clipQueued = false
		}
	}
}

// onHeard handles heard {transmission_id, last_seq} (section 13.5). It is never forwarded.
func (c *conn) onHeard(transmissionID string, lastSeq int64) {
	h := c.h
	id, err := uuid.Parse(transmissionID)
	if err != nil {
		return // canonical per the parser; cannot happen
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	s := c.sess
	if s == nil || !c.joined || c.protocol < 2 {
		return
	}
	t := s.buf.find(id)
	if t == nil || t.listener != c.deviceID || t.unbuffered {
		return
	}
	s.buf.hear(t, min(int(lastSeq), t.last()))
	if t.state == txClosed && t.heard >= t.last() {
		s.buf.remove(t)
	}
	s.pumpLocked(c)
}

// findOwnLocked is dev's held transmission with id in the state wanted, or nil.
func (s *session) findOwnLocked(dev string, id [audioframe.IDSize]byte, state txState) *transmission {
	if t := s.buf.find(id); t != nil && t.talker == dev && t.state == state {
		return t
	}
	return nil
}
