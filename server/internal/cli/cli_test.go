package cli

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/httpapi"
	"github.com/jonwilkey/ptt/server/migrations"
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
		{"admin"}, {"admin", "devices", "x"}, {"admin", "bootstrap"}, {"admin", "bootstrap", "a", "b"},
		{"admin", "revoke"}, {"admin", "revoke", "--bogus", "x"}, {"admin", "status", "--bogus"}, {"admin", "status", "x"},
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
		ActiveSessions: 0, ActivePages: 2, DBOK: true, SchemaVersion: 1, BinarySchemaVersion: 1,
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

func TestAdminStatusSchemaBehind(t *testing.T) {
	srv := statusServer(t, httpapi.Status{Version: "1.2.3", DBOK: true, SchemaVersion: 1, BinarySchemaVersion: 2})
	r := run(t, map[string]string{config.EnvInternalAddr: hostPort(srv)}, "admin", "status")
	if r.code != 0 || !strings.Contains(r.stdout, "schema version:  1 (BEHIND binary's 2)") {
		t.Fatalf("admin status = %+v", r)
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
	want := httpapi.Status{Version: "1.2.3", UptimeSeconds: 42, ActivePages: 1, DBOK: true, SchemaVersion: 1, BinarySchemaVersion: 1}
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

	// status and down refuse to create a missing database.
	for _, action := range []string{"status", "down"} {
		r := run(t, vars, "migrate", action)
		if r.code != 1 || !strings.Contains(r.stderr, "does not exist") {
			t.Fatalf("%s on missing db = %+v", action, r)
		}
		if _, err := os.Stat(vars[config.EnvDBPath]); !os.IsNotExist(err) {
			t.Fatalf("%s created the database: %v", action, err)
		}
	}
	nested := map[string]string{config.EnvDBPath: filepath.Join(dir, "no", "such", "dir", "pttd.db")}
	if r := run(t, nested, "migrate", "status"); r.code != 1 {
		t.Fatalf("status on missing dir = %+v", r)
	}
	if _, err := os.Stat(filepath.Join(dir, "no")); !os.IsNotExist(err) {
		t.Fatalf("status created the directory: %v", err)
	}

	// Create the database, then roll it back to empty so status shows a pending migration.
	if r := run(t, vars, "migrate", "up"); r.code != 0 {
		t.Fatalf("up = %+v", r)
	}
	// Migrations are numbered 1..N without gaps, so the newest version is the file count.
	files, err := fs.Glob(migrations.FS, "*.sql")
	if err != nil {
		t.Fatal(err)
	}
	latest := len(files)
	for range latest { // one step per migration
		if r := run(t, vars, "migrate", "down"); r.code != 0 {
			t.Fatalf("down to 0 = %+v", r)
		}
	}
	if err := os.RemoveAll(vars[config.EnvSnapshotDir]); err != nil {
		t.Fatal(err)
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
	if err != nil || len(snaps) != 1 || snaps[0].Name() != fmt.Sprintf("pre-migrate-20261024T150405.000Z-v%d.db", latest) {
		t.Fatalf("snapshots after down = %v, %v", snaps, err)
	}
	for range latest - 1 { // the rest, down to an empty schema
		if r := run(t, vars, "migrate", "down"); r.code != 0 {
			t.Fatalf("down to 0 again = %+v", r)
		}
	}
	if r := run(t, vars, "migrate", "down"); r.code != 1 || !strings.Contains(r.stderr, "no migrations are applied") {
		t.Fatalf("second down = %+v", r)
	}
}

// adminVars returns env vars for a freshly migrated database in a temp dir.
func adminVars(t *testing.T) map[string]string {
	t.Helper()
	dir := t.TempDir()
	vars := map[string]string{
		config.EnvDBPath:      filepath.Join(dir, "pttd.db"),
		config.EnvSnapshotDir: filepath.Join(dir, "snapshots"),
		config.EnvPublicURL:   "https://ptt.example.com",
		config.EnvLogLevel:    "error",
	}
	if r := run(t, vars, "migrate", "up"); r.code != 0 {
		t.Fatalf("migrate up = %+v", r)
	}
	return vars
}

func sqlExec(t *testing.T, vars map[string]string, q string) {
	t.Helper()
	d, err := db.Open(t.Context(), vars[config.EnvDBPath])
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = d.Close() }()
	if _, err := d.ExecContext(t.Context(), q); err != nil {
		t.Fatalf("%s: %v", q, err)
	}
}

func TestAdminBootstrap(t *testing.T) {
	vars := adminVars(t)
	r := run(t, vars, "admin", "bootstrap", "Alex Example")
	if r.code != 0 || r.stderr != "" {
		t.Fatalf("bootstrap = %+v", r)
	}
	var qrText string
	for _, line := range strings.Split(r.stdout, "\n") {
		if strings.HasPrefix(line, "pttenroll1:") {
			qrText = line
		}
	}
	if qrText == "" {
		t.Fatalf("no QR text in %q", r.stdout)
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(qrText, "pttenroll1:"))
	if err != nil {
		t.Fatal(err)
	}
	var payload map[string]string
	if err := json.Unmarshal(raw, &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload) != 2 || payload["url"] != "https://ptt.example.com" || len(payload["code"]) != 22 {
		t.Errorf("payload = %v", payload)
	}
	if !strings.ContainsAny(r.stdout, "█▀▄") {
		t.Error("no QR drawing in output")
	}

	// The code is stored (hashed) and never reaches the audit log.
	d, err := db.Open(t.Context(), vars[config.EnvDBPath])
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = d.Close() }()
	var n int
	if err := d.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM enrollment_codes WHERE display_name = 'Alex Example' AND make_admin = 1`).Scan(&n); err != nil || n != 1 {
		t.Errorf("enrollment code rows = %d, %v", n, err)
	}
	if err := d.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%' || ? || '%'`, payload["code"]).Scan(&n); err != nil || n != 0 {
		t.Errorf("code found in audit log (%d rows), %v", n, err)
	}
}

