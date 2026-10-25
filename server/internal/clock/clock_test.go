package clock

import (
	"testing"
	"time"
)

func TestFakeAfterFiresOnAdvance(t *testing.T) {
	f := NewFake(time.Unix(1000, 0))
	ch := f.After(10 * time.Second)
	select {
	case <-ch:
		t.Fatal("fired early")
	default:
	}
	f.Advance(9 * time.Second)
	select {
	case <-ch:
		t.Fatal("fired early")
	default:
	}
	f.Advance(time.Second)
	select {
	case got := <-ch:
		if !got.Equal(time.Unix(1010, 0)) {
			t.Errorf("fired at %v", got)
		}
	default:
		t.Fatal("did not fire")
	}
	select {
	case <-f.After(0):
	default:
		t.Error("After(0) did not fire at once")
	}
}

func TestFakeBlockUntil(*testing.T) {
	f := NewFake(time.Unix(0, 0))
	done := make(chan struct{})
	go func() { f.BlockUntil(1); close(done) }()
	f.After(time.Second)
	<-done
}
