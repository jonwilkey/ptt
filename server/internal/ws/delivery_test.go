package ws

import (
	"encoding/json"
	"fmt"
	"runtime"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/pages"
)

// p2Session is queueSession with both sockets on protocol 2: a is the sender, b the answerer.
func p2Session(t *testing.T) (*Hub, *session, *conn, *conn) {
	t.Helper()
	h, s, a, b := queueSession(t)
	a.protocol, b.protocol = 2, 2
	s.lastProto["a"], s.lastProto["b"] = 2, 2
	return h, s, a, b
}

func grantP2(h *Hub, s *session, c *conn, clip bool) string {
	tx := uuid.NewString()
	h.mu.Lock()
	s.grantLocked(c, tx, uuid.MustParse(tx), clip)
	h.mu.Unlock()
	return tx
}

// frames sends seqs from..to (inclusive) of tx from c; a frame's last byte is its seq mod 256.
func frames(h *Hub, c *conn, tx string, from, to int) {
	for seq := from; seq <= to; seq++ {
		h.relayFrame(c, audioframe.Header{TransmissionID: uuid.MustParse(tx), Seq: uint32(seq)}, frameFor(tx, uint32(seq), byte(seq)))
	}
}

// drain plays c's writer: it takes every entry off the queue in order, topping the replay up
// after each frame as the writer does, and returns them as labels: "f<seq>" for a frame,
// the type for a message, with ":<reason>" or ":<last_seq>" where there is one.
func drain(c *conn) []string {
	var got []string
	for {
		c.qmu.Lock()
		if len(c.queue) == 0 {
			c.qmu.Unlock()
			return got
		}
		o := c.queue[0]
		c.queue = c.queue[1:]
		if o.frame {
			c.frames--
		}
		c.qmu.Unlock()
		if o.binary {
			hdr, _, err := audioframe.Parse(o.data)
			if err != nil {
				panic(err)
			}
			got = append(got, fmt.Sprintf("f%d", hdr.Seq))
			if c.h != nil {
				c.h.topUp(c)
			}
			continue
		}
		var m map[string]any
		_ = json.Unmarshal(o.data, &m)
		label := fmt.Sprint(m["type"])
		if r, ok := m["reason"]; ok {
			label += fmt.Sprintf(":%v", r)
		}
		if n, ok := m["last_seq"]; ok {
			label += fmt.Sprintf(":%v", n)
		}
		got = append(got, label)
	}
}

func seqs(prefix []string, from, to int, suffix ...string) []string {
	out := slices.Clone(prefix)
	for i := from; i <= to; i++ {
		out = append(out, fmt.Sprintf("f%d", i))
	}
	return append(out, suffix...)
}

func wantQueue(t *testing.T, who string, got, want []string) {
	t.Helper()
	if !slices.Equal(got, want) {
		t.Fatalf("%s got\n %v\nwant\n %v", who, got, want)
	}
}

func (h *Hub) held(s *session, tx string) *transmission {
	h.mu.Lock()
	defer h.mu.Unlock()
	return s.buf.find(uuid.MustParse(tx))
}

// held2 is held for a caller that already holds the lock.
func (h *Hub) held2(s *session, tx string) *transmission {
	return s.buf.find(uuid.MustParse(tx))
}

func (h *Hub) stateOf(s *session, tx string) txState {
	h.mu.Lock()
	defer h.mu.Unlock()
	return s.buf.find(uuid.MustParse(tx)).state
}

// rejoin replaces c's socket by a fresh one on protocol: the old one detaches, the new one joins
// with the snapshot already sent.
func rejoin(h *Hub, s *session, c *conn, protocol int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c.joined = false
	s.listenerLeftLocked(c.deviceID)
	c.qmu.Lock()
	c.queue, c.frames = nil, 0
	c.qmu.Unlock()
	c.protocol, c.joined = protocol, true
	s.listenerJoinedLocked(c)
}

func away(h *Hub, s *session, c *conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	c.joined = false
	s.listenerLeftLocked(c.deviceID)
}

