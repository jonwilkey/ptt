package monitor

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/push"
)

var discard = slog.New(slog.NewTextHandler(io.Discard, nil))

func newDB(t *testing.T) *sql.DB {
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
	return d
}

func exec(t *testing.T, d *sql.DB, q string, args ...any) {
	t.Helper()
	if _, err := d.ExecContext(t.Context(), q, args...); err != nil {
		t.Fatalf("%s: %v", q, err)
	}
}

type devSpec struct {
	id, user, name string
	token          any
	lastSeen       any
	checklist      any
	revoked, test  int
}

func addDevice(t *testing.T, d *sql.DB, s devSpec) {
	t.Helper()
	exec(t, d, `INSERT OR IGNORE INTO users (id, display_name, created_at) VALUES (?, ?, 0)`, s.user, s.user)
	hash := sha256.Sum256([]byte(s.id))
	var revoked any
	if s.revoked == 1 {
		revoked = 1
	}
	exec(t, d, `INSERT INTO devices (id, user_id, name, token_hash, fcm_token, app_version, checklist_status,
		created_at, last_seen_at, revoked_at, is_test) VALUES (?, ?, ?, ?, ?, '0.5.0', ?, 0, ?, ?, ?)`,
		s.id, s.user, s.name, hash[:], s.token, s.checklist, s.lastSeen, revoked, s.test)
}

type stamped struct {
	hit
	at time.Time
}

func stampedHC(t *testing.T, clk clock.Clock) (string, chan stamped) {
	t.Helper()
	hits := make(chan stamped, 100)
	srv := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		hits <- stamped{hit{r.Method, r.URL.Path, string(b), r.Header.Get("Content-Type")}, clk.Now()}
	}))
	t.Cleanup(srv.Close)
	return srv.URL, hits
}

func next(t *testing.T, hits chan stamped) stamped {
	t.Helper()
	select {
	case h := <-hits:
		return h
	case <-time.After(5 * time.Second):
		t.Fatal("no ping arrived")
		return stamped{}
	}
}

var t0 = time.Date(2026, 11, 2, 18, 0, 0, 0, time.UTC)

func TestEveryTiming(t *testing.T) {
	clk := clock.NewFake(t0)
	ctx, cancel := context.WithCancel(t.Context())
	var mu sync.Mutex
	var ran []time.Time
	done := make(chan struct{})
	go func() {
		defer close(done)
		every(ctx, clk, time.Minute, 6*time.Hour, func(context.Context) {
			mu.Lock()
			ran = append(ran, clk.Now())
			mu.Unlock()
		})
	}()
	count := func() int { mu.Lock(); defer mu.Unlock(); return len(ran) }

	clk.BlockUntil(1)
	clk.Advance(time.Minute - time.Second)
	clk.BlockUntil(1)
	if count() != 0 {
		t.Fatal("ran before the first delay")
	}
	clk.Advance(time.Second)
	clk.BlockUntil(1)
	clk.Advance(6*time.Hour - time.Second)
	clk.BlockUntil(1)
	if count() != 1 {
		t.Fatalf("ran %d times, want 1 before the first period", count())
	}
	clk.Advance(time.Second)
	clk.BlockUntil(1)
	cancel()
	<-done

	want := []time.Time{t0.Add(time.Minute), t0.Add(time.Minute + 6*time.Hour)}
	if fmt.Sprint(ran) != fmt.Sprint(want) {
		t.Errorf("ran at %v, want %v", ran, want)
	}
}

type stubSender struct {
	push.Fake
	validate error
	mu       sync.Mutex
	sentAt   []time.Time
	clk      clock.Clock
}

func (s *stubSender) Send(ctx context.Context, m push.Message) error {
	s.mu.Lock()
	s.sentAt = append(s.sentAt, s.clk.Now())
	s.mu.Unlock()
	return s.Fake.Send(ctx, m)
}

func (s *stubSender) ValidateOnly(context.Context) error { return s.validate }

func opts(t *testing.T, clk clock.Clock, base string, sender push.Sender, d *sql.DB) Options {
	t.Helper()
	return Options{
		Pinger: NewPinger(base, "key", discard), Clock: clk, Logger: discard, DB: d, Version: "0.6.0",
		Sender: sender, Health: func(context.Context) string { return "" },
	}
}

