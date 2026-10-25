package push

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db"
)

func rosterDB(t *testing.T) *sql.DB {
	t.Helper()
	d, err := db.Open(t.Context(), filepath.Join(t.TempDir(), "pttd.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = d.Close() })
	m, err := db.NewMigrator(d, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Up(t.Context()); err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{
		`INSERT INTO users (id, display_name, is_admin, created_at) VALUES ('u1', 'Alex', 1, 1)`,
		`INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token) VALUES ('d-live', 'u1', 'A', randomblob(32), 1, 'tok-live')`,
		`INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token) VALUES ('d-gone', 'u1', 'B', randomblob(32), 1, 'tok-gone')`,
		`INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token) VALUES ('d-fail', 'u1', 'C', randomblob(32), 1, 'tok-fail')`,
		`INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token, revoked_at) VALUES ('d-revoked', 'u1', 'D', randomblob(32), 1, 'tok-revoked', 9)`,
		`INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token, is_test) VALUES ('d-test', 'u1', 'E', randomblob(32), 1, 'tok-test', 1)`,
		`INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d-notoken', 'u1', 'F', randomblob(32), 1)`,
	} {
		if _, err := d.ExecContext(t.Context(), q); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	return d
}

// scriptedSender wraps the fake and fails chosen tokens.
type scriptedSender struct {
	*Fake
	errs map[string]error
}

func (s scriptedSender) Send(ctx context.Context, m Message) error {
	if err := s.errs[m.Token]; err != nil {
		return err
	}
	return s.Fake.Send(ctx, m)
}

func TestRosterNotifier(t *testing.T) {
	d := rosterDB(t)
	f := NewFake()
	s := scriptedSender{f, map[string]error{
		"tok-gone": ErrUnregistered,
		"tok-fail": errors.New("fcm unavailable"),
	}}
	var buf syncBuffer
	n := NewRosterNotifier(d, s, newTextLogger(&buf))

	n.RosterPublished(t.Context(), 7)
	n.Wait()

	msgs := f.Messages()
	if len(msgs) != 1 || msgs[0].Token != "tok-live" {
		t.Fatalf("delivered = %+v, want only tok-live", msgs)
	}
	m := msgs[0]
	if m.Data["type"] != "roster" || m.Data["version"] != "7" || len(m.Data) != 2 || m.Priority != "normal" || m.TTLSecs != 86400 {
		t.Errorf("message = %+v", m)
	}

	var gone, fail sql.NullString
	if err := d.QueryRowContext(t.Context(), `SELECT fcm_token FROM devices WHERE id='d-gone'`).Scan(&gone); err != nil || gone.Valid {
		t.Errorf("unregistered token not cleared: %v %v", gone, err)
	}
	if err := d.QueryRowContext(t.Context(), `SELECT fcm_token FROM devices WHERE id='d-fail'`).Scan(&fail); err != nil || !fail.Valid {
		t.Errorf("transiently failing token must stay: %v %v", fail, err)
	}

	out := buf.String()
	if !contains(out, "d-fail") || !contains(out, "level=WARN") {
		t.Errorf("expected a warning naming d-fail, got %q", out)
	}
	for _, secret := range []string{"tok-live", "tok-gone", "tok-fail"} {
		if contains(out, secret) {
			t.Errorf("log leaked %s: %q", secret, out)
		}
	}
}

func TestRosterNotifierDoesNotBlock(t *testing.T) {
	d := rosterDB(t)
	release := make(chan struct{})
	n := NewRosterNotifier(d, blockingSender{release}, newTextLogger(&syncBuffer{}))

	done := make(chan struct{})
	go func() { n.RosterPublished(t.Context(), 1); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("RosterPublished blocked on a slow sender")
	}

	// Shutdown cancels sends that outlast the drain deadline.
	ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
	defer cancel()
	finished := make(chan struct{})
	go func() { n.Close(ctx); close(finished) }()
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not cancel the stuck send")
	}
	close(release)
}

type blockingSender struct{ release chan struct{} }

func (b blockingSender) Send(ctx context.Context, _ Message) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-b.release:
		return nil
	}
}
func (blockingSender) ValidateOnly(context.Context) error { return nil }

// racingSender rotates the device's token while the push is in flight, then reports the old
// one unregistered.
type racingSender struct{ db *sql.DB }

func (r racingSender) Send(ctx context.Context, m Message) error {
	if m.Token != "tok-gone" {
		return nil
	}
	if _, err := r.db.ExecContext(ctx, `UPDATE devices SET fcm_token='tok-rotated' WHERE id='d-gone'`); err != nil {
		return err
	}
	return ErrUnregistered
}
func (racingSender) ValidateOnly(context.Context) error { return nil }

func TestRosterNotifierKeepsRotatedToken(t *testing.T) {
	d := rosterDB(t)
	n := NewRosterNotifier(d, racingSender{d}, newTextLogger(&syncBuffer{}))
	n.RosterPublished(t.Context(), 1)
	n.Wait()

	var tok sql.NullString
	if err := d.QueryRowContext(t.Context(), `SELECT fcm_token FROM devices WHERE id='d-gone'`).Scan(&tok); err != nil {
		t.Fatal(err)
	}
	if tok.String != "tok-rotated" {
		t.Errorf("token = %v, want the rotated token to survive", tok)
	}
}

// versionSender records roster versions and holds the first send until released.
type versionSender struct {
	started chan struct{}
	release chan struct{}
	once    sync.Once
	mu      sync.Mutex
	seen    map[string]bool
}

func (v *versionSender) Send(ctx context.Context, m Message) error {
	v.once.Do(func() { close(v.started) })
	select {
	case <-v.release:
	case <-ctx.Done():
		return ctx.Err()
	}
	v.mu.Lock()
	v.seen[m.Data["version"]] = true
	v.mu.Unlock()
	return nil
}
func (*versionSender) ValidateOnly(context.Context) error { return nil }

func TestRosterNotifierCoalescesQueuedVersions(t *testing.T) {
	d := rosterDB(t)
	v := &versionSender{started: make(chan struct{}), release: make(chan struct{}), seen: map[string]bool{}}
	n := NewRosterNotifier(d, v, newTextLogger(&syncBuffer{}))

	n.RosterPublished(t.Context(), 1)
	<-v.started
	n.RosterPublished(t.Context(), 2)
	n.RosterPublished(t.Context(), 3)
	close(v.release)
	n.Wait()

	if len(v.seen) != 2 || !v.seen["1"] || !v.seen["3"] {
		t.Errorf("versions sent = %v, want only 1 (in flight) and 3 (latest)", v.seen)
	}
}
