package ws

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/pages"
)

func (f *fixture) answer(device string, p page) {
	f.t.Helper()
	if err := f.svc.Answer(f.t.Context(), f.ids[device], p.id); err != nil {
		f.t.Fatal(err)
	}
	f.svc.Wait()
}

func (f *fixture) cancel(device string, p page) {
	f.t.Helper()
	if err := f.svc.Cancel(f.t.Context(), f.ids[device], p.id); err != nil {
		f.t.Fatal(err)
	}
	f.svc.Wait()
}

// both returns a sender and an answerer that have joined an answered page.
func (f *fixture) both() (page, *client, *client) {
	f.t.Helper()
	p := f.page()
	a, _ := f.joined("A", p)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	s, snap := f.joined("S1", p)
	if snap["type"] != "page_state" || snap["state"] != "answered" {
		f.t.Fatalf("answerer snapshot = %v", snap)
	}
	s.want("peer_joined", "device_id", f.ids["A"])
	a.want("peer_joined", "device_id", f.ids["S1"])
	f.fcm.Clear()
	return p, a, s
}

func TestJoinRules(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	notFound := func(c *client) { c.want("error", "code", "not_found") }

	// The sender joins at any time, and gets the snapshot.
	a, snap := f.joined("A", p)
	if snap["type"] != "page_state" || snap["page_id"] != p.id || snap["state"] != "ringing" {
		t.Fatalf("sender snapshot = %v", snap)
	}

	// A recipient may not join before it answers; the socket stays open and may retry.
	s1 := f.connect("S1")
	s1.send(map[string]any{"type": "join", "session_id": p.session})
	notFound(s1)
	s1.send(map[string]any{"type": "join", "session_id": p.session})
	notFound(s1)

	// An unknown session and a page of somebody else are the same answer.
	s1.send(map[string]any{"type": "join", "session_id": "00000000-0000-4000-8000-000000000000"})
	notFound(s1)

	// After the answer the answering device may join; the other recipient may not.
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	s1.send(map[string]any{"type": "join", "session_id": p.session})
	s1.want("page_state", "state", "answered")
	s1.want("peer_joined", "device_id", f.ids["A"])
	a.want("peer_joined", "device_id", f.ids["S1"])

	s2 := f.connect("S2")
	s2.send(map[string]any{"type": "join", "session_id": p.session})
	notFound(s2)

	// A second successful join is a protocol error.
	s1.send(map[string]any{"type": "join", "session_id": p.session})
	s1.wantClose(4400, "protocol_error")
	a.want("peer_left", "device_id", f.ids["S1"])
}

func TestJoinAfterRefusalRetriesOnTheSameSocket(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	c := f.connect("A")
	c.send(map[string]any{"type": "join", "session_id": "11111111-1111-4111-8111-111111111111"})
	c.want("error", "code", "not_found")
	c.send(map[string]any{"type": "join", "session_id": p.session})
	c.want("page_state", "state", "ringing")
}

func TestSnapshotStates(t *testing.T) {
	f := newFixture(t)
	check := func(p page, want string) {
		t.Helper()
		c, snap := f.joined("A", p)
		if snap["state"] != want {
			t.Errorf("snapshot = %v, want %s", snap["state"], want)
		}
		_ = c.ws.CloseNow()
	}
	ringing, delivered, acked, answered, late := f.page(), f.page(), f.page(), f.page(), f.page()
	check(ringing, "ringing")
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], delivered.id); err != nil {
		t.Fatal(err)
	}
	check(delivered, "delivered")
	if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], acked.id); err != nil {
		t.Fatal(err)
	}
	check(acked, "acknowledged")
	f.answer("S2", answered)
	check(answered, "answered")
	// Undelivered for 30 s after creation: not_delivered. (Created at t0; the clock moves
	// before the socket connects so no socket timer is involved.)
	f.clk.Advance(30 * time.Second)
	check(late, "not_delivered")
	// A delivered page stays delivered however old.
	check(delivered, "delivered")
}

func TestSelfPageIsSenderOnly(t *testing.T) {
	f := newFixture(t)
	p := f.pageTo(f.alexUser, "A")
	f.answer("A", p)
	a, snap := f.joined("A", p)
	if snap["state"] != "answered" {
		t.Fatalf("snapshot = %v", snap)
	}
	// There is no second participant and no floor.
	a.send(map[string]any{"type": "floor_request", "transmission_id": "6f1d6a4e-9c1b-4f5e-8a7d-2b3c4d5e6f70"})
	a.want("error", "code", "no_peer")
	if f.hub.Sessions() != 0 {
		t.Errorf("a self page counted as a session")
	}
	// Ending it reaches the one socket.
	f.cancel("A", p)
	a.want("session_end", "reason", "ended_by_you")
	a.wantClose(1000, "normal")
}