func TestRunSchedule(t *testing.T) {
	clk := clock.NewFake(t0)
	base, hits := stampedHC(t, clk)
	d := newDB(t)
	addDevice(t, d, devSpec{id: "a", user: "Alex", name: "A", token: "tok", lastSeen: t0.Add(-time.Hour).UnixMilli(), checklist: `{"battery":false}`})
	o := opts(t, clk, base, push.NewFake(), d)
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() { defer close(done); Run(ctx, o) }()
	t.Cleanup(func() { cancel(); <-done })

	expect := func(at time.Time, paths ...string) {
		t.Helper()
		got := map[string]bool{}
		for range paths {
			h := next(t, hits)
			if !h.at.Equal(at) {
				t.Errorf("%s pinged at %v, want %v", h.path, h.at, at)
			}
			got[h.path] = true
		}
		for _, p := range paths {
			if !got[p] {
				t.Errorf("missing ping %s at %v (got %v)", p, at, got)
			}
		}
	}
	step := func(d time.Duration) {
		t.Helper()
		clk.BlockUntil(4)
		clk.Advance(d)
	}

	expect(t0, "/key/ptt-uptime")
	step(time.Minute)
	expect(t0.Add(time.Minute), "/key/ptt-fcm")
	step(4 * time.Minute)
	expect(t0.Add(5*time.Minute), "/key/ptt-uptime", "/key/ptt-phones/fail")
	step(5 * time.Minute)
	expect(t0.Add(10*time.Minute), "/key/ptt-uptime")
	for range 10 {
		step(5 * time.Minute)
		next(t, hits)
	}
	step(5 * time.Minute)
	expect(t0.Add(65*time.Minute), "/key/ptt-uptime", "/key/ptt-phones/fail")
}

func TestRunWithoutPingerOnlySendsCheckIn(t *testing.T) {
	clk := clock.NewFake(t0)
	o := opts(t, clk, "http://unused", push.NewFake(), newDB(t))
	o.Pinger = nil
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() { defer close(done); Run(ctx, o) }()
	clk.BlockUntil(1)
	cancel()
	<-done
}

func TestRunDisabledSenderSkipsCheckIn(t *testing.T) {
	clk := clock.NewFake(t0)
	o := opts(t, clk, "http://unused", push.Disabled{}, newDB(t))
	o.Pinger = nil
	done := make(chan struct{})
	go func() { defer close(done); Run(t.Context(), o) }()
	<-done
}

func TestUptime(t *testing.T) {
	for _, tt := range []struct {
		reason, path, body string
	}{
		{"", "/key/ptt-uptime", ""},
		{"database_unavailable", "/key/ptt-uptime/fail", "database_unavailable"},
		{"schema_outdated", "/key/ptt-uptime/fail", "schema_outdated"},
	} {
		t.Run(tt.reason, func(t *testing.T) {
			clk := clock.NewFake(t0)
			base, hits := stampedHC(t, clk)
			o := opts(t, clk, base, push.NewFake(), nil)
			o.Health = func(context.Context) string { return tt.reason }
			o.uptime(t.Context())
			if h := next(t, hits); h.path != tt.path || h.body != tt.body {
				t.Errorf("ping = %s %q, want %s %q", h.path, h.body, tt.path, tt.body)
			}
		})
	}
}

func TestFCM(t *testing.T) {
	tests := []struct {
		name   string
		sender push.Sender
		path   string
		body   string
	}{
		{"validate ok", &stubSender{}, "/key/ptt-fcm", ""},
		{"fake sender is success", push.NewFake(), "/key/ptt-fcm", ""},
		{"not configured", push.Disabled{}, "/key/ptt-fcm/fail", "fcm not configured"},
		{"permanent failure", &stubSender{validate: fmt.Errorf("%w: sender id mismatch", push.ErrPermanent)}, "/key/ptt-fcm/fail", "permanent"},
		{"timeout", &stubSender{validate: context.DeadlineExceeded}, "/key/ptt-fcm/fail", "timeout"},
		{"transient", &stubSender{validate: errors.New("503 from fcm")}, "/key/ptt-fcm/fail", "transient"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			clk := clock.NewFake(t0)
			base, hits := stampedHC(t, clk)
			o := opts(t, clk, base, tt.sender, nil)
			o.fcm(t.Context())
			if h := next(t, hits); h.path != tt.path || h.body != tt.body {
				t.Errorf("ping = %s %q, want %s %q", h.path, h.body, tt.path, tt.body)
			}
		})
	}
}

