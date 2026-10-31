package ws

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
)

// Floor control and frame relay (protocol sections 11.6 and 11.7).
//
// All floor state lives on the session and is guarded by the hub's mu, like everything else a
// message handler decides on: a decision (grant, busy, release) and the control messages it
// produces are made and enqueued under one hold of mu, so decisions are serialized per session
// and each socket's ordered queue sees them in the order they were taken. Relayed frames are
// enqueued under mu too, which is what puts floor_released behind the last frame of its
// transmission. Enqueueing only takes the socket's leaf mutex; nothing under mu touches the
// database or the network.

// Floor timers of section 11.6.
const (
	silenceAfterFrame = time.Second      // no accepted frame for this long since the last one
	silenceBeforeData = 3 * time.Second  // ... or, before the first frame, since the grant
	maxTransmission   = 60 * time.Second // from the grant
)

// Release reasons (the closed set of section 11.6).
const (
	reasonReleased     = "released"
	reasonSilence      = "silence"
	reasonMaxLength    = "max_length"
	reasonDisconnected = "disconnected"
)

type floorGrantedMsg struct {
	Type           string `json:"type"`
	Talker         string `json:"talker"`
	TransmissionID string `json:"transmission_id"`
}

type floorBusyMsg struct {
	Type string `json:"type"`
}

type floorReleasedMsg struct {
	Type           string `json:"type"`
	Talker         string `json:"talker"`
	TransmissionID string `json:"transmission_id"`
	Reason         string `json:"reason"`
}

// floorState is the floor of one session. Guarded by the hub's mu.
type floorState struct {
	// talker is the socket holding the floor, nil when it is free. It is always a joined member
	// of the session: a detaching talker releases the floor first.
	talker *conn
	// txID is the current transmission id in canonical form (echoed to clients) and txRaw the
	// same as the 16 bytes a frame header carries.
	txID  string
	txRaw [audioframe.IDSize]byte
	// gen counts grants. A timer callback carries the generation it was armed for and does
	// nothing if the floor has moved on, so a firing that was already past its timer's
	// cancellation when the floor was released or re-granted is harmless.
	gen uint64
	// grantedAt and lastFrame are on the hub's clock; hasFrame is set by the first accepted
	// frame of the transmission. The silence timer is not reset per frame (50 a second): it
	// reads lastFrame when it fires and re-arms itself if a frame arrived since.
	grantedAt time.Time
	lastFrame time.Time
	hasFrame  bool

	silence, maxLen timer

	// used holds every transmission id granted in this session, for the session's whole life
	// (across rejoins, until the session ends).
	used map[[audioframe.IDSize]byte]struct{}
}

func (f *floorState) stop() {
	f.silence.stop()
	f.maxLen.stop()
}

// floorSnapshotLocked sends the joiner floor_granted when the floor is held (section 11.4,
// step 3). Called with the hub lock held, right after peer_joined.
func (s *session) floorSnapshotLocked(c *conn) {
	if f := &s.floor; f.talker != nil {
		c.send(floorGrantedMsg{Type: "floor_granted", Talker: f.talker.deviceID, TransmissionID: f.txID})
	}
}

// floorDetachLocked is called, hub lock held, when a joined socket goes away (closed, replaced
// or revoked), before it is removed from the session: if it held the floor, the peer is told
// with reason "disconnected", ahead of peer_left.
func (s *session) floorDetachLocked(c *conn) {
	if s.floor.talker == c {
		s.releaseLocked(reasonDisconnected)
	}
}

// grantLocked gives the floor to c under tx. The caller has checked everything.
func (s *session) grantLocked(c *conn, tx string, raw [audioframe.IDSize]byte) {
	f := &s.floor
	now := s.h.clk.Now()
	if f.used == nil {
		f.used = map[[audioframe.IDSize]byte]struct{}{}
	}
	f.used[raw] = struct{}{}
	f.talker, f.txID, f.txRaw = c, tx, raw
	f.gen++
	f.grantedAt, f.lastFrame, f.hasFrame = now, time.Time{}, false
	gen := f.gen
	f.silence.reset(silenceBeforeData, func() { s.h.silenceFired(s, gen) })
	f.maxLen.reset(maxTransmission, func() { s.h.maxLengthFired(s, gen) })

	msg := floorGrantedMsg{Type: "floor_granted", Talker: c.deviceID, TransmissionID: tx}
	c.send(msg)
	if peer := s.peerOf(c); peer != nil {
		peer.send(msg)
	}
	s.resetIdleLocked()
}

// releaseLocked frees the floor and tells the participants still connected: both, except for
// "disconnected", which goes to the peer only (the talker's socket is going away).
func (s *session) releaseLocked(reason string) {
	f := &s.floor
	talker := f.talker
	if talker == nil {
		return
	}
	msg := floorReleasedMsg{Type: "floor_released", Talker: talker.deviceID, TransmissionID: f.txID, Reason: reason}
	f.stop()
	f.talker = nil
	f.gen++
	if reason != reasonDisconnected {
		talker.send(msg)
	}
	if peer := s.peerOf(talker); peer != nil {
		peer.send(msg)
	}
	s.resetIdleLocked()
}