func TestUnjoinedSocketNeedsAJoin(t *testing.T) {
	f := newFixture(t)
	for _, raw := range []string{
		`{"type":"floor_request","transmission_id":"6f1d6a4e-9c1b-4f5e-8a7d-2b3c4d5e6f70"}`,
		`{"type":"floor_release","transmission_id":"6f1d6a4e-9c1b-4f5e-8a7d-2b3c4d5e6f70"}`,
		`{"type":"session_end"}`,
	} {
		c := f.connect("A")
		c.sendRaw([]byte(raw))
		c.wantClose(4400, "protocol_error")
	}
	// A binary frame on an unjoined socket is dropped silently.
	c := f.connect("A")
	c.sendBinary(validFrame(0))
	c.sendBinary([]byte("not even a frame"))
	c.ping()
}

func TestMalformedJoin(t *testing.T) {
	f := newFixture(t)
	for _, raw := range []string{
		`{"type":"join"}`,
		`{"type":"join","session_id":"ABCDEF00-0000-4000-8000-000000000000"}`,
		`{"type":"join","session_id":"nope"}`,
		`{"type":"join","session_id":5}`,
		`{"type":"join","session_id":"00000000-0000-4000-8000-000000000000","extra":1}`,
	} {
		c := f.connect("A")
		c.sendRaw([]byte(raw))
		c.wantClose(4400, "protocol_error")
	}
}

func TestOneSocketPerDevice(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()

	// S1 opens a new socket: the old one is closed 4409 and the sender hears peer_left.
	s2 := f.connect("S1")
	s.wantClose(4409, "replaced")
	a.want("peer_left", "device_id", f.ids["S1"])

	// The new socket has no session; its owner joins again and the sender hears peer_joined.
	s2.ping()
	s2.send(map[string]any{"type": "join", "session_id": p.session})
	s2.want("page_state", "state", "answered")
	s2.want("peer_joined", "device_id", f.ids["A"])
	a.want("peer_joined", "device_id", f.ids["S1"])
	s2.ping()
	a.ping()
}

func TestSessionEndFromAParticipant(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	s.send(map[string]any{"type": "session_end"})
	s.want("session_end", "reason", "ended_by_you")
	a.want("session_end", "reason", "ended_by_peer")
	s.wantClose(1000, "normal")
	a.wantClose(1000, "normal")
	if !f.pageGone(p) {
		t.Error("the page row is not deleted, or has no tombstone")
	}
	if f.hub.Sessions() != 0 || f.hub.Sockets() != 0 {
		t.Errorf("sessions=%d sockets=%d after the end", f.hub.Sessions(), f.hub.Sockets())
	}
	// Both were live: no FCM fallback.
	f.wantFCM()
}

func TestSenderSessionEndMatchesRESTCancel(t *testing.T) {
	for _, via := range []string{"websocket", "rest"} {
		t.Run(via, func(t *testing.T) {
			f := newFixture(t)
			p, a, s := f.both()
			if via == "websocket" {
				a.send(map[string]any{"type": "session_end"})
			} else {
				f.cancel("A", p)
			}
			a.want("session_end", "reason", "ended_by_you")
			s.want("session_end", "reason", "ended_by_peer")
			if !f.pageGone(p) {
				t.Error("page not ended")
			}
			// The answering device was live on its socket: not told again by FCM.
			f.wantFCM()
		})
	}
}

func TestEndDeletesTheRowBeforeAnySessionEnd(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	s.send(map[string]any{"type": "session_end"})
	a.want("session_end", "reason", "ended_by_peer")
	if !f.pageGone(p) {
		t.Error("session_end was delivered before the deletion was committed")
	}
}

func TestEndFCMFallbackForNonLiveParticipants(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	// Fifteen seconds of silence: both sockets are still open (30 s timeout) but no longer live.
	f.clk.Advance(16 * time.Second)
	_ = p
	s.send(map[string]any{"type": "session_end"}) // S1 is live again by speaking
	s.want("session_end", "reason", "ended_by_you")
	a.want("session_end", "reason", "ended_by_peer") // the WebSocket message goes to every joined socket
	// The sender was not live: FCM tells it the page ended.
	f.wantFCM("page_state:ended->tok-A")
}

