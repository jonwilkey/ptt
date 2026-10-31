package pages

import (
	"errors"
	"testing"
	"time"
)

// runDue advances the clock, runs the due steps and waits for their pushes.
func (f *fixture) runDue(advance time.Duration) {
	f.t.Helper()
	f.clk.Advance(advance)
	if _, _, err := f.svc.RunDue(f.t.Context()); err != nil {
		f.t.Fatal(err)
	}
	f.svc.Wait()
}

func TestUndeliveredPageIsRepushed(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})

	f.runDue(FirstRepush - time.Millisecond)
	f.wantPushes()
	f.runDue(time.Millisecond)
	f.wantPushes("page -> tok-S1 [high 900s]", "page -> tok-S2 [high 900s]", "page_state:not_delivered -> tok-A [normal 900s]")
	// Nothing more until the second deadline, and each deadline fires once.
	f.runDue(time.Minute)
	f.wantPushes()
	f.runDue(SecondRepush - FirstRepush - time.Minute)
	f.wantPushes("page -> tok-S1 [high 900s]", "page -> tok-S2 [high 900s]", "page_state:not_delivered -> tok-A [normal 900s]")
	f.runDue(5 * time.Minute)
	f.wantPushes()
	if f.state(id) != StateRinging {
		t.Errorf("state = %s", f.state(id))
	}
	if got := f.count(`SELECT push_attempts FROM active_pages`); got != 3 {
		t.Errorf("push_attempts = %d, want 3", got)
	}
}

func TestRepushEnvelopeMatchesTheOriginal(t *testing.T) {
	f := newFixture(t)
	req, caller := f.request(pageSpec{level: LevelUrgent})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	f.svc.Wait()
	first := f.sender.Messages()[0].Data
	f.sender.Clear()
	f.runDue(FirstRepush)
	for _, m := range f.sender.Messages() {
		if m.Data["type"] == "page" {
			for _, k := range []string{"page_id", "level", "from_user", "envelope"} {
				if m.Data[k] != first[k] {
					t.Errorf("re-push %s = %q, want %q", k, m.Data[k], first[k])
				}
			}
			if m.TTLSecs != 24*3600 {
				t.Errorf("urgent re-push TTL = %d", m.TTLSecs)
			}
		}
	}
}

func TestDeliveredStopsRepush(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	f.runDue(10 * time.Second)
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	f.runDue(5 * time.Minute)
	f.wantPushes()
}

func TestNormalPageMissedAtFifteenMinutes(t *testing.T) {
	for _, acked := range []bool{false, true} {
		f := newFixture(t)
		id := f.create(pageSpec{})
		if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id); err != nil {
			t.Fatal(err)
		}
		if acked {
			if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], id); err != nil {
				t.Fatal(err)
			}
		}
		f.pushes()

		f.runDue(MissedAfter - time.Millisecond)
		f.wantPushes()
		f.runDue(time.Millisecond)
		f.wantPushes("page_cancel:missed -> tok-S1 [high 900s]", "page_cancel:missed -> tok-S2 [high 900s]",
			"page_state:missed -> tok-A [normal 900s]")
		if f.state(id) != StateEnded || f.count(`SELECT COUNT(*) FROM active_pages`) != 0 {
			t.Errorf("acked=%v: page not ended to a tombstone", acked)
		}
		// A late answer is "ended".
		if err := f.svc.Answer(t.Context(), f.ids["S1"], id); !errors.Is(err, ErrEnded) {
			t.Errorf("answer after missed: %v", err)
		}
	}
}

func TestUrgentPageNeverTimesOut(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{level: LevelUrgent})
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	f.runDue(30 * 24 * time.Hour)
	f.wantPushes()
	if f.state(id) != StateRinging {
		t.Errorf("state = %s", f.state(id))
	}
	if _, ok, _ := f.svc.RunDue(t.Context()); ok {
		t.Error("an urgent delivered page has a deadline")
	}
}

