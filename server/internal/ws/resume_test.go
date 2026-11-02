package ws

import (
	"context"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/clock"
)

func resume(h *Hub, s *session, c *conn, tx string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	s.resumeLocked(c, uuid.MustParse(tx))
}

func TestResumeOfAnOpenTransmissionReportsNextAndAppendsFromThere(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 4)
	h.mu.Lock()
	s.releaseLocked(reasonDisconnected)
	h.mu.Unlock()
	resume(h, s, a, tx)
	frames(h, a, tx, 3, 6) // 3 and 4 are held already: dropped silently
	if got := h.held(s, tx); got.next != 7 || len(got.frames) != 7 || got.state != txOpen {
		t.Fatalf("next %d, %d frames, state %d", got.next, len(got.frames), got.state)
	}
	a.onFloorRelease(tx)
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "resume_from:4", "listener_gap", "floor_released:released"})
	wantQueue(t, "listener", drain(b), seqs([]string{"floor_granted"}, 0, 4, "floor_released:disconnected", "clip:6", "f5", "f6"))
	if n := h.ClipCounts().Resumes; n != 1 {
		t.Errorf("resumes = %d", n)
	}
}

func TestResumeWithNoFramesYetReportsMinusOne(t *testing.T) {
	h, s, a, _ := p2Session(t)
	tx := grantP2(h, s, a, false)
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	h.mu.Unlock()
	resume(h, s, a, tx)
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:silence", "resume_from:-1"})
}

func TestResumeOfAnUnbufferedOpenTransmissionIsAccepted(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 2)
	rejoin(h, s, b, 1)
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	h.mu.Unlock()
	resume(h, s, a, tx)
	frames(h, a, tx, 3, 3)
	wantQueue(t, "talker", drain(a), []string{"floor_granted", "floor_released:silence", "resume_from:2"})
	wantQueue(t, "listener", drain(b), []string{"floor_released:silence", "f3"})
}

func TestResumeWindowRestartsAtTheResume(t *testing.T) {
	h, s, a, _ := p2Session(t)
	clk, ok := h.clk.(*clock.Fake)
	if !ok {
		t.Fatal("not a fake clock")
	}
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 0)
	h.mu.Lock()
	s.releaseLocked(reasonDisconnected)
	h.mu.Unlock()
	clk.Advance(25 * time.Second)
	resume(h, s, a, tx)
	clk.Advance(10 * time.Second) // 35 s after the release: past the old window
	blockUntil(t, clk, 2)
	if h.stateOf(s, tx) != txOpen {
		t.Fatal("closed 35 s after open although a resume came at 25 s")
	}
	clk.Advance(19 * time.Second) // 29 s after the resume
	blockUntil(t, clk, 2)
	if h.stateOf(s, tx) != txOpen {
		t.Fatal("closed before 30 s after the resume")
	}
	clk.Advance(time.Second)
	waitFor(t, func() bool { return h.stateOf(s, tx) == txClosed })
}

func TestResumeOfAClosedHeldTransmissionRepliesThenReleases(t *testing.T) {
	h, s, a, b := p2Session(t)
	s.everBoth = true
	away(h, s, b)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 2)
	a.onFloorRelease(tx)
	drain(a)
	h.mu.Lock()
	gen := s.idleGen
	h.mu.Unlock()
	resume(h, s, a, tx)
	wantQueue(t, "talker", drain(a), []string{"resume_from:2", "floor_released:released"})
	h.mu.Lock()
	defer h.mu.Unlock()
	if s.idleGen == gen {
		t.Error("its floor_released did not reset the idle timer (section 11.5)")
	}
	if got := s.buf.find(uuid.MustParse(tx)); got == nil || got.state != txClosed || got.next != 3 {
		t.Fatalf("transmission changed: %+v", got)
	}
	if n := h.clips.resumes.Load(); n != 1 {
		t.Errorf("resumes = %d", n)
	}
}

func TestResumeOfAnotherDevicesOrUnknownOrEmptyTransmissionIsBadTransmissionID(t *testing.T) {
	h, s, a, b := p2Session(t)
	away(h, s, b)
	mine := grantP2(h, s, a, false)
	frames(h, a, mine, 0, 1)
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	h.mu.Unlock()
	empty := grantP2(h, s, b, false)
	b.onFloorRelease(empty) // closed with no frames: removed at once
	drain(a)
	drain(b)

	for name, tc := range map[string]struct {
		c  *conn
		tx string
	}{
		"not the caller's": {b, mine},
		"unknown":          {a, uuid.NewString()},
		"no frames":        {b, empty},
	} {
		resume(h, s, tc.c, tc.tx)
		wantQueue(t, name, drain(tc.c), []string{"error"})
	}
	if n := h.ClipCounts().Resumes; n != 0 {
		t.Errorf("resumes = %d", n)
	}
	if h.stateOf(s, mine) != txOpen {
		t.Error("a rejected resume changed the transmission")
	}
}

