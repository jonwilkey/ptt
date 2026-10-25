package cleanup

import (
	"bytes"
	"context"
	"database/sql"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
)

var now = time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)

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

func count(t *testing.T, d *sql.DB, table string) int {
	t.Helper()
	var n int
	if err := d.QueryRowContext(t.Context(), "SELECT COUNT(*) FROM "+table).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func ms(d time.Duration) int64 { return now.Add(d).UnixMilli() }

func seed(t *testing.T, d *sql.DB) {
	t.Helper()
	for i, exp := range []int64{ms(-time.Second), ms(0), ms(time.Hour)} { // expired, expiring now (exclusive), live
		exec(t, d, `INSERT INTO page_tombstones (id, expires_at) VALUES (?, ?)`, string(rune('a'+i)), exp)
	}
	code := func(id byte, expires time.Duration, used *time.Duration) {
		var usedAt any
		if used != nil {
			usedAt = ms(*used)
		}
		exec(t, d, `INSERT INTO enrollment_codes (code_hash, display_name, created_at, expires_at, used_at) VALUES (?, 'x', 0, ?, ?)`,
			bytes.Repeat([]byte{id}, 32), ms(expires), usedAt)
	}
	dur := func(d time.Duration) *time.Duration { return &d }
	code(1, -25*time.Hour, nil)                // expired more than 24 h ago: deleted
	code(2, -23*time.Hour, nil)                // expired recently: kept so the poll can say "expired"
	code(3, time.Hour, nil)                    // pending: kept
	code(4, 0, dur(-25*time.Hour))             // used more than 24 h ago: deleted
	code(5, time.Hour, dur(-time.Hour))        // used recently: kept
	code(6, -48*time.Hour, dur(-47*time.Hour)) // long gone: deleted
	for i, at := range []int64{ms(-91 * 24 * time.Hour), ms(-90*24*time.Hour - time.Millisecond), ms(-90 * 24 * time.Hour), ms(-time.Hour)} {
		exec(t, d, `INSERT INTO audit_log (at, action) VALUES (?, ?)`, at, "a"+string(rune('0'+i)))
	}
}

func TestOnce(t *testing.T) {
	d := newDB(t)
	seed(t, d)
	got, err := Once(t.Context(), d, now)
	if err != nil {
		t.Fatal(err)
	}
	if want := (Counts{Tombstones: 2, Codes: 3, AuditRows: 2}); got != want {
		t.Errorf("counts = %+v, want %+v", got, want)
	}
	if n := count(t, d, "page_tombstones"); n != 1 {
		t.Errorf("tombstones left = %d", n)
	}
	if n := count(t, d, "enrollment_codes"); n != 3 {
		t.Errorf("codes left = %d", n)
	}
	if n := count(t, d, "audit_log"); n != 2 {
		t.Errorf("audit rows left = %d", n)
	}
	// Idempotent.
	if got, err = Once(t.Context(), d, now); err != nil || got != (Counts{}) {
		t.Errorf("second sweep = %+v, %v", got, err)
	}
}

type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuf) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func TestRunSweepsAtStartAndDaily(t *testing.T) {
	d := newDB(t)
	seed(t, d)
	clk := clock.NewFake(now)
	logs := &syncBuf{}
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan struct{})
	go func() {
		defer close(done)
		Run(ctx, d, clk, slog.New(slog.NewTextHandler(logs, nil)))
	}()

	clk.BlockUntil(1) // the startup sweep is done
	if n := count(t, d, "page_tombstones"); n != 1 {
		t.Fatalf("startup sweep left %d tombstones", n)
	}
	out := logs.String()
	if !strings.Contains(out, "page_tombstones=2") || !strings.Contains(out, "enrollment_codes=3") || !strings.Contains(out, "audit_rows=2") {
		t.Errorf("log = %s", out)
	}

	// 24 h later the live tombstone is gone too.
	clk.Advance(Interval)
	clk.BlockUntil(1)
	if n := count(t, d, "page_tombstones"); n != 0 {
		t.Errorf("tombstones after a day = %d", n)
	}
	cancel()
	<-done
}