func TestLiveRelayTracksSentAndAFullyHeardTransmissionIsRemoved(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 4)
	a.onFloorRelease(tx)
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_granted"}, 0, 4, "floor_released:released"))
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:released"})
	held := h.held(s, tx)
	if held == nil || held.state != txClosed || held.sent != 4 || held.gapped {
		t.Fatalf("state %d sent %d gapped %v", held.state, held.sent, held.gapped)
	}
	if r, d := h.FrameCounts(); r != 5 || d != 0 {
		t.Errorf("relayed/dropped = %d/%d, want 5/0", r, d)
	}
	b.onHeard(tx, 2)
	if held.heard != 2 || held.frames[2] != nil || held.frames[3] == nil {
		t.Fatal("heard 2 did not free frames 0-2")
	}
	b.onHeard(tx, 4095) // bounded by next-1
	if h.held(s, tx) != nil || h.ClipCounts().BufferBytes != 0 {
		t.Fatalf("a closed, fully heard transmission must be removed (bytes %d)", h.ClipCounts().BufferBytes)
	}
	if c := h.ClipCounts(); c.ClipTransmissions != 0 || c.ListenerGaps != 0 || c.Transmissions != 1 {
		t.Errorf("counts = %+v", c)
	}
	wantQueue(t, "talker after heard", drain(a), nil) // heard is never forwarded
}

func TestQueueOverflowGapsTheTransmissionAndTheRestArrivesAsAClip(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 29) // the listener's writer is stalled: 25 frames fit
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "listener_gap"})
	frames(h, a, tx, 30, 31)
	if got := drain(b); !slices.Equal(got, seqs([]string{"floor_granted"}, 0, 24)) {
		t.Fatalf("listener got %v", got)
	}
	frames(h, a, tx, 32, 33) // queue has room again, but live relay stopped for good
	wantQueue(t, "listener while gapped", drain(b), nil)
	a.onFloorRelease(tx)
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_released:released", "clip:33"}, 25, 33))
	wantQueue(t, "talker", drain(a), []string{"floor_released:released"})
	c := h.ClipCounts()
	if c.ListenerGaps != 1 || c.ClipTransmissions != 1 {
		t.Errorf("counts = %+v", c)
	}
	if r, d := h.FrameCounts(); r != 25 || d != 0 {
		t.Errorf("relayed/dropped = %d/%d: buffered and replayed frames count in neither", r, d)
	}
}

func TestClipReplayIsPacedAt25QueuedFrames(t *testing.T) {
	h, s, a, b := p2Session(t)
	away(h, s, b)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 99)
	a.onFloorRelease(tx)
	rejoin(h, s, b, 2)
	b.qmu.Lock()
	queued, n := len(b.queue), b.frames
	b.qmu.Unlock()
	if queued != 26 || n != 25 {
		t.Fatalf("queued %d entries (%d frames), want clip + 25 frames", queued, n)
	}
	wantQueue(t, "listener", drain(b), seqs([]string{"clip:99"}, 0, 99))
}

func TestClipTransmissionIsNeverRelayedLiveAndGetsNoGap(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, true)
	frames(h, a, tx, 0, 9)
	wantQueue(t, "listener", drain(b), []string{"floor_granted"})
	a.onFloorRelease(tx)
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_released:released", "clip:9"}, 0, 9))
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:released"})
}

func TestGrantWithTheListenerAwaySendsTheGapAtOnce(t *testing.T) {
	for _, c := range []struct {
		name      string
		lastProto int64 // 0: never joined
		wantGap   bool
	}{
		{"never joined", 0, true},
		{"last on protocol 2", 2, true},
		{"last on protocol 1", 1, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			h, s, a, b := p2Session(t)
			away(h, s, b)
			if c.lastProto == 0 {
				delete(s.lastProto, "b")
			} else {
				s.lastProto["b"] = c.lastProto
			}
			tx := grantP2(h, s, a, false)
			frames(h, a, tx, 0, 2)
			want := []string{"floor_granted"}
			if c.wantGap {
				want = append(want, "listener_gap")
			}
			wantQueue(t, "talker", drain(a), want)
			if !h.held(s, tx).gapped {
				t.Fatal("not gapped from the grant")
			}
		})
	}
}