func TestResumeOfALiveTransmissionIsBadTransmissionID(t *testing.T) {
	h, s, a, _ := p2Session(t)
	tx := grantP2(h, s, a, false)
	drain(a)
	resume(h, s, a, tx)
	wantQueue(t, "talker", drain(a), []string{"error"})
	if h.stateOf(s, tx) != txLive || h.ClipCounts().Resumes != 0 {
		t.Fatal("a rejected resume changed something")
	}
}

func TestResumeOfARemovedTransmissionIsBadTransmissionID(t *testing.T) {
	h, s, a, b := p2Session(t)
	tx := grantP2(h, s, a, false)
	frames(h, a, tx, 0, 1)
	a.onFloorRelease(tx)
	b.onHeard(tx, 1) // heard in full: removed
	drain(a)
	resume(h, s, a, tx)
	wantQueue(t, "talker", drain(a), []string{"error"})
}

func TestResumeOfAnOpenTransmissionResetsTheIdleTimer(t *testing.T) {
	h, s, a, _ := p2Session(t)
	s.everBoth = true
	tx := grantP2(h, s, a, false)
	h.mu.Lock()
	s.releaseLocked(reasonSilence)
	gen := s.idleGen
	h.mu.Unlock()
	resume(h, s, a, tx)
	h.mu.Lock()
	defer h.mu.Unlock()
	if s.idleGen == gen {
		t.Error("idle timer not reset")
	}
}

func TestTalkerSocketDropsAndResumesOnANewOne(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	s.want("floor_granted")
	for i := range 5 {
		a.sendBinary(frameFor(tx, uint32(i), byte(i)))
		s.wantFrame(frameFor(tx, uint32(i), byte(i)))
	}
	_ = a.ws.CloseNow()
	s.want("floor_released", "transmission_id", tx, "reason", "disconnected")
	s.want("peer_left", "device_id", f.ids["A"])

	a, _ = f.joinedProto("A", p, 2)
	s.want("peer_joined", "device_id", f.ids["A"])
	a.want("peer_joined", "device_id", f.ids["S1"])
	a.send(map[string]any{"type": "resume", "transmission_id": tx})
	a.want("resume_from", "last_seq", "4")
	for i := 3; i <= 6; i++ {
		a.sendBinary(frameFor(tx, uint32(i), byte(i)))
	}
	a.want("listener_gap", "transmission_id", tx)
	a.release(tx)
	a.want("floor_released", "transmission_id", tx, "reason", "released")
	s.want("clip", "talker", f.ids["A"], "transmission_id", tx, "last_seq", "6")
	s.wantFrame(frameFor(tx, 5, 5))
	s.wantFrame(frameFor(tx, 6, 6))
	s.quiet()
	s.send(map[string]any{"type": "heard", "transmission_id": tx, "last_seq": 6})
	s.ping()
	f.bufferBytes(0)
	if c := f.hub.ClipCounts(); c.Resumes != 1 || c.Transmissions != 1 {
		t.Errorf("counts = %+v", c)
	}
}

func TestResumeAfterTheWindowRanOutLearnsItsTransmissionClosed(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	s.want("floor_granted")
	for i := range 3 {
		a.sendBinary(frameFor(tx, uint32(i), byte(i)))
		s.wantFrame(frameFor(tx, uint32(i), byte(i)))
	}
	_ = a.ws.CloseNow()
	s.want("floor_released", "reason", "disconnected")
	s.want("peer_left")
	f.advance(31*time.Second, s)
	s.quiet() // seen live in full: no clip

	a, _ = f.joinedProto("A", p, 2)
	s.want("peer_joined")
	a.want("peer_joined")
	a.send(map[string]any{"type": "resume", "transmission_id": tx})
	a.want("resume_from", "last_seq", "2")
	a.want("floor_released", "transmission_id", tx, "reason", "released")
	a.quiet()
}

func TestResumeAfterARestartIsBadTransmissionID(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	s.want("floor_granted")
	a.sendBinary(frameFor(tx, 0, 0))
	s.wantFrame(frameFor(tx, 0, 0))

	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	f.hub.Shutdown(ctx)
	a.wantClose(4503, "server_restarting")
	f.srv.Close()
	f.hub = New(Options{DB: f.db, Ender: f.svc, Clock: f.clk})
	f.hub.started = t0.Add(-time.Hour)
	f.svc.SetNotifier(f.hub)
	f.srv = httptest.NewServer(f.hub)

	a, _ = f.joinedProto("A", p, 2)
	a.send(map[string]any{"type": "resume", "transmission_id": tx})
	a.want("error", "code", "bad_transmission_id")
	a.quiet()
}

// blockUntil is clk.BlockUntil with a deadline, so a timer that fires early (and leaves fewer
// waiters) fails the test instead of hanging it.
func blockUntil(t *testing.T, clk *clock.Fake, n int) {
	t.Helper()
	done := make(chan struct{})
	go func() { clk.BlockUntil(n); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatalf("fewer than %d timers waiting: one fired early", n)
	}
}
