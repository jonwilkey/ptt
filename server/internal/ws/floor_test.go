package ws

import (
	"bytes"
	"context"
	"encoding/binary"
	"math/rand/v2"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
	"github.com/jonwilkey/ptt/server/internal/clock"
)

// ---- helpers -------------------------------------------------------------------------

func (c *client) request(tx string) {
	c.send(map[string]any{"type": "floor_request", "transmission_id": tx})
}

func (c *client) release(tx string) {
	c.send(map[string]any{"type": "floor_release", "transmission_id": tx})
}

// frameFor builds a valid frame: header, then an opaque 40-byte body (the server never looks).
func frameFor(tx string, seq uint32, fill byte) []byte {
	id := uuid.MustParse(tx)
	b := append([]byte{audioframe.TypeLive}, id[:]...)
	b = binary.BigEndian.AppendUint32(b, seq)
	return append(b, bytes.Repeat([]byte{fill}, 40)...)
}

// recvFrame reads the next message, which must be a binary frame.
func (c *client) recvFrame() []byte {
	c.f.t.Helper()
	ctx, cancel := context.WithTimeout(c.f.t.Context(), recvTimeout)
	defer cancel()
	typ, raw, err := c.ws.Read(ctx)
	if err != nil {
		c.f.t.Fatalf("%s: read: %v", c.name, err)
	}
	if typ != websocket.MessageBinary {
		c.f.t.Fatalf("%s: got text %q, want a frame", c.name, raw)
	}
	return raw
}

// quiet proves nothing is waiting for c: a ping round trip returns the pong first. (The server
// handles a socket's messages in order, so anything it owed c earlier would arrive before it.)
func (c *client) quiet() { c.ping() }

// grant has talker request the floor under a fresh id and both sides read the grant.
func (f *fixture) grant(talker, listener *client, device string) string {
	f.t.Helper()
	tx := uuid.NewString()
	talker.request(tx)
	talker.want("floor_granted", "talker", f.ids[device], "transmission_id", tx)
	listener.want("floor_granted", "talker", f.ids[device], "transmission_id", tx)
	return tx
}

// ---- grant, busy, release ------------------------------------------------------------

func TestFloorGrantAndRelease(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	a.release(tx)
	a.want("floor_released", "talker", f.ids["A"], "transmission_id", tx, "reason", "released")
	s.want("floor_released", "talker", f.ids["A"], "transmission_id", tx, "reason", "released")
	// Releasing never ends the session, and the other side can now talk.
	tx2 := f.grant(s, a, "S1")
	s.release(tx2)
	s.want("floor_released", "reason", "released")
	a.want("floor_released", "reason", "released")
}

func TestFloorContention(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	txA, txS := uuid.NewString(), uuid.NewString()
	a.request(txA)
	s.request(txS)
	// Whichever the server processed first won; both hear one grant, the other gets busy.
	ma, ms := a.recv(), s.recv()
	if ma["type"] != "floor_granted" || ms["type"] != "floor_granted" ||
		ma["talker"] != ms["talker"] || ma["transmission_id"] != ms["transmission_id"] {
		t.Fatalf("grants differ: %v / %v", ma, ms)
	}
	loser, winner := s, a
	if ma["talker"] == f.ids["S1"] {
		loser, winner = a, s
	}
	loser.want("floor_busy")
	winner.quiet()
	loser.quiet()
}

func TestFloorBusyForTheRequesterOnly(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	f.grant(a, s, "A")
	s.request(uuid.NewString())
	s.want("floor_busy")
	a.quiet()
}

func TestBadTransmissionIDs(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	for _, bad := range []string{
		"", "nope", "6F1D6A4E-9C1B-4F5E-8A7D-2B3C4D5E6F70", // uppercase
		"6f1d6a4e-9c1b-1f5e-8a7d-2b3c4d5e6f70", // version 1
		"6f1d6a4e-9c1b-4f5e-ca7d-2b3c4d5e6f70", // variant
	} {
		a.request(bad)
		a.want("error", "code", "bad_transmission_id")
	}
	s.quiet()
}

