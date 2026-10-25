// Package ratelimit is an in-memory sliding-window rate limiter keyed by an arbitrary string.
//
// It keeps at most limit timestamps per key, so memory is bounded by the number of distinct
// keys seen within one window; keys whose window has emptied are pruned. State is lost on
// restart, which only ever errs towards allowing a request.
package ratelimit

import (
	"sync"
	"time"
)

// Limiter allows at most limit events per key in any window-long interval.
type Limiter struct {
	limit  int
	window time.Duration
	now    func() time.Time

	mu        sync.Mutex
	events    map[string][]time.Time
	lastPrune time.Time
}

// New returns a Limiter. A nil now means time.Now; tests inject a fake clock.
func New(limit int, window time.Duration, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{limit: limit, window: window, now: now, events: make(map[string][]time.Time)}
}

// Allow records an event for key and reports whether it is within the limit. When it is not,
// retryAfter is how long until the oldest counted event leaves the window; the rejected event
// is not recorded, so hammering a limited key does not extend its penalty.
func (l *Limiter) Allow(key string) (ok bool, retryAfter time.Duration) {
	now := l.now()

	l.mu.Lock()
	defer l.mu.Unlock()
	l.prune(now)

	evs := l.live(key, now)
	if len(evs) >= l.limit {
		l.events[key] = evs
		return false, evs[0].Add(l.window).Sub(now)
	}
	l.events[key] = append(evs, now)
	return true, 0
}

// live returns key's events still inside the window, oldest first.
func (l *Limiter) live(key string, now time.Time) []time.Time {
	evs := l.events[key]
	cutoff := now.Add(-l.window)
	i := 0
	for i < len(evs) && !evs[i].After(cutoff) {
		i++
	}
	return evs[i:]
}

// prune drops keys with no live events, at most once per window so Allow stays cheap.
func (l *Limiter) prune(now time.Time) {
	if now.Sub(l.lastPrune) < l.window {
		return
	}
	l.lastPrune = now
	for k := range l.events {
		if len(l.live(k, now)) == 0 {
			delete(l.events, k)
		}
	}
}

// Len returns the number of keys currently tracked (for tests).
func (l *Limiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.events)
}