func TestSenderCancelAfterAnswerFCMOnlyIfAnswererNotLive(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.clk.Advance(16 * time.Second)
	a.ping() // the sender is live; the answerer has been silent for 16 s
	f.cancel("A", p)
	a.want("session_end", "reason", "ended_by_you")
	s.want("session_end", "reason", "ended_by_peer")
	f.wantFCM("page_cancel:cancelled->tok-S1")
}

func TestCancelBeforeAnswer(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	f.cancel("A", p)
	a.want("session_end", "reason", "ended_by_you")
	a.wantClose(1000, "normal")
	// Before an answer every recipient device is told by FCM.
	f.wantFCM("page_cancel:cancelled->tok-S1", "page_cancel:cancelled->tok-S2")
}

func TestIdleTimer(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.advance(2*time.Minute-time.Second, a, s)
	if f.pageGone(p) {
		t.Fatal("ended before the idle timeout")
	}
	f.clk.Advance(time.Second)
	a.want("session_end", "reason", "idle")
	s.want("session_end", "reason", "idle")
	a.wantClose(1000, "normal")
	s.wantClose(1000, "normal")
	if !f.pageGone(p) {
		t.Error("page not ended")
	}
	f.wantFCM() // the sender was live (it pinged just before)
}

func TestIdleTimerResetAndFCMForANonLiveSender(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.advance(100*time.Second, a, s)
	// What 14c does on a floor grant or release.
	f.hub.mu.Lock()
	f.hub.rooms[p.id].resetIdleLocked()
	f.hub.mu.Unlock()
	f.advance(100*time.Second, a, s)
	if f.pageGone(p) {
		t.Fatal("the reset did not restart the idle timer")
	}
	// Pings, joins and silence do not reset it; the sender goes quiet and is not live at the end.
	s.ping()
	f.clk.Advance(20 * time.Second)
	s.ping()
	f.clk.Advance(1 * time.Second)
	s.want("session_end", "reason", "idle")
	a.want("session_end", "reason", "idle")
	f.wantFCM("page_state:ended->tok-A")
}

func TestIdleTimerArmsOnlyWhenBothJoined(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	f.advance(10*time.Minute, a)
	if f.pageGone(p) {
		t.Fatal("a session with one participant ended on the idle timer")
	}
	// A late answerer joins: the timer starts then.
	s, _ := f.joined("S1", p)
	s.want("peer_joined")
	a.want("peer_joined")
	f.advance(2*time.Minute-time.Second, a, s)
	f.clk.Advance(time.Second)
	a.want("session_end", "reason", "idle")
}

func TestIdleTimerKeepsRunningAfterADisconnect(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	_ = s.ws.CloseNow()
	a.want("peer_left", "device_id", f.ids["S1"])
	f.advance(90*time.Second, a)
	// A rejoin within the window keeps the session.
	s2, snap := f.joined("S1", p)
	if snap["state"] != "answered" {
		t.Fatalf("rejoin snapshot = %v", snap)
	}
	s2.want("peer_joined")
	a.want("peer_joined")
	f.advance(20*time.Second, a, s2)
	f.clk.Advance(10 * time.Second)
	a.want("session_end", "reason", "idle") // the timer was not restarted by the rejoin
	s2.want("session_end", "reason", "idle")
}

func TestFifteenMinuteCapAndHeld(t *testing.T) {
	// Nobody joined together: the scheduler's 15-minute cap ends the session `idle` for whoever is joined.
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	f.fcm.Clear()
	f.exec(`UPDATE active_pages SET answered_at = ? WHERE id = ?`, t0.Add(-pages.AnsweredTTL).UnixMilli(), p.id)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	a.want("session_end", "reason", "idle")
	a.wantClose(1000, "normal")
	if !f.pageGone(p) {
		t.Error("the cap did not end the page")
	}
	f.svc.Wait()

	// Both joined once: the cap no longer applies, the idle timer owns the page. (The answer is
	// moved 20 minutes into the past instead of advancing the clock, which would fire the
	// idle timer.)
	f2 := newFixture(t)
	p2, a2, s2 := f2.both()
	if !f2.hub.Held(p2.id) {
		t.Fatal("Held = false for a session both participants joined")
	}
	f2.exec(`UPDATE active_pages SET answered_at = ? WHERE id = ?`, t0.Add(-20*time.Minute).UnixMilli(), p2.id)
	if _, _, err := f2.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	if f2.count(`SELECT COUNT(*) FROM active_pages WHERE id = ?`, p2.id) != 1 {
		t.Fatal("the 15-minute cap ended a session whose participants joined")
	}
	a2.ping()
	s2.ping()
}