func TestAdminBootstrapRefusesThenResets(t *testing.T) {
	vars := adminVars(t)
	sqlExec(t, vars, `INSERT INTO users (id, display_name, is_admin, created_at) VALUES ('u1', 'Alex', 1, 1)`)
	sqlExec(t, vars, `INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d1', 'u1', 'Phone', zeroblob(32), 1)`)

	r := run(t, vars, "admin", "bootstrap", "Alex")
	if r.code != 1 || !strings.Contains(r.stderr, "--reset") || strings.Contains(r.stdout, "pttenroll1:") {
		t.Fatalf("bootstrap with admin = %+v", r)
	}

	r = run(t, vars, "admin", "bootstrap", "--reset", "Jo")
	if r.code != 0 || !strings.Contains(r.stderr, "WARNING") || !strings.Contains(r.stdout, "pttenroll1:") {
		t.Fatalf("bootstrap --reset = %+v", r)
	}
	r = run(t, vars, "admin", "devices", "--json")
	var devs []struct {
		ID        string  `json:"id"`
		RevokedAt *string `json:"revoked_at"`
	}
	if err := json.Unmarshal([]byte(r.stdout), &devs); err != nil {
		t.Fatal(err)
	}
	if len(devs) != 1 || devs[0].ID != "d1" || devs[0].RevokedAt == nil || *devs[0].RevokedAt != "2026-10-24T15:04:05Z" {
		t.Errorf("devices after reset = %+v", devs)
	}
}

func TestAdminBootstrapNeedsPublicURL(t *testing.T) {
	vars := adminVars(t)
	delete(vars, config.EnvPublicURL)
	r := run(t, vars, "admin", "bootstrap", "Alex")
	if r.code != 1 || !strings.Contains(r.stderr, config.EnvPublicURL) {
		t.Fatalf("bootstrap without URL = %+v", r)
	}
	vars[config.EnvPublicURL] = "http://ptt.example.com"
	if r := run(t, vars, "admin", "bootstrap", "Alex"); r.code != 1 || !strings.Contains(r.stderr, "https") {
		t.Fatalf("bootstrap with http URL = %+v", r)
	}
}

func TestAdminDatabaseMustExist(t *testing.T) {
	vars := map[string]string{
		config.EnvDBPath:    filepath.Join(t.TempDir(), "missing.db"),
		config.EnvPublicURL: "https://ptt.example.com",
	}
	for _, args := range [][]string{{"bootstrap", "Alex"}, {"devices"}, {"revoke", "d1"}} {
		r := run(t, vars, append([]string{"admin"}, args...)...)
		if r.code != 1 || !strings.Contains(r.stderr, "does not exist") {
			t.Errorf("%v = %+v", args, r)
		}
	}
	if _, err := os.Stat(vars[config.EnvDBPath]); !os.IsNotExist(err) {
		t.Errorf("database was created: %v", err)
	}
}