func TestAnsweredPageExpires(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{level: LevelUrgent})
	f.runDue(10 * time.Minute)
	f.pushes()
	if err := f.svc.Answer(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	f.runDue(AnsweredTTL - time.Millisecond)
	if f.state(id) != StateAnswered {
		t.Fatalf("state = %s", f.state(id))
	}
	f.runDue(time.Millisecond)
	if f.state(id) != StateEnded || f.count(`SELECT COUNT(*) FROM active_pages`) != 0 {
		t.Errorf("state = %s", f.state(id))
	}
	// The 15-minute cap is an "idle" session end (protocol sections 8 and 11.5): a sender
	// with no live socket is told by FCM.
	f.wantPushes("page_state:ended -> tok-A [normal 900s]")
}

func TestOverdueAfterDowntimeSkipsRepush(t *testing.T) {
	f := newFixture(t)
	f.create(pageSpec{})
	// The server was down for 20 minutes: the page is simply missed, not re-rung first.
	f.runDue(20 * time.Minute)
	f.wantPushes("page_cancel:missed -> tok-S1 [high 900s]", "page_cancel:missed -> tok-S2 [high 900s]",
		"page_state:missed -> tok-A [normal 900s]")
}

func TestOverdueRepushesCatchUp(t *testing.T) {
	f := newFixture(t)
	f.create(pageSpec{level: LevelUrgent})
	// Down past both re-push deadlines: one re-ring covers both.
	f.runDue(5 * time.Minute)
	f.wantPushes("page -> tok-S1 [high 86400s]", "page -> tok-S2 [high 86400s]", "page_state:not_delivered -> tok-A [normal 900s]")
	if got := f.count(`SELECT push_attempts FROM active_pages`); got != 3 {
		t.Errorf("push_attempts = %d, want 3", got)
	}
	f.runDue(time.Hour)
	f.wantPushes()
}

func TestRunDueReportsNextDeadline(t *testing.T) {
	f := newFixture(t)
	if _, ok, err := f.svc.RunDue(t.Context()); err != nil || ok {
		t.Fatalf("empty: ok=%v err=%v", ok, err)
	}
	f.create(pageSpec{})
	next, ok, err := f.svc.RunDue(t.Context())
	if err != nil || !ok || !next.Equal(t0.Add(FirstRepush)) {
		t.Fatalf("next = %v, %v, %v", next, ok, err)
	}
}

func TestSchedulerLoopOnFakeClock(t *testing.T) {
	f := newFixture(t)
	f.create(pageSpec{})
	f.svc.Start()
	f.svc.Start() // idempotent
	f.clk.BlockUntil(1)

	f.clk.Advance(FirstRepush)
	f.clk.BlockUntil(1) // the loop ran its pass and went back to sleep
	f.wantPushes("page -> tok-S1 [high 900s]", "page -> tok-S2 [high 900s]", "page_state:not_delivered -> tok-A [normal 900s]")

	f.clk.Advance(MissedAfter)
	f.clk.BlockUntil(1)
	f.svc.Wait()
	if n := f.count(`SELECT COUNT(*) FROM active_pages`); n != 0 {
		t.Errorf("%d pages left after the loop's missed pass", n)
	}

	f.svc.Close(t.Context())
	if f.count(`SELECT COUNT(*) FROM page_tombstones`) != 1 {
		t.Error("missing tombstone")
	}
}

func TestRestartResumesTimers(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	f.svc.Close(t.Context())

	// A new service over the same database (a restart) finds the ringing page.
	again := f.newService(f.db)
	t.Cleanup(func() { again.Close(t.Context()) })
	f.clk.Advance(FirstRepush)
	again.Start()
	f.clk.BlockUntil(1)
	again.Wait()
	f.wantPushes("page -> tok-S1 [high 900s]", "page -> tok-S2 [high 900s]", "page_state:not_delivered -> tok-A [normal 900s]")

	// ... and it still honours answers made through the new instance.
	if err := again.Answer(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	again.Wait()
	f.sender.Clear()
	f.clk.Advance(AnsweredTTL)
	f.clk.BlockUntil(1)
	if f.state(id) != StateEnded {
		t.Errorf("state = %s", f.state(id))
	}
}

func TestNoStepsStartAfterClose(t *testing.T) {
	f := newFixture(t)
	f.create(pageSpec{})
	f.svc.Close(t.Context())
	f.clk.Advance(FirstRepush)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := f.count(`SELECT push_attempts FROM active_pages`); got != 1 {
		t.Errorf("push_attempts = %d: a step ran after Close", got)
	}
}
