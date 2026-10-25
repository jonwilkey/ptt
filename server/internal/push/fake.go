package push

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// fakeCapacity bounds the fake's memory; the oldest messages are dropped first.
const fakeCapacity = 200

// Recorded is one message the Fake accepted, as served by GET /internal/fake-fcm.
type Recorded struct {
	Token    string            `json:"token"`
	Data     map[string]string `json:"data"`
	Priority string            `json:"priority"`
	TTL      string            `json:"ttl"`
	TTLSecs  int64             `json:"ttl_seconds"`
}

// Fake records messages in a bounded ring instead of sending them. It is for CI smoke tests and
// local development; the tokens it holds are test values.
type Fake struct {
	mu   sync.Mutex
	msgs []Recorded
}

// NewFake returns an empty Fake.
func NewFake() *Fake { return &Fake{} }

// Send implements Sender. It applies the same validation (and error classification) as the real sender.
func (f *Fake) Send(_ context.Context, m Message) error {
	if err := m.Validate(); err != nil {
		return fmt.Errorf("%w: %w", ErrPermanent, err)
	}
	data := make(map[string]string, len(m.Data))
	for k, v := range m.Data {
		data[k] = v
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.msgs) >= fakeCapacity {
		f.msgs = append(f.msgs[:0], f.msgs[1:]...)
	}
	f.msgs = append(f.msgs, Recorded{
		Token: m.Token, Data: data, Priority: m.Priority.String(),
		TTL: m.TTL.String(), TTLSecs: int64(m.TTL / time.Second),
	})
	return nil
}

// ValidateOnly implements Sender.
func (*Fake) ValidateOnly(context.Context) error { return nil }

// Messages returns a copy of the recorded messages, oldest first.
func (f *Fake) Messages() []Recorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]Recorded{}, f.msgs...)
}

// Clear forgets every recorded message.
func (f *Fake) Clear() {
	f.mu.Lock()
	f.msgs = nil
	f.mu.Unlock()
}

// Handler serves GET (a JSON list) and DELETE (clear) for the internal listener.
func (f *Fake) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			_ = json.NewEncoder(w).Encode(f.Messages())
		case http.MethodDelete:
			f.Clear()
			w.WriteHeader(http.StatusNoContent)
		default:
			w.Header().Set("Allow", "GET, DELETE")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	})
}