func phoneJobFor(t *testing.T, clk *clock.Fake, base string, d *sql.DB) *phoneJob {
	t.Helper()
	j := &phoneJob{Options: opts(t, clk, base, push.NewFake(), d), procStart: clk.Now()}
	j.init(t.Context())
	return j
}

func TestPhoneJob(t *testing.T) {
	ms := func(ago time.Duration) int64 { return t0.Add(-ago).UnixMilli() }
	clk := clock.NewFake(t0)
	base, hits := stampedHC(t, clk)
	d := newDB(t)
	addDevice(t, d, devSpec{id: "ok", user: "Alex", name: "fine", token: "t", lastSeen: ms(time.Hour), checklist: `{"battery":true}`})
	j := phoneJobFor(t, clk, base, d)

	j.run(t.Context())
	if h := next(t, hits); h.path != "/key/ptt-phones" || h.body != "" {
		t.Errorf("healthy ping = %s %q", h.path, h.body)
	}

	addDevice(t, d, devSpec{id: "red", user: "Sam", name: "Pixel", token: "t", lastSeen: ms(time.Hour), checklist: `{"battery":false}`})
	addDevice(t, d, devSpec{id: "test", user: "Sam", name: "t", token: nil, lastSeen: ms(time.Hour), test: 1})
	addDevice(t, d, devSpec{id: "gone", user: "Sam", name: "g", token: nil, lastSeen: ms(time.Hour), revoked: 1})
	j.run(t.Context())
	h := next(t, hits)
	if h.path != "/key/ptt-phones/fail" || !strings.Contains(h.body, "Sam / Pixel (red): red checklist: battery; app 0.5.0") || strings.Contains(h.body, "(test)") || strings.Contains(h.body, "(gone)") {
		t.Errorf("ping = %s\n%s", h.path, h.body)
	}

	addDevice(t, d, devSpec{id: "quiet", user: "Zed", name: "Z", token: "", lastSeen: ms(49 * time.Hour)})
	j.run(t.Context())
	if a, b := next(t, hits), next(t, hits); a.path != "/key/ptt-phones" || a.body != "re-alert follows; not resolved" || b.path != "/key/ptt-phones/fail" {
		t.Errorf("new pair: got %s %q then %s", a.path, a.body, b.path)
	}
}

func TestPhoneJobRestartRule(t *testing.T) {
	ms := func(ago time.Duration) int64 { return t0.Add(-ago).UnixMilli() }
	for _, tt := range []struct {
		name     string
		newest   time.Duration
		wantFail bool
	}{
		{"server was down: nobody reported silent", 30 * time.Hour, false},
		{"normal restart: the old phone is reported", 2 * time.Hour, true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			clk := clock.NewFake(t0)
			base, hits := stampedHC(t, clk)
			d := newDB(t)
			addDevice(t, d, devSpec{id: "new", user: "Alex", name: "n", token: "t", lastSeen: ms(tt.newest)})
			addDevice(t, d, devSpec{id: "old", user: "Sam", name: "o", token: "t", lastSeen: ms(60 * time.Hour)})
			j := phoneJobFor(t, clk, base, d)
			j.run(t.Context())
			h := next(t, hits)
			if got := h.path == "/key/ptt-phones/fail"; got != tt.wantFail {
				t.Errorf("ping = %s %q, want fail=%v", h.path, h.body, tt.wantFail)
			}
		})
	}
}