func TestReusedTransmissionIDIsRefused(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	tx := f.grant(a, s, "A")
	a.release(tx)
	a.want("floor_released")
	s.want("floor_released")
	// Reused by the same device, and by the other one.
	a.request(tx)
	a.want("error", "code", "bad_transmission_id")
	s.request(tx)
	s.want("error", "code", "bad_transmission_id")

	// Also after a rejoin: the session (and its used ids) outlives the socket.
	_ = s.ws.CloseNow()
	a.want("peer_left")
	s2, _ := f.joined("S1", p)
	s2.want("peer_joined")
	a.want("peer_joined")
	s2.request(tx)
	s2.want("error", "code", "bad_transmission_id")
	a.request(tx)
	a.want("error", "code", "bad_transmission_id")
}

func TestTalkerRerequestIsIgnored(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	again := uuid.NewString()
	a.request(again)
	a.quiet()
	s.quiet()
	a.release(tx)
	a.want("floor_released", "transmission_id", tx)
	s.want("floor_released")
	// The ignored request's id was never granted, so it is still usable.
	a.request(again)
	a.want("floor_granted", "transmission_id", again)
}

func TestStaleOrForeignReleaseIsIgnored(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	s.release(uuid.NewString()) // nobody talks
	s.quiet()
	tx := f.grant(a, s, "A")
	s.release(tx)               // not the talker
	a.release(uuid.NewString()) // not the current id
	a.quiet()
	s.quiet()
	a.release(tx)
	a.want("floor_released", "reason", "released")
	s.want("floor_released")
	a.release(tx) // a stale repeat
	a.quiet()
}

func TestFloorRequestWithoutAPeerStaysNoPeer(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	a.request(uuid.NewString())
	a.want("error", "code", "no_peer")
	f.answer("S1", p)
	a.want("page_state")
	a.request(uuid.NewString())
	a.want("error", "code", "no_peer") // answered but the peer has not joined
}

// ---- timers --------------------------------------------------------------------------

func TestSilenceBeforeTheFirstFrameIs3Seconds(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	f.clk.Advance(2999 * time.Millisecond)
	a.quiet()
	s.quiet()
	f.clk.Advance(time.Millisecond)
	a.want("floor_released", "transmission_id", tx, "reason", "silence")
	s.want("floor_released", "transmission_id", tx, "reason", "silence")
}

func TestSilenceIsOneSecondAfterTheLastFrame(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	f.clk.Advance(2500 * time.Millisecond)
	a.sendBinary(frameFor(tx, 0, 1))
	a.quiet()
	s.recvFrame()
	// The 3 s timer fires at 3.0 s, sees a frame at 2.5 s and sleeps until 3.5 s.
	f.clk.Advance(500 * time.Millisecond)
	a.quiet()
	f.clk.Advance(499 * time.Millisecond)
	a.quiet()
	s.quiet()
	a.sendBinary(frameFor(tx, 1, 2)) // at 3.499 s: the allowance restarts
	a.quiet()
	s.recvFrame()
	f.clk.Advance(999 * time.Millisecond)
	a.quiet()
	f.clk.Advance(time.Millisecond)
	a.want("floor_released", "reason", "silence")
	s.want("floor_released", "reason", "silence")
}

func TestMaxLengthIs60SecondsFromTheGrant(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	for i := range 80 { // a frame every 750 ms keeps the silence timer quiet
		a.sendBinary(frameFor(tx, uint32(i), 7))
		a.quiet()
		s.recvFrame()
		if i%20 == 19 {
			s.ping() // 30 s without a message would time the listener out
		}
		if i < 79 {
			f.clk.Advance(750 * time.Millisecond)
		}
	}
	// 59.25 s so far: still talking.
	a.quiet()
	f.clk.Advance(750 * time.Millisecond)
	a.want("floor_released", "transmission_id", tx, "reason", "max_length")
	s.want("floor_released", "reason", "max_length")
}

