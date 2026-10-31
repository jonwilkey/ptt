package app

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

type person struct {
	user, device, token string
}

func addPerson(t *testing.T, d *sql.DB, name string) person {
	t.Helper()
	tok, hash, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	p := person{user: uuid.NewString(), device: uuid.NewString(), token: tok}
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO users (id, display_name, created_at) VALUES (?, ?, 0)`, []any{p.user, name}},
		{`INSERT INTO devices (id, user_id, name, token_hash, fcm_token, created_at) VALUES (?, ?, ?, ?, ?, 0)`,
			[]any{p.device, p.user, name, hash, "tok-" + name}},
	} {
		if _, err := d.ExecContext(t.Context(), q.sql, q.args...); err != nil {
			t.Fatal(err)
		}
	}
	return p
}

func call(t *testing.T, h http.Handler, token, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var rd bytes.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		rd = *bytes.NewReader(raw)
	}
	req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, path, &rd)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func summarize(f *push.Fake) []string {
	var out []string
	for _, m := range f.Messages() {
		s := m.Data["type"]
		if r := m.Data["reason"] + m.Data["state"]; r != "" {
			s += ":" + r
		}
		out = append(out, s+"->"+m.Token)
	}
	sort.Strings(out)
	f.Clear()
	return out
}

func equal(a, b []string) bool { return strings.Join(a, ",") == strings.Join(b, ",") }

// boot opens the database at path and starts an App on it, as pttd does at startup.
func boot(t *testing.T, path string, clk clock.Clock, fake *push.Fake) (*App, *sql.DB) {
	t.Helper()
	d, err := db.Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	m, err := db.NewMigrator(d, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Up(t.Context()); err != nil {
		t.Fatal(err)
	}
	a := New("test", slog.New(slog.DiscardHandler), d, m, WithSender(fake, fake), WithClock(clk))
	a.Start()
	return a, d
}

// TestRestartWhileRinging stops the app with a page ringing and starts a new one on the same
// database: the timers carry on from the stored page.
func TestRestartWhileRinging(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pttd.db")
	start := time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)
	fake := push.NewFake()

	clk1 := clock.NewFake(start)
	a1, d1 := boot(t, path, clk1, fake)
	alex, sam := addPerson(t, d1, "alex"), addPerson(t, d1, "sam")

	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	id, session := uuid.NewString(), uuid.NewString()
	payload, _ := json.Marshal(map[string]any{
		"format": 1, "page_id": id, "session_id": session, "from_device": alex.device, "to_user": sam.user,
		"level": "normal", "created_at": start.UnixMilli(),
		"keys": map[string]string{sam.device: wire.B64u.EncodeToString(make([]byte, 80))},
	})
	body := map[string]any{
		"id": id, "to": sam.user, "level": "normal", "session_id": session,
		"envelope": signed.Sign(priv, alex.device, signed.ContextEnvelope, payload).Encode(),
	}
	h1 := a1.PublicHandler()
	if rec := call(t, h1, alex.token, "/v1/pages", body); rec.Code != http.StatusCreated {
		t.Fatalf("create = %d %s", rec.Code, rec.Body)
	}
	a1.pageSvc.Wait()
	if got := summarize(fake); !equal(got, []string{"page->tok-sam"}) {
		t.Fatalf("initial pushes = %v", got)
	}

	// "Restart": shut down the first app and its database.
	a1.Close(t.Context())
	if err := d1.Close(); err != nil {
		t.Fatal(err)
	}

	// The new process starts 40 s later, with the 30 s deadline already behind it.
	clk2 := clock.NewFake(start.Add(40 * time.Second))
	a2, d2 := boot(t, path, clk2, fake)
	t.Cleanup(func() { a2.Close(t.Context()); _ = d2.Close() })
	clk2.BlockUntil(3) // the scheduler's first pass is done; the cleanup's daily wait and the WebSocket sweep are set
	a2.pageSvc.Wait()
	if got := summarize(fake); !equal(got, []string{"page->tok-sam", "page_state:not_delivered->tok-alex"}) {
		t.Fatalf("pushes after restart = %v", got)
	}

	// Sam's phone answers through the new process; its timers are the new clock's.
	h2 := a2.PublicHandler()
	if rec := call(t, h2, sam.token, "/v1/pages/"+id+"/answer", nil); rec.Code != http.StatusNoContent {
		t.Fatalf("answer = %d %s", rec.Code, rec.Body)
	}
	a2.pageSvc.Wait()
	if got := summarize(fake); !equal(got, []string{"page_answered->tok-alex"}) {
		t.Fatalf("answer pushes = %v", got)
	}
	clk2.Advance(15 * time.Minute)
	clk2.BlockUntil(3) // scheduler tick, the cleanup's daily wait and the WebSocket sweep
	var n int
	if err := d2.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM active_pages`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("active pages after the answered expiry = %d, %v", n, err)
	}
	if rec := call(t, h2, sam.token, "/v1/pages/"+id+"/delivered", nil); rec.Code != http.StatusGone {
		t.Errorf("delivered after expiry = %d", rec.Code)
	}
}

func TestPageMetricsAreExposedWithoutIdentifyingLabels(t *testing.T) {
	a, closeDB := newApp(t, true)
	t.Cleanup(func() { _ = closeDB() })
	rec := httptest.NewRecorder()
	a.InternalHandler().ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/metrics", nil))
	out := rec.Body.String()
	for _, want := range []string{
		"pttd_page_delivery_seconds_bucket", "pttd_page_answer_seconds_bucket",
		`pttd_pages_ended_total{reason="missed"} 0`, `pttd_fcm_send_errors_total{kind="unregistered"} 0`,
		"pttd_active_sessions 0",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("/metrics lacks %q", want)
		}
	}
}