func TestRejoinOrderIsSnapshotThenClipsInGrantOrderThenTheLiveOneAsAClip(t *testing.T) {
	// The example of section 13.4: T1 and T2 closed and unheard, T3 live and gapped.
	h, s, a, b := p2Session(t)
	away(h, s, b)
	t1 := grantP2(h, s, a, false)
	frames(h, a, t1, 0, 2)
	a.onFloorRelease(t1)
	t2 := grantP2(h, s, a, false)
	frames(h, a, t2, 0, 1)
	a.onFloorRelease(t2)
	t3 := grantP2(h, s, a, false)
	frames(h, a, t3, 0, 3)
	h.mu.Lock()
	b.joined, b.protocol = true, 2
	s.floorSnapshotLocked(b)
	s.listenerJoinedLocked(b)
	h.mu.Unlock()
	frames(h, a, t3, 4, 4)
	a.onFloorRelease(t3)
	want := seqs([]string{"floor_granted"}, 0, 2, "clip:1")
	want = seqs(slices.Insert(want, 1, "clip:2"), 0, 1, "floor_released:released", "clip:4")
	want = seqs(want, 0, 4)
	wantQueue(t, "listener", drain(b), want)
}

func TestAPartlyHeardClipResumesFromHeardOnTheNextSocket(t *testing.T) {
	h, s, a, b := p2Session(t)
	away(h, s, b)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 39)
	a.onFloorRelease(tx)
	rejoin(h, s, b, 2) // clip + 25 frames queued
	b.onHeard(tx, 9)
	if got := h.held(s, tx); got.inReplay() != true || got.sent != 24 {
		t.Fatalf("in replay = %v, sent %d", got.inReplay(), got.sent)
	}
	rejoin(h, s, b, 2)
	wantQueue(t, "listener", drain(b), seqs([]string{"clip:39"}, 10, 39))
	// A heard for frames accepted on an earlier socket raises sent: nothing is queued twice.
	// The new socket's queue is full, so only the clip is queued at the join.
	h.mu.Lock()
	b.joined = false
	s.listenerLeftLocked("b")
	b.qmu.Lock()
	b.queue, b.frames = nil, 0
	for range maxQueuedFrames {
		b.queue = append(b.queue, out{data: frameFor(tx, 0, 0), binary: true, frame: true})
	}
	b.frames = maxQueuedFrames
	b.qmu.Unlock()
	b.joined = true
	s.listenerJoinedLocked(b)
	h.mu.Unlock()
	b.onHeard(tx, 30)
	if got := h.held(s, tx); got.sent != 30 || got.heard != 30 {
		t.Fatalf("sent %d heard %d, want 30/30", got.sent, got.heard)
	}
	want := slices.Repeat([]string{"f0"}, maxQueuedFrames)
	wantQueue(t, "listener", drain(b), seqs(append(want, "clip:39"), 31, 39))
}

func TestHeardIsIgnoredFromTheTalkerAndForUnknownIDs(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 4)
	a.onHeard(tx, 4) // the talker's own transmission
	b.onHeard(uuid.NewString(), 4)
	if got := h.held(s, tx); got.heard != -1 || got.frames[0] == nil {
		t.Fatal("a foreign heard changed the transmission")
	}
}

func TestReaching3000FramesWhileLiveReleasesTheFloorWithReleased(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	var heard []string
	for from := 0; from < maxTxFrames; from += 20 {
		frames(h, a, tx, from, from+19)
		heard = drain(b)
	}
	if len(heard) == 0 || heard[len(heard)-1] != "floor_released:released" {
		t.Fatalf("listener's last messages %v, want floor_released released", heard)
	}
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:released"})
	if got := h.held(s, tx); got == nil || got.state != txClosed || got.next != maxTxFrames || got.sent != 2999 {
		t.Fatalf("state %d next %d sent %d", got.state, got.next, got.sent)
	}
	_, before := h.FrameCounts()
	frames(h, a, tx, 3000, 3000)
	if _, d := h.FrameCounts(); d != before+1 {
		t.Fatal("a frame past 2999 was not dropped")
	}
	h.mu.Lock()
	free := s.floor.talker == nil
	h.mu.Unlock()
	if !free {
		t.Fatal("the floor is still held")
	}
}

func TestFramesOutOfSeqOrderAreDropped(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 1)
	frames(h, a, tx, 1, 1) // again
	frames(h, a, tx, 3, 3) // a gap
	frames(h, a, tx, 2, 2)
	if got := h.held(s, tx); got.next != 3 {
		t.Fatalf("next = %d, want 3", got.next)
	}
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_granted"}, 0, 2))
	if _, d := h.FrameCounts(); d != 2 {
		t.Errorf("dropped = %d, want 2", d)
	}
}