func TestFramesKeepTheFloorAliveWhilePeerIsAway(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	_ = s.ws.CloseNow()
	a.want("peer_left")
	f.clk.Advance(2500 * time.Millisecond)
	a.sendBinary(frameFor(tx, 0, 1)) // counts as activity although nobody receives it
	a.quiet()
	f.clk.Advance(500 * time.Millisecond)
	a.quiet()
	f.clk.Advance(499 * time.Millisecond)
	a.quiet()
	f.clk.Advance(time.Millisecond)
	a.want("floor_released", "reason", "silence") // only the talker is left to hear it

	// A new request is no_peer while the peer is away.
	a.request(uuid.NewString())
	a.want("error", "code", "no_peer")
}

// ---- disconnect, replace, revoke -----------------------------------------------------

func TestTalkerDisconnectReleasesBeforePeerLeft(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	_ = a.ws.CloseNow()
	s.want("floor_released", "talker", f.ids["A"], "transmission_id", tx, "reason", "disconnected")
	s.want("peer_left", "device_id", f.ids["A"])
}

func TestListenerDisconnectKeepsTheFloor(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	tx := f.grant(a, s, "A")
	_ = s.ws.CloseNow()
	a.want("peer_left")
	a.quiet()
	// The rejoining peer is told who holds the floor, after page_state and peer_joined.
	s2 := f.connect("S1")
	s2.send(map[string]any{"type": "join", "session_id": p.session})
	s2.want("page_state")
	s2.want("peer_joined", "device_id", f.ids["A"])
	s2.want("floor_granted", "talker", f.ids["A"], "transmission_id", tx)
	a.want("peer_joined")
	a.sendBinary(frameFor(tx, 0, 9))
	s2.recvFrame()
}

func TestReplacedTalkerReleasesWithDisconnected(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	a2 := f.connect("A")
	a.wantClose(4409, "replaced")
	s.want("floor_released", "transmission_id", tx, "reason", "disconnected")
	s.want("peer_left", "device_id", f.ids["A"])
	_ = a2
}

func TestRevokedTalkerReleasesThenTheSessionEnds(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	tx := f.grant(a, s, "A")
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["A"])
	f.hub.CloseDevice(f.ids["A"])
	a.wantClose(4403, "revoked")
	s.want("floor_released", "transmission_id", tx, "reason", "disconnected")
	s.want("peer_left", "device_id", f.ids["A"])
	s.want("session_end", "reason", "ended_by_peer")
	if !f.pageGone(p) {
		t.Error("page not ended")
	}
}

func TestRevokedRequesterIsClosedBeforeAnyFloorDecision(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["A"])
	a.request("not even an id")
	a.wantClose(4403, "revoked")
	_ = s
}

// ---- idle timer ----------------------------------------------------------------------

func TestGrantAndReleaseResetTheIdleTimer(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.advance(110*time.Second, a, s)
	f.grant(a, s, "A")                            // resets the timer at +110 s
	f.clk.Advance(silenceBeforeData)              // the silence release at +113 s resets it again
	a.want("floor_released", "reason", "silence") // (nothing else would have kept the session)
	s.want("floor_released", "reason", "silence")
	f.advance(2*time.Minute-time.Second, a, s)
	if f.pageGone(p) {
		t.Fatal("ended early: the release did not reset the idle timer")
	}
	f.clk.Advance(time.Second)
	a.want("session_end", "reason", "idle")
	s.want("session_end", "reason", "idle")
}

func TestExplicitReleaseResetsTheIdleTimer(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.advance(100*time.Second, a, s)
	tx := f.grant(a, s, "A")
	f.clk.Advance(2 * time.Second)
	a.release(tx)
	a.want("floor_released", "reason", "released")
	s.want("floor_released")
	f.advance(2*time.Minute-time.Second, a, s)
	if f.pageGone(p) {
		t.Fatal("ended early")
	}
	f.clk.Advance(time.Second)
	a.want("session_end", "reason", "idle")
}

// gateEnder blocks EndIdle until released, to hold it "in flight".
type gateEnder struct {
	Ender
	entered chan struct{}
	proceed chan struct{}
}

func (e *gateEnder) EndIdle(ctx context.Context, pageID string) error {
	close(e.entered)
	<-e.proceed
	return e.Ender.EndIdle(ctx, pageID)
}

