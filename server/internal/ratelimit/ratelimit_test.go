package ratelimit

import (
	"testing"
	"time"
)

type fakeClock struct{ t time.Time }

func (c *fakeClock) now() time.Time { return c.t }

func TestAllow(t *testing.T) {
	clk := &fakeClock{t: time.Unix(1_000_000, 0)}
	l := New(2, time.Hour, clk.now)

	for i := range 2 {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("event %d rejected", i)
		}
		clk.t = clk.t.Add(10 * time.Minute)
	}
	// 20 minutes in: the first event leaves the window after 40 more.
	ok, retry := l.Allow("a")
	if ok || retry != 40*time.Minute {
		t.Fatalf("Allow = %v, %v; want rejected, 40m", ok, retry)
	}
	// A rejected event is not recorded, so the penalty does not move.
	clk.t = clk.t.Add(time.Minute)
	if ok, retry = l.Allow("a"); ok || retry != 39*time.Minute {
		t.Fatalf("Allow = %v, %v; want rejected, 39m", ok, retry)
	}
	// Other keys are independent.
	if ok, _ := l.Allow("b"); !ok {
		t.Error("key b rejected")
	}
	// Once the first event leaves the window, one more fits.
	clk.t = clk.t.Add(39 * time.Minute)
	if ok, _ = l.Allow("a"); !ok {
		t.Error("event rejected after the window slid")
	}
	if ok, _ = l.Allow("a"); ok {
		t.Error("window over-filled")
	}
}

func TestPruneDropsIdleKeys(t *testing.T) {
	clk := &fakeClock{t: time.Unix(1_000_000, 0)}
	l := New(1, time.Hour, clk.now)
	for _, k := range []string{"a", "b", "c"} {
		l.Allow(k)
	}
	if l.Len() != 3 {
		t.Fatalf("Len = %d, want 3", l.Len())
	}
	clk.t = clk.t.Add(2 * time.Hour)
	l.Allow("d")
	if l.Len() != 1 {
		t.Errorf("Len = %d after pruning, want 1", l.Len())
	}
}