func TestIdleTimerEndsTheSessionEvenIfTheRowIsGone(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.exec(`DELETE FROM active_pages WHERE id = ?`, p.id) // removed behind the server's back
	f.advance(2*time.Minute-time.Second, a, s)
	f.clk.Advance(time.Second)
	a.want("session_end", "reason", "idle")
	s.want("session_end", "reason", "idle")
	a.wantClose(1000, "normal")
	if f.hub.Sessions() != 0 {
		t.Error("the session outlived its page")
	}
}

// flakyEnder fails EndIdle a set number of times, then delegates.
type flakyEnder struct {
	Ender
	mu    sync.Mutex
	fails int
	calls int
}

func (e *flakyEnder) EndIdle(ctx context.Context, pageID string) error {
	e.mu.Lock()
	e.calls++
	fail := e.calls <= e.fails
	e.mu.Unlock()
	if fail {
		return errors.New("database is busy")
	}
	return e.Ender.EndIdle(ctx, pageID)
}

func (e *flakyEnder) count() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.calls
}

func TestIdleEndIsRetriedAfterAnError(t *testing.T) {
	f := newFixture(t)
	e := &flakyEnder{Ender: f.svc, fails: 1}
	f.hub.SetEnder(e)
	p, a, s := f.both()
	f.advance(2*time.Minute-time.Second, a, s)
	f.clk.Advance(time.Second) // the timer fires; EndIdle fails
	waitUntil(t, func() bool { return e.count() == 1 })
	time.Sleep(100 * time.Millisecond) // let idleFired re-arm
	if f.pageGone(p) || !f.hub.Held(p.id) {
		t.Fatal("the session ended despite the failure")
	}
	f.clk.Advance(idleRetry)
	a.want("session_end", "reason", "idle")
	s.want("session_end", "reason", "idle")
	if !f.pageGone(p) || f.hub.Sessions() != 0 {
		t.Error("the retry did not end the page")
	}
}

func waitUntil(t *testing.T, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(recvTimeout); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatal("condition not reached")
}

// TestRestartGraceKeepsAnsweredPagesForRejoin: a freshly started server knows no sessions, but
// the first scheduler pass must not end an old answered page before its participants rejoin.
func TestRestartGraceKeepsAnsweredPagesForRejoin(t *testing.T) {
	f := newFixture(t)
	f.hub.started = t0 // just started
	p := f.page()
	f.answer("S1", p)
	f.exec(`UPDATE active_pages SET answered_at = ? WHERE id = ?`, t0.Add(-20*time.Minute).UnixMilli(), p.id)
	f.fcm.Clear()

	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	if f.pageGone(p) {
		t.Fatal("the first pass after startup ended an answered page")
	}
	a, snap := f.joined("A", p)
	if snap["state"] != "answered" {
		t.Fatalf("snapshot = %v", snap)
	}
	s, _ := f.joined("S1", p)
	s.want("peer_joined", "device_id", f.ids["A"])
	a.want("peer_joined", "device_id", f.ids["S1"])

	// Both rejoined: the session is held by its idle timer from here on, grace or not.
	f.advance(20*time.Second, a, s)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil || f.pageGone(p) {
		t.Fatalf("rejoined session ended by the scheduler: %v", err)
	}
}

func TestRestartGraceEndsAtTheCapWhenNobodyRejoins(t *testing.T) {
	f := newFixture(t)
	f.hub.started = t0
	p := f.page()
	f.answer("S1", p)
	f.exec(`UPDATE active_pages SET answered_at = ? WHERE id = ?`, t0.Add(-20*time.Minute).UnixMilli(), p.id)
	f.fcm.Clear()
	f.clk.Advance(restartGrace - time.Second)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil || f.pageGone(p) {
		t.Fatalf("ended inside the grace: %v", err)
	}
	f.clk.Advance(time.Second)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	if !f.pageGone(p) {
		t.Fatal("an answered page nobody rejoined was not ended after the grace")
	}
	f.wantFCM("page_state:ended->tok-A")
}

func TestReplacementByASocketThatIsAlreadyClosingDoesNothing(t *testing.T) {
	f := newFixture(t)
	old := f.connect("A")
	// A second socket that times out while authenticating must not evict the first.
	c := f.dial()
	f.hub.mu.Lock()
	var mine *conn
	for k := range f.hub.conns {
		if !k.authed {
			mine = k
		}
	}
	f.hub.mu.Unlock()
	mine.closeNow(codeTimeout)
	c.send(hello(f.tokens["A"]))
	c.wantClose(4408, "timeout")
	old.ping()
}
