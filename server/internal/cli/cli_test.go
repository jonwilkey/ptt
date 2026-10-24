package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/httpapi"
)

type result struct {
	code           int
	stdout, stderr string
}

func run(t *testing.T, vars map[string]string, args ...string) result {
	t.Helper()
	var out, errOut bytes.Buffer
	code := Run(t.Context(), args, Env{
		Version: "1.2.3",
		Lookup: func(k string) (string, bool) {
			v, ok := vars[k]
			return v, ok
		},
		Stdout: &out,
		Stderr: &errOut,
		Now:    func() time.Time { return time.Date(2026, 10, 24, 15, 4, 5, 0, time.UTC) },
	})
	return result{code, out.String(), errOut.String()}
}

// hostPort returns the host:port of an httptest server, for use as a PTTD_*_ADDR value.
func hostPort(s *httptest.Server) string {
	return strings.TrimPrefix(s.URL, "http://")
}

func TestVersion(t *testing.T) {
	r := run(t, nil, "version")
	if r.code != 0 || r.stdout != "1.2.3\n" {
		t.Fatalf("version = %+v", r)
	}
}

func TestUsageErrors(t *testing.T) {
	for _, args := range [][]string{
		{"bogus"}, {"version", "x"}, {"migrate"}, {"migrate", "sideways"},
		{"admin"}, {"admin", "devices"}, {"admin", "status", "--bogus"}, {"admin", "status", "x"},
	} {
		r := run(t, nil, args...)
		if r.code != 2 || !strings.Contains(r.stderr, "Usage: pttd") {
			t.Errorf("%v: code=%d stderr=%q, want usage and exit 2", args, r.code, r.stderr)
		}
	}
}

func TestHelp(t *testing.T) {
	r := run(t, nil, "help")
	if r.code != 0 || !strings.Contains(r.stdout, "admin status") {
		t.Fatalf("help = %+v", r)
	}
}

func TestInvalidConfigFailsFast(t *testing.T) {
	r := run(t, map[string]string{config.EnvLogLevel: "loud"}, "serve")
	if r.code != 1 || !strings.Contains(r.stderr, config.EnvLogLevel) {
		t.Fatalf("serve with bad config = %+v", r)
	}
}

func TestHealthcheck(t *testing.T) {
	for _, tt := range []struct {
		status   int
		wantCode int
	}{{http.StatusOK, 0}, {http.StatusServiceUnavailable, 1}} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/healthz" {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(tt.status)
		}))
		r := run(t, map[string]string{config.EnvListenAddr: hostPort(srv)}, "healthcheck")
		srv.Close()
		if r.code != tt.wantCode {
			t.Errorf("healthz %d: exit %d, want %d (stderr %q)", tt.status, r.code, tt.wantCode, r.stderr)
		}
	}
}

func TestHealthcheckServerDown(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	addr := hostPort(srv)
	srv.Close()
	if r := run(t, map[string]string{config.EnvListenAddr: addr}, "healthcheck"); r.code != 1 {
		t.Fatalf("exit %d, want 1", r.code)
	}
}

func statusServer(t *testing.T, s httpapi.Status) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/internal/status" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(s)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestAdminStatus(t *testing.T) {
	srv := statusServer(t, httpapi.Status{
		Version: "1.2.3", UptimeSeconds: 2*86400 + 3*3600 + 4*60 + 5,
		ActiveSessions: 0, ActivePages: 2, DBOK: true, SchemaVersion: 1,
	})
	r := run(t, map[string]string{config.EnvInternalAddr: hostPort(srv)}, "admin", "status")
	if r.code != 0 {
		t.Fatalf("exit %d: %s", r.code, r.stderr)
	}
	for _, want := range []string{
		"version:         1.2.3",
		"uptime:          2d 3h4m0s",
		"database:        ok",
		"schema version:  1",
		"active sessions: 0",
		"active pages:    2",
	} {
		if !strings.Contains(r.stdout, want) {
			t.Errorf("output missing %q:\n%s", want, r.stdout)
		}
	}
}

func TestAdminStatusDatabaseError(t *testing.T) {
	srv := statusServer(t, httpapi.Status{Version: "1.2.3", DBOK: false})
	r := run(t, map[string]string{config.EnvInternalAddr: hostPort(srv)}, "admin", "status")
	if r.code != 0 || !strings.Contains(r.stdout, "database:        ERROR") {
		t.Fatalf("admin status = %+v", r)
	}
}

func TestAdminStatusJSON(t *testing.T) {
	want := httpapi.Status{Version: "1.2.3", UptimeSeconds: 42, ActivePages: 1, DBOK: true, SchemaVersion: 1}
	srv := statusServer(t, want)
	r := run(t, map[string]string{config.EnvInternalAddr: hostPort(srv)}, "admin", "status", "--json")
	if r.code != 0 {
		t.Fatalf("exit %d: %s", r.code, r.stderr)
	}
	var got httpapi.Status
	if err := json.Unmarshal([]byte(r.stdout), &got); err != nil {
		t.Fatalf("output is not JSON: %v\n%s", err, r.stdout)
	}
	if got != want {
		t.Errorf("got %+v, want %+v", got, want)
	}
}

func TestAdminStatusServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer srv.Close()
	r := run(t, map[string]string{config.EnvInternalAddr: hostPort(srv)}, "admin", "status")
	if r.code != 1 || !strings.Contains(r.stderr, "HTTP 500") {
		t.Fatalf("admin status = %+v", r)
	}
}

func TestMigrateCommands(t *testing.T) {
	dir := t.TempDir()
	vars := map[string]string{
		config.EnvDBPath:      filepath.Join(dir, "pttd.db"),
		config.EnvSnapshotDir: filepath.Join(dir, "snapshots"),
		config.EnvLogLevel:    "error",
	}

	r := run(t, vars, "migrate", "status")
	if r.code != 0 || !strings.Contains(r.stdout, "pending") || !strings.Contains(r.stdout, "00001_init.sql") {
		t.Fatalf("status before up = %+v", r)
	}
	if r := run(t, vars, "migrate", "up"); r.code != 0 {
		t.Fatalf("up = %+v", r)
	}
	r = run(t, vars, "migrate", "status")
	if r.code != 0 || !strings.Contains(r.stdout, "applied") {
		t.Fatalf("status after up = %+v", r)
	}

	if r := run(t, vars, "migrate", "down"); r.code != 0 {
		t.Fatalf("down = %+v", r)
	}
	// down keeps a copy of what it is about to drop.
	snaps, err := os.ReadDir(vars[config.EnvSnapshotDir])
	if err != nil || len(snaps) != 1 || snaps[0].Name() != "pre-migrate-20261024T150405Z-v1.db" {
		t.Fatalf("snapshots after down = %v, %v", snaps, err)
	}
	if r := run(t, vars, "migrate", "down"); r.code != 1 || !strings.Contains(r.stderr, "no migrations are applied") {
		t.Fatalf("second down = %+v", r)
	}
}
