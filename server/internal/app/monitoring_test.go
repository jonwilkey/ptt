package app

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/monitor"
	"github.com/jonwilkey/ptt/server/internal/push"
)

func TestMonitoringPingsUptime(t *testing.T) {
	paths := make(chan string, 8)
	srv := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { paths <- r.URL.Path }))
	t.Cleanup(srv.Close)

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
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	fake := push.NewFake()
	a := New("test", logger, d, m, WithSender(fake, fake), WithClock(clock.NewFake(time.Now())),
		WithMonitoring(monitor.NewPinger(srv.URL, "key", logger)))
	a.Start()
	t.Cleanup(func() { a.Close(context.Background()) })

	select {
	case p := <-paths:
		if p != "/key/ptt-uptime" {
			t.Errorf("first ping = %s, want /key/ptt-uptime", p)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no uptime ping")
	}
}