func TestFloorRequestWhileIdleEndIsInFlightIsRefused(t *testing.T) {
	f := newFixture(t)
	e := &gateEnder{Ender: f.svc, entered: make(chan struct{}), proceed: make(chan struct{})}
	f.hub.SetEnder(e)
	_, a, s := f.both()
	f.advance(2*time.Minute-time.Second, a, s)
	f.clk.Advance(time.Second)
	<-e.entered
	a.request(uuid.NewString())
	a.want("error", "code", "no_peer") // not granted: the session is going away
	close(e.proceed)
	a.want("session_end", "reason", "idle")
	s.want("session_end", "reason", "idle")
}

func TestStaleIdleFiringIsIgnored(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.hub.mu.Lock()
	sess := f.hub.rooms[p.id]
	stale := sess.idleGen
	f.hub.mu.Unlock()
	tx := f.grant(a, s, "A") // re-arms the idle timer
	a.release(tx)
	a.want("floor_released")
	s.want("floor_released")
	f.hub.idleFired(sess, stale) // a firing that lost the race with the activity
	if f.pageGone(p) {
		t.Fatal("a stale idle firing ended the session")
	}
	f.hub.mu.Lock()
	ending := sess.ending
	f.hub.mu.Unlock()
	if ending {
		t.Error("session marked ending by a stale firing")
	}
	a.quiet()
}

func TestTimersStopWhenTheSessionEnds(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.grant(a, s, "A")
	f.hub.mu.Lock()
	sess := f.hub.rooms[p.id]
	f.hub.mu.Unlock()
	s.send(map[string]any{"type": "session_end"})
	s.want("session_end")
	a.want("session_end")
	for name, tm := range map[string]*timer{"idle": &sess.idle, "silence": &sess.floor.silence, "max": &sess.floor.maxLen} {
		tm.mu.Lock()
		cur := tm.cur
		tm.mu.Unlock()
		if cur != nil {
			t.Errorf("%s timer still pending after the session ended", name)
		}
	}
	f.clk.Advance(2 * time.Minute) // nothing fires into the gone session
}

// ---- relay ---------------------------------------------------------------------------

func TestFramesAreRelayedByteForByteInOrder(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	r := rand.New(rand.NewPCG(1, 2))
	for i := range 120 {
		fr := frameFor(tx, uint32(i), 0)
		for j := audioframe.HeaderSize; j < len(fr); j++ {
			fr[j] = byte(r.UintN(256))
		}
		a.sendBinary(fr)
		got := s.recvFrame()
		if !bytes.Equal(got, fr) {
			t.Fatalf("frame %d differs", i)
		}
		if i%20 == 19 {
			a.quiet() // frames never echo back to the talker
		}
	}
	// And the release follows the last frame.
	a.release(tx)
	a.want("floor_released")
	s.want("floor_released")
}

func TestFramesThatAreNotTheTalkersCurrentTransmissionAreDropped(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	stranger := uuid.NewString()
	a.sendBinary(frameFor(stranger, 0, 1)) // no floor yet
	a.quiet()
	tx := f.grant(a, s, "A")
	s.sendBinary(frameFor(tx, 0, 2))       // the listener may not send
	a.sendBinary(frameFor(stranger, 0, 3)) // another transmission
	a.sendBinary(frameFor(tx, 0, 4))       // the real one
	if got := s.recvFrame(); got[len(got)-1] != 4 {
		t.Fatalf("first relayed frame is %d, want 4: a dropped frame got through", got[len(got)-1])
	}
	a.quiet() // nothing was echoed to the talker
	// After the release the same id is stale.
	a.release(tx)
	a.want("floor_released")
	s.want("floor_released")
	a.sendBinary(frameFor(tx, 1, 5))
	a.quiet()
	s.quiet()
}

func TestMalformedFrameClosesTheSocket(t *testing.T) {
	f := newFixture(t)
	_, a, _ := f.both()
	a.sendBinary([]byte{1, 2, 3})
	a.wantClose(4400, "protocol_error")
}

// ---- the queue -----------------------------------------------------------------------