func TestAdminDevicesAndRevoke(t *testing.T) {
	vars := adminVars(t)
	r := run(t, vars, "admin", "devices")
	if r.code != 0 || strings.TrimSpace(r.stdout) != "ID  PERSON  ADMIN  NAME  VERSION  LAST SEEN  REVOKED" {
		t.Fatalf("empty devices = %+v", r)
	}

	sqlExec(t, vars, `INSERT INTO users (id, display_name, is_admin, created_at) VALUES ('u1', 'Alex', 1, 1), ('u2', 'Sam', 0, 2)`)
	sqlExec(t, vars, `INSERT INTO devices (id, user_id, name, token_hash, created_at, app_version, last_seen_at) VALUES ('dev-a', 'u1', 'Pixel', randomblob(32), 1, '0.3.0', 1761318245000)`)
	sqlExec(t, vars, `INSERT INTO devices (id, user_id, name, token_hash, created_at, is_test) VALUES ('dev-b', 'u2', 'Kiosk', randomblob(32), 2, 1)`)

	r = run(t, vars, "admin", "devices")
	if r.code != 0 {
		t.Fatalf("devices = %+v", r)
	}
	lines := strings.Split(strings.TrimSpace(r.stdout), "\n")
	if len(lines) != 3 {
		t.Fatalf("devices output = %q", r.stdout)
	}
	a, b := strings.Fields(lines[1]), strings.Fields(lines[2])
	if strings.Join(a, " ") != "dev-a Alex yes Pixel 0.3.0 2025-10-24T15:04:05Z -" {
		t.Errorf("row a = %q", lines[1])
	}
	if strings.Join(b, " ") != "dev-b Sam no Kiosk (test) - - -" {
		t.Errorf("row b = %q", lines[2])
	}

	r = run(t, vars, "admin", "revoke", "dev-b")
	if r.code != 0 || !strings.Contains(r.stdout, "Revoked device dev-b") {
		t.Fatalf("revoke = %+v", r)
	}
	r = run(t, vars, "admin", "revoke", "dev-b")
	if r.code != 0 || !strings.Contains(r.stdout, "already revoked") {
		t.Fatalf("second revoke = %+v", r)
	}
	if r := run(t, vars, "admin", "revoke", "nope"); r.code != 1 || !strings.Contains(r.stderr, "no such device") {
		t.Fatalf("revoke unknown = %+v", r)
	}

	r = run(t, vars, "admin", "devices", "--json")
	var got []struct {
		ID        string  `json:"id"`
		Person    string  `json:"person"`
		RevokedAt *string `json:"revoked_at"`
	}
	if err := json.Unmarshal([]byte(r.stdout), &got); err != nil {
		t.Fatalf("json: %v\n%s", err, r.stdout)
	}
	if len(got) != 2 || got[0].ID != "dev-a" || got[0].RevokedAt != nil || got[1].RevokedAt == nil || *got[1].RevokedAt != "2026-10-24T15:04:05Z" {
		t.Errorf("json = %+v", got)
	}
}

func TestAdminTestPush(t *testing.T) {
	vars := adminVars(t)
	sqlExec(t, vars, `INSERT INTO users (id, display_name, is_admin, created_at) VALUES ('u1', 'Alex', 1, 1)`)
	sqlExec(t, vars, `INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token) VALUES ('dev-ok', 'u1', 'Pixel', randomblob(32), 1, 'secret-token-value')`)
	sqlExec(t, vars, `INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('dev-notok', 'u1', 'Old', randomblob(32), 1)`)
	sqlExec(t, vars, `INSERT INTO devices (id, user_id, name, token_hash, created_at, fcm_token, revoked_at) VALUES ('dev-gone', 'u1', 'Gone', randomblob(32), 1, 'tok', 5)`)

	// FCM not configured: a clear error, not a silent success.
	if r := run(t, vars, "admin", "test-push", "dev-ok"); r.code != 1 || !strings.Contains(r.stderr, "FCM is not configured") || strings.Count(r.stderr, "not configured") != 1 {
		t.Fatalf("disabled = %+v", r)
	}

	vars[config.EnvFCMFake] = "true"
	r := run(t, vars, "admin", "test-push", "dev-ok")
	if r.code != 0 || !strings.Contains(r.stdout, "would send to device dev-ok: type=test priority=high ttl=15m0s") {
		t.Fatalf("fake = %+v", r)
	}
	if strings.Contains(r.stdout+r.stderr, "secret-token-value") {
		t.Errorf("token leaked: %+v", r)
	}

	for id, want := range map[string]string{
		"nope":      "no such device",
		"dev-notok": "no FCM token",
		"dev-gone":  "revoked",
	} {
		if r := run(t, vars, "admin", "test-push", id); r.code != 1 || !strings.Contains(r.stderr, want) {
			t.Errorf("%s = %+v, want %q", id, r, want)
		}
	}
	if r := run(t, vars, "admin", "test-push"); r.code != 2 {
		t.Errorf("no args = %+v", r)
	}

	// Fake and real credentials together are a configuration error.
	cred := filepath.Join(t.TempDir(), "sa.json")
	if err := os.WriteFile(cred, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	vars[config.EnvFCMCredentials+"_FILE"] = cred
	if r := run(t, vars, "admin", "test-push", "dev-ok"); r.code != 1 || !strings.Contains(r.stderr, "both set") {
		t.Errorf("both = %+v", r)
	}
}
