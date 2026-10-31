package ws

import (
	"testing"
	"time"
)

// TestProgressGoesToALiveSocketNotFCM is protocol section 8: one channel per transition.
func TestProgressGoesToALiveSocketNotFCM(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)

	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], p.id); err != nil {
		t.Fatal(err)
	}
	a.want("page_state", "page_id", p.id, "state", "delivered")
	if err := f.svc.Acknowledge(t.Context(), f.ids["S2"], p.id); err != nil {
		t.Fatal(err)
	}
	a.want("page_state", "state", "acknowledged")
	// The recipients' own pushes are unchanged; the sender got nothing by FCM.
	f.wantFCM("page_ack->tok-S1")

	// page_answered is the exception: always FCM, and over the socket as well.
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	f.wantFCM("page_answered->tok-A", "page_cancel:answered_elsewhere->tok-S2")
}

func TestProgressFallsBackToFCMWhenNotLive(t *testing.T) {
	f := newFixture(t)

	// No socket at all.
	p := f.page()
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], p.id); err != nil {
		t.Fatal(err)
	}
	f.wantFCM("page_state:delivered->tok-A")

	// A socket that is connected but not joined is not live.
	p2 := f.page()
	c := f.connect("A")
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], p2.id); err != nil {
		t.Fatal(err)
	}
	f.wantFCM("page_state:delivered->tok-A")
	c.ping()

	// A joined socket silent for more than 15 s is not live either.
	p3 := f.page()
	a, _ := f.joined("A", p3)
	f.clk.Advance(16 * time.Second)
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], p3.id); err != nil {
		t.Fatal(err)
	}
	f.wantFCM("page_state:delivered->tok-A")
	// ...and is live again as soon as it speaks.
	a.ping()
	if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], p3.id); err != nil {
		t.Fatal(err)
	}
	a.want("page_state", "state", "acknowledged")
	f.wantFCM("page_ack->tok-S2")
}

func TestProgressIsOnlyForTheSocketsOwnSession(t *testing.T) {
	f := newFixture(t)
	p, other := f.page(), f.page()
	a, _ := f.joined("A", other) // joined to a different page
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], p.id); err != nil {
		t.Fatal(err)
	}
	f.wantFCM("page_state:delivered->tok-A")
	a.ping()
}

func TestNotDeliveredAndMissedReachTheSocket(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	// 30 s after creation, undelivered: the scheduler re-pushes and tells the sender.
	f.advance(20*time.Second, a)
	f.clk.Advance(10 * time.Second)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	a.want("page_state", "state", "not_delivered")
	f.wantFCM("page->tok-S1", "page->tok-S2") // the re-push; the sender is live, so no FCM page_state

	// Fifteen minutes unanswered: missed. (The page is moved into the past rather than the
	// clock forward, which would time the socket out.)
	f.fcm.Clear()
	f.exec(`UPDATE active_pages SET created_at = ? WHERE id = ?`, t0.Add(-16*time.Minute).UnixMilli(), p.id)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	a.want("session_end", "reason", "missed")
	a.wantClose(1000, "normal")
	f.wantFCM("page_cancel:missed->tok-S1", "page_cancel:missed->tok-S2")
}