// queueSession is a session of two joined sockets with no network, to drive the relay directly.
func queueSession(t *testing.T) (*Hub, *session, *conn, *conn) {
	t.Helper()
	h := New(Options{Clock: clock.NewFake(t0)})
	s := &session{
		h: h, pageID: "p", members: map[string]*conn{}, idle: timer{clk: h.clk},
		floor:  floorState{silence: timer{clk: h.clk}, maxLen: timer{clk: h.clk}},
		sender: "a", answerer: "b",
	}
	h.rooms["p"] = s
	mk := func(dev string) *conn {
		c := newQueueConn()
		c.h, c.deviceID, c.sess, c.joined = h, dev, s, true
		s.members[dev] = c
		return c
	}
	return h, s, mk("a"), mk("b")
}

func TestRelayDropsPast25FramesButControlStillArrivesInOrder(t *testing.T) {
	h, s, a, b := queueSession(t)
	tx := uuid.NewString()
	h.mu.Lock()
	s.grantLocked(a, tx, uuid.MustParse(tx))
	h.mu.Unlock()
	for i := range 40 {
		h.relayFrame(a, audioframe.Header{TransmissionID: s.floor.txRaw}, frameFor(tx, uint32(i), byte(i)))
	}
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	h.mu.Unlock()
	q, closing, _ := b.snapshot()
	if closing {
		t.Fatal("peer closed")
	}
	if len(q) != 1+25+1 {
		t.Fatalf("peer queue holds %d entries, want grant + 25 frames + release", len(q))
	}
	if q[0][0] != '{' || q[26][0] != '{' {
		t.Fatalf("control messages are not at the ends: %q ... %q", q[0], q[26])
	}
	for i := 1; i <= 25; i++ { // the first 25 frames were kept, in order
		if q[i][len(q[i])-1] != byte(i-1) {
			t.Fatalf("queue slot %d holds frame %d", i, q[i][len(q[i])-1])
		}
	}
	if r, d := h.FrameCounts(); r != 25 || d != 15 {
		t.Errorf("relayed/dropped = %d/%d, want 25/15", r, d)
	}
}

func TestGrantWithRacingRequestsStaysConsistent(t *testing.T) {
	// Many requests at once through the hub's lock: exactly one wins, whoever it is.
	f := newFixture(t)
	_, a, s := f.both()
	var wg sync.WaitGroup
	for _, c := range []*client{a, s} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 5 {
				c.request(uuid.NewString())
			}
		}()
	}
	wg.Wait()
	grants := 0
	// Drain: a ping on each side returns after everything owed to it.
	for _, c := range []*client{a, s} {
		c.send(map[string]any{"type": "ping", "t": 1})
		for {
			m := c.recv()
			if m["type"] == "pong" {
				break
			}
			if m["type"] == "floor_granted" {
				grants++
			}
		}
	}
	if grants != 2 { // one grant, seen by both
		t.Errorf("saw %d floor_granted messages, want 2 (one grant to both)", grants)
	}
}

func TestSilenceAfterAnEarlyFirstFrameIsOneSecondFromThatFrame(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.both()
	tx := f.grant(a, s, "A")
	f.clk.Advance(100 * time.Millisecond)
	a.sendBinary(frameFor(tx, 0, 1))
	a.quiet()
	s.recvFrame()
	f.clk.Advance(999 * time.Millisecond) // 1.099 s: not yet
	a.quiet()
	s.quiet()
	f.clk.Advance(time.Millisecond) // 1.1 s: one second after the frame, well before grant + 3 s
	a.want("floor_released", "reason", "silence")
	s.want("floor_released", "reason", "silence")
}

func TestShutdownWithTheFloorHeldArmsNoIdleTimer(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.grant(a, s, "A")
	f.hub.mu.Lock()
	sess := f.hub.rooms[p.id]
	f.hub.mu.Unlock()
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	f.hub.Shutdown(ctx) // closes the sockets; the talker's drop releases the floor
	a.wantClose(4503, "server_restarting")
	s.wantClose(4503, "server_restarting")
	sess.idle.mu.Lock()
	cur := sess.idle.cur
	sess.idle.mu.Unlock()
	if cur != nil {
		t.Error("an idle timer is pending after Shutdown")
	}
}