func TestServerReleaseLeavesItOpenAndTheTalkerFinishesIt(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 2)
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	h.mu.Unlock()
	if got := h.held(s, tx); got.state != txOpen {
		t.Fatalf("state %d, want open", got.state)
	}
	frames(h, a, tx, 3, 5) // accepted while open, buffered, not relayed
	if got := h.held(s, tx); got.next != 6 || !got.gapped {
		t.Fatalf("next %d gapped %v", got.next, got.gapped)
	}
	a.onFloorRelease(uuid.NewString()) // not its transmission: ignored
	a.onFloorRelease(tx)
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:silence", "listener_gap", "floor_released:released"})
	wantQueue(t, "listener", drain(b), seqs(seqs([]string{"floor_granted"}, 0, 2, "floor_released:silence", "clip:5"), 3, 5))
}

func TestOpenTransmissionClosesWhenTheResumeWindowRunsOut(t *testing.T) {
	h, s, a, b := p2Session(t)
	clk, ok := h.clk.(*clock.Fake)
	if !ok {
		t.Fatal("not a fake clock")
	}
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 0)
	h.mu.Lock()
	s.releaseLocked(reasonMaxLength)
	h.mu.Unlock()
	clk.Advance(20 * time.Second)
	frames(h, a, tx, 1, 1) // a frame restarts the window
	clk.Advance(20 * time.Second)
	// The window's first firing (at 30 s) found the frame and slept again: two waiters, the
	// stopped max_length timer and the re-armed window.
	clk.BlockUntil(2)
	if h.stateOf(s, tx) != txOpen {
		t.Fatal("closed 40 s after open although a frame came at 20 s")
	}
	clk.Advance(10 * time.Second)
	waitFor(t, func() bool { return h.stateOf(s, tx) == txClosed })
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:max_length", "listener_gap", "floor_released:released"})
	wantQueue(t, "listener", drain(b), []string{"floor_granted", "f0", "floor_released:max_length", "clip:1", "f1"})
}

func TestCapFallbackClosesTheLiveTransmission(t *testing.T) {
	h, s, a, b := p2Session(t)
	h.mu.Lock()
	// b's open transmission fills the session: nothing is evictable.
	other := uuid.New()
	ot := s.buf.grant(other, other.String(), "b", "a", false)
	ot.state = txOpen
	s.buf.hold(ot, big[:sessionCapBytes-2*txOverhead-7*61])
	ot.next++
	h.mu.Unlock()
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 7) // a frame is 61 bytes: seven fit
	if got := h.held(s, tx); got.state != txClosed || got.next != 7 {
		t.Fatalf("state %d next %d sent %d", got.state, got.next, got.sent)
	}
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:released"})
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_granted"}, 0, 6, "floor_released:released"))
	if h.ClipCounts().BufferBytes != sessionCapBytes {
		t.Errorf("charged %d, want exactly the cap", h.ClipCounts().BufferBytes)
	}
}

func TestProtocol1ListenerJoinRemovesClosedAndUnbuffersTheUnfinished(t *testing.T) {
	h, s, a, b := p2Session(t)
	away(h, s, b)
	t1 := grantP2(h, s, a, false)
	frames(h, a, t1, 0, 4)
	a.onFloorRelease(t1)
	t2 := grantP2(h, s, a, false)
	frames(h, a, t2, 0, 4)
	rejoin(h, s, b, 1)
	if h.held(s, t1) != nil {
		t.Fatal("a closed transmission survived a protocol-1 join")
	}
	u := h.held(s, t2)
	if u == nil || !u.unbuffered || u.frames != nil || h.ClipCounts().BufferBytes != txOverhead {
		t.Fatalf("unbuffered %v, bytes %d", u != nil && u.unbuffered, h.ClipCounts().BufferBytes)
	}
	frames(h, a, t2, 5, 7) // relayed as section 11.7, not held
	b.onHeard(t2, 7)       // frees nothing, changes nothing
	// Back on protocol 2 it stays unbuffered: relayed, never replayed.
	rejoin(h, s, b, 2)
	frames(h, a, t2, 8, 8)
	if u.next != 9 || u.heard != -1 {
		t.Fatalf("next %d heard %d", u.next, u.heard)
	}
	a.onFloorRelease(t2)
	wantQueue(t, "listener", drain(b), []string{"f8", "floor_released:released"})
	if h.held(s, t2) != nil || h.ClipCounts().BufferBytes != 0 {
		t.Fatal("an unbuffered transmission must be removed when it closes")
	}
	if c := h.ClipCounts(); c.ListenerGaps != 2 {
		t.Errorf("gaps = %d: one per transmission while the listener was away on protocol 2", c.ListenerGaps)
	}
}

