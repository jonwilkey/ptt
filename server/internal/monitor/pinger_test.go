package monitor

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type hit struct {
	method, path, body, contentType string
}

type lockedBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuf) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuf) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func fakeHC(t *testing.T, status int) (*httptest.Server, chan hit) {
	t.Helper()
	hits := make(chan hit, 100)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		hits <- hit{r.Method, r.URL.Path, string(b), r.Header.Get("Content-Type")}
		w.WriteHeader(status)
	}))
	t.Cleanup(srv.Close)
	return srv, hits
}

func TestPing(t *testing.T) {
	srv, hits := fakeHC(t, http.StatusOK)
	logs := &lockedBuf{}
	p := NewPinger(srv.URL+"/", "sekrit-key", slog.New(slog.NewTextHandler(logs, nil)))

	p.Ping(t.Context(), "ptt-uptime", false, "")
	p.Ping(t.Context(), "ptt-phones", true, "2 phones need attention")

	want := []hit{
		{"POST", "/sekrit-key/ptt-uptime", "", "text/plain; charset=utf-8"},
		{"POST", "/sekrit-key/ptt-phones/fail", "2 phones need attention", "text/plain; charset=utf-8"},
	}
	for i, w := range want {
		if got := <-hits; got != w {
			t.Errorf("ping %d = %+v, want %+v", i, got, w)
		}
	}
	if logs.String() != "" {
		t.Errorf("successful pings logged: %s", logs.String())
	}
}

func TestNewPingerWithoutKey(t *testing.T) {
	if p := NewPinger("https://hc-ping.com", "", slog.Default()); p != nil {
		t.Errorf("NewPinger without a key = %v, want nil", p)
	}
}

func TestPingFailureIsLoggedWithoutKeyAndNotRetried(t *testing.T) {
	srv, hits := fakeHC(t, http.StatusInternalServerError)
	logs := &lockedBuf{}
	p := NewPinger(srv.URL, "sekrit-key", slog.New(slog.NewTextHandler(logs, nil)))
	p.Ping(t.Context(), "ptt-fcm", false, "")

	if n := len(hits); n != 1 {
		t.Errorf("requests = %d, want exactly 1 (no retries)", n)
	}
	out := logs.String()
	if !strings.Contains(out, "level=WARN") || !strings.Contains(out, "ptt-fcm") || !strings.Contains(out, "500") {
		t.Errorf("log = %q, want a warning naming the check and status", out)
	}
	if strings.Contains(out, "sekrit-key") || strings.Contains(out, srv.URL) {
		t.Errorf("log leaks the URL or the key: %q", out)
	}
}

func TestPingUnreachableNeverLogsKey(t *testing.T) {
	srv, _ := fakeHC(t, http.StatusOK)
	url := srv.URL
	srv.Close()
	logs := &lockedBuf{}
	p := NewPinger(url, "sekrit-key", slog.New(slog.NewTextHandler(logs, nil)))
	p.Ping(t.Context(), "ptt-uptime", false, "")
	out := logs.String()
	if !strings.Contains(out, "ptt-uptime") || strings.Contains(out, "sekrit-key") {
		t.Errorf("log = %q", out)
	}
}

func TestPingTimeout(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { <-release }))
	t.Cleanup(srv.Close)
	t.Cleanup(func() { close(release) })
	logs := &lockedBuf{}
	p := NewPinger(srv.URL, "sekrit-key", slog.New(slog.NewTextHandler(logs, nil)))
	p.client.Timeout = 50 * time.Millisecond

	start := time.Now()
	p.Ping(context.Background(), "ptt-uptime", false, "")
	if time.Since(start) > 5*time.Second {
		t.Error("ping did not time out")
	}
	if out := logs.String(); !strings.Contains(out, "level=WARN") || strings.Contains(out, "sekrit-key") {
		t.Errorf("log = %q", out)
	}
}

func TestPingTimeoutIsTenSeconds(t *testing.T) {
	if p := NewPinger("http://x", "k", slog.Default()); p.client.Timeout != 10*time.Second {
		t.Errorf("timeout = %v, want 10s", p.client.Timeout)
	}
}