func TestNextCheckIn(t *testing.T) {
	la, _ := time.LoadLocation("America/Los_Angeles")
	tests := []struct {
		name      string
		now, want time.Time
	}{
		{"before 11:00", time.Date(2026, 11, 2, 10, 59, 59, 0, la), time.Date(2026, 11, 2, 11, 0, 0, 0, la)},
		{"at 11:00 is already past", time.Date(2026, 11, 2, 11, 0, 0, 0, la), time.Date(2026, 11, 3, 11, 0, 0, 0, la)},
		{"after 11:00", time.Date(2026, 11, 2, 23, 0, 0, 0, la), time.Date(2026, 11, 3, 11, 0, 0, 0, la)},
		{"given in UTC", time.Date(2026, 11, 2, 18, 59, 0, 0, time.UTC), time.Date(2026, 11, 2, 11, 0, 0, 0, la)},
		{"across fall back (25 h day)", time.Date(2026, 11, 1, 12, 0, 0, 0, la), time.Date(2026, 11, 2, 11, 0, 0, 0, la)},
		{"across spring forward (23 h day)", time.Date(2026, 3, 8, 12, 0, 0, 0, la), time.Date(2026, 3, 9, 11, 0, 0, 0, la)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := NextCheckIn(tt.now); !got.Equal(tt.want) {
				t.Errorf("NextCheckIn(%v) = %v, want %v", tt.now, got, tt.want)
			}
		})
	}
}

func TestCheckInLoopAcrossDST(t *testing.T) {
	la, _ := time.LoadLocation("America/Los_Angeles")
	clk := clock.NewFake(time.Date(2026, 10, 30, 13, 0, 0, 0, la))
	sender := &stubSender{clk: clk}
	d := newDB(t)
	o := opts(t, clk, "http://unused", sender, d)
	o.Pinger = nil
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() { defer close(done); Run(ctx, o) }()

	addDevice(t, d, devSpec{id: "a", user: "Alex", name: "A", token: "tok-a"})
	addDevice(t, d, devSpec{id: "b", user: "Sam", name: "B", token: "tok-b"})
	addDevice(t, d, devSpec{id: "none", user: "Sam", name: "n", token: nil})
	addDevice(t, d, devSpec{id: "empty", user: "Sam", name: "e", token: ""})
	addDevice(t, d, devSpec{id: "test", user: "Sam", name: "t", token: "tok-t", test: 1})
	addDevice(t, d, devSpec{id: "gone", user: "Sam", name: "g", token: "tok-g", revoked: 1})

	var wantAt []time.Time
	for _, day := range []int{31, 1, 2} {
		month := time.October
		if day < 31 {
			month = time.November
		}
		at := time.Date(2026, month, day, 11, 0, 0, 0, la)
		wantAt = append(wantAt, at, at)
		clk.BlockUntil(1)
		clk.Advance(at.Add(-time.Second).Sub(clk.Now()))
		clk.BlockUntil(1)
		sender.mu.Lock()
		if n := len(sender.sentAt); n != len(wantAt)-2 {
			t.Fatalf("%d sends before %v, want %d", n, at, len(wantAt)-2)
		}
		sender.mu.Unlock()
		clk.Advance(time.Second)
		for deadline := time.Now().Add(5 * time.Second); ; {
			sender.mu.Lock()
			n := len(sender.sentAt)
			sender.mu.Unlock()
			if n == len(wantAt) {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("check_in of %v never sent (%d sends)", at, n)
			}
			time.Sleep(time.Millisecond)
		}
		clk.BlockUntil(1)
	}
	cancel()
	<-done

	for i, at := range sender.sentAt {
		if !at.Equal(wantAt[i]) {
			t.Errorf("send %d at %v, want %v", i, at, wantAt[i])
		}
	}
	msgs := sender.Messages()
	tokens := map[string]int{}
	for _, m := range msgs {
		tokens[m.Token]++
		if len(m.Data) != 1 || m.Data["type"] != "check_in" || m.Priority != "normal" || m.TTLSecs != 24*3600 {
			t.Errorf("message = %+v, want {type: check_in}, normal, 24 h", m)
		}
	}
	if len(tokens) != 2 || tokens["tok-a"] != 3 || tokens["tok-b"] != 3 {
		t.Errorf("recipients = %v, want tok-a and tok-b three times each", tokens)
	}
}