func TestProtocol1ListenerGetsMilestone4RelayAndTheTransmissionGoesAtClose(t *testing.T) {
	h, s, a, b := p2Session(t)
	b.protocol, s.lastProto["b"] = 1, 1
	tx := grantP2(h, s, a, true) // the clip flag is ignored toward protocol 1
	frames(h, a, tx, 0, 29)
	if got := h.held(s, tx); got.sent != -1 || len(got.frames) != 30 {
		t.Fatalf("sent %d, %d frames held", got.sent, len(got.frames))
	}
	a.onFloorRelease(tx)
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_granted"}, 0, 24, "floor_released:released"))
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:released"})
	if h.held(s, tx) != nil || h.ClipCounts().BufferBytes != 0 {
		t.Fatal("freed at close toward a protocol-1 listener")
	}
	if r, d := h.FrameCounts(); r != 25 || d != 5 {
		t.Errorf("relayed/dropped = %d/%d, want 25/5 as milestone 4", r, d)
	}
}

func TestProtocol1TalkerIsNotBuffered(t *testing.T) {
	h, s, a, b := queueSession(t)
	b.protocol = 2
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 2)
	if h.held(s, tx) != nil || h.ClipCounts().BufferBytes != 0 {
		t.Fatal("a protocol-1 grant was buffered")
	}
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_granted"}, 0, 2))
}

func TestSessionEndFreesEveryBuffer(t *testing.T) {
	h, s, a, b := p2Session(t)
	away(h, s, b)
	for range 3 {
		tx := grantP2(h, s, a, false)
		frames(h, a, tx, 0, 9)
		a.onFloorRelease(tx)
	}
	if h.ClipCounts().BufferBytes == 0 {
		t.Fatal("nothing buffered")
	}
	h.Ended(pages.Ending{PageID: s.pageID, Sender: s.sender, Answerer: s.answerer, Reason: pages.ReasonIdle})
	if c := h.ClipCounts(); c.BufferBytes != 0 || len(h.clips.buffers) != 0 {
		t.Fatalf("after the end: %+v, %d buffers", c, len(h.clips.buffers))
	}
}

// waitFor polls cond: a fake-clock timer fires on its own goroutine.
func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(recvTimeout); !cond(); {
		if time.Now().After(deadline) {
			t.Fatal("condition not met")
		}
		runtime.Gosched()
		time.Sleep(time.Millisecond)
	}
}

func TestAP2TransmissionIsDeadToAProtocol1SocketOfItsTalker(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 1)
	drain(b)
	away(h, s, b)
	rejoin(h, s, a, 1)
	_, before := h.FrameCounts()
	frames(h, a, tx, 2, 2)
	wantQueue(t, "talker", drain(a), nil)
	if got := h.held(s, tx); got.next != 2 || got.gapSent {
		t.Fatalf("next %d gapSent %v: the protocol-1 frame was taken", got.next, got.gapSent)
	}
	if _, d := h.FrameCounts(); d != before+1 {
		t.Fatal("the protocol-1 frame was not dropped")
	}
	h.mu.Lock()
	s.gapLocked(h.held2(s, tx))
	h.mu.Unlock()
	wantQueue(t, "talker", drain(a), nil)
}

func TestClosingAnOpenTransmissionResetsTheIdleTimer(t *testing.T) {
	h, s, a, _ := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 1)
	h.mu.Lock()
	s.everBoth = true
	h.mu.Unlock()
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	gen := s.idleGen
	h.mu.Unlock()
	if h.stateOf(s, tx) != txOpen {
		t.Fatal("not open")
	}
	h.mu.Lock()
	s.closeTxLocked(h.held2(s, tx))
	after := s.idleGen
	h.mu.Unlock()
	if after == gen {
		t.Fatal("closing an open transmission did not re-arm the idle timer")
	}
}
