package ws

import (
	"context"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
)

// This file holds the seams where floor control and frame relay (protocol sections 11.6 and
// 11.7, task 14c) attach. 14b implements none of it: no floor is ever granted, so a talker
// cannot exist and nothing is relayed.

// 14c adds a floorState field to session (who holds the floor and under which transmission
// id, the ids already granted, the silence and max-length timers).

// floorSnapshotLocked sends the joiner floor_granted when the floor is held (section 11.4,
// step 3). Called with the hub lock held, right after peer_joined. (14c.)
func (s *session) floorSnapshotLocked(*conn) {}

// floorDetachLocked is called, hub lock held, when a joined socket goes away: if it held the
// floor, release it with reason "disconnected" to the peer. (14c.)
func (s *session) floorDetachLocked(*conn) {}

// onFloorRequest handles floor_request. In 14b only the refusals that need no floor are
// answered: a revoked device is closed, a malformed id is bad_transmission_id and an absent
// peer (or a self page) is no_peer. With a peer present the request is not served until 14c
// (it grants the floor, or answers floor_busy, and must call resetIdleLocked).
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
	h.mu.Lock()
	defer h.mu.Unlock()
	s := c.sess
	if s == nil {
		return
	}
	if s.self() || s.peerOf(c) == nil {
		c.send(errorMsg{Type: "error", Code: errNoPeer})
		return
	}
	// 14c: grant the floor or answer floor_busy.
}

// onFloorRelease handles floor_release. Without a floor every release is "from anyone else or
// with another id", which is ignored silently (section 11.6). (14c.)
func (c *conn) onFloorRelease(string) {}

// relayFrame receives a parsed audio frame from a joined socket. 14c relays it to the peer
// if hdr names the current transmission of the current talker (section 11.7) and otherwise
// drops it silently; in 14b every frame is dropped.
func (h *Hub) relayFrame(*conn, audioframe.Header, []byte) {}