// currentFloorLocked reports whether s is still live and gen is its floor's current grant.
func (h *Hub) currentFloorLocked(s *session, gen uint64) bool {
	return h.rooms[s.pageID] == s && s.floor.talker != nil && s.floor.gen == gen
}

// silenceFired runs when the silence timer elapses: it releases the floor if the allowance
// (1 s after the last accepted frame, 3 s after the grant before the first) is used up, and
// otherwise sleeps until it would be.
func (h *Hub) silenceFired(s *session, gen uint64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.currentFloorLocked(s, gen) {
		return
	}
	f := &s.floor
	deadline := f.grantedAt.Add(silenceBeforeData)
	if f.hasFrame {
		deadline = f.lastFrame.Add(silenceAfterFrame)
	}
	if wait := deadline.Sub(h.clk.Now()); wait > 0 {
		f.silence.reset(wait, func() { h.silenceFired(s, gen) })
		return
	}
	s.releaseLocked(reasonSilence)
}

// maxLengthFired releases a transmission 60 s after its grant.
func (h *Hub) maxLengthFired(s *session, gen uint64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.currentFloorLocked(s, gen) {
		s.releaseLocked(reasonMaxLength)
	}
}

// onFloorRequest handles floor_request. The order of the checks is part of the contract: a
// revoked device is closed first, a bad id is bad_transmission_id, an absent peer (or a self
// page) is no_peer; only then is the floor considered.
func (c *conn) onFloorRequest(transmissionID string) {
	h := c.h
	ctx, cancel := context.WithTimeout(c.ctx, dbTimeout)
	defer cancel()
	if c.revokedCheck(ctx) {
		return
	}
	if !validTransmissionID(transmissionID) {
		c.send(errorMsg{Type: "error", Code: errBadTransmission})
		return
	}
	raw := uuid.MustParse(transmissionID) // validated above
	h.mu.Lock()
	defer h.mu.Unlock()
	s := c.sess
	if s == nil || !c.joined || c.isClosing() {
		return // gone, or closing: a grant to it would only be lost
	}
	// A session whose idle end is under way takes no new floor: the grant would be lost with
	// the session (see idleFired).
	if s.ending || s.self() || s.peerOf(c) == nil {
		c.send(errorMsg{Type: "error", Code: errNoPeer})
		return
	}
	f := &s.floor
	if _, used := f.used[raw]; used {
		c.send(errorMsg{Type: "error", Code: errBadTransmission})
		return
	}
	switch {
	case f.talker == c:
		// ignored (section 11.6)
	case f.talker != nil:
		c.send(floorBusyMsg{Type: "floor_busy"})
	default:
		s.grantLocked(c, transmissionID, raw)
	}
}

// onFloorRelease handles floor_release: only the current talker with the current id frees the
// floor; anything else (a stale message that crossed the server's own release) is ignored.
func (c *conn) onFloorRelease(transmissionID string) {
	h := c.h
	h.mu.Lock()
	defer h.mu.Unlock()
	if s := c.sess; s != nil && s.floor.talker == c && s.floor.txID == transmissionID {
		s.releaseLocked(reasonReleased)
	}
}

// relayFrame receives a parsed audio frame from a joined socket (section 11.7). A frame from
// the current talker under the current transmission id counts as activity for the silence timer
// and goes to the peer, byte for byte, if the peer is joined; every other frame is dropped
// silently. The ciphertext is never looked at. frame is the slice the reader allocated for
// this one message, so it can be queued as it is.
func (h *Hub) relayFrame(c *conn, hdr audioframe.Header, frame []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	s := c.sess
	if s == nil || !c.joined || s.floor.talker != c || s.floor.txRaw != hdr.TransmissionID {
		h.dropped.Add(1)
		return
	}
	fl := &s.floor
	fl.lastFrame = h.clk.Now()
	if !fl.hasFrame {
		// The 3 s allowance of the grant is over with the first frame: from here on it is 1 s
		// after the latest frame, so re-arm once (later frames only move lastFrame).
		fl.hasFrame = true
		gen := fl.gen
		fl.silence.reset(silenceAfterFrame, func() { h.silenceFired(s, gen) })
	}
	if peer := s.peerOf(c); peer != nil && peer.sendFrame(frame) {
		h.relayed.Add(1)
		return
	}
	h.dropped.Add(1)
}

// FrameCounts is the number of audio frames relayed and dropped so far, for the aggregate
// counters.
func (h *Hub) FrameCounts() (relayed, dropped uint64) { return h.relayed.Load(), h.dropped.Load() }
