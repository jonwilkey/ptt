package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/jonwilkey/ptt/server/internal/metrics"
)

func do(t *testing.T, h http.Handler, method, path string, body io.Reader, hdr ...string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequestWithContext(t.Context(), method, path, body)
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func decode[T any](t *testing.T, rec *httptest.ResponseRecorder) T {
	t.Helper()
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q, want application/json", ct)
	}
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode %q: %v", rec.Body.String(), err)
	}
	return v
}

func TestHealthzHealthy(t *testing.T) {
	h := NewPublic(PublicOptions{Version: "1.2.3", Health: func(context.Context) error { return nil }})
	rec := do(t, h, http.MethodGet, "/healthz", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	got := decode[HealthResponse](t, rec)
	if got != (HealthResponse{Status: "ok", Version: "1.2.3"}) {
		t.Errorf("body = %+v", got)
	}
}

func TestHealthzUnhealthy(t *testing.T) {
	tests := []struct {
		name       string
		err        error
		wantReason string
	}{
		{"typed reason", &UnhealthyError{Reason: "schema_outdated", Err: errors.New("at 1, want 2")}, "schema_outdated"},
		{"wrapped typed reason", errors.Join(errors.New("x"), &UnhealthyError{Reason: "database_unavailable", Err: errors.New("closed")}), "database_unavailable"},
		{"untyped error hides details", errors.New("open /data/secret/path: permission denied"), "unavailable"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			h := NewPublic(PublicOptions{Version: "dev", Health: func(context.Context) error { return tt.err }})
			rec := do(t, h, http.MethodGet, "/healthz", nil)
			if rec.Code != http.StatusServiceUnavailable {
				t.Fatalf("status = %d, want 503", rec.Code)
			}
			got := decode[HealthResponse](t, rec)
			want := HealthResponse{Status: "unavailable", Version: "dev", Reason: tt.wantReason}
			if got != want {
				t.Errorf("body = %+v, want %+v", got, want)
			}
			if strings.Contains(rec.Body.String(), "/data") {
				t.Errorf("body leaks internal error detail: %s", rec.Body)
			}
		})
	}
}

func TestHealthzTimesOut(t *testing.T) {
	h := NewPublic(PublicOptions{HealthTimeout: 50 * time.Millisecond, Health: func(ctx context.Context) error {
		<-ctx.Done()
		return ctx.Err()
	}})
	start := time.Now()
	rec := do(t, h, http.MethodGet, "/healthz", nil)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rec.Code)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Errorf("health check took %v", d)
	}
}

func TestNotFoundIsJSON(t *testing.T) {
	h := NewPublic(PublicOptions{Health: func(context.Context) error { return nil }})
	for _, path := range []string{"/", "/v1/pages", "/metrics", "/internal/status", "/healthz/x"} {
		rec := do(t, h, http.MethodGet, path, nil)
		if rec.Code != http.StatusNotFound {
			t.Errorf("GET %s = %d, want 404", path, rec.Code)
			continue
		}
		if got := decode[ErrorResponse](t, rec); got.Error != "not_found" {
			t.Errorf("GET %s body = %+v", path, got)
		}
	}
}

func TestMethodNotAllowedIsJSON(t *testing.T) {
	h := NewPublic(PublicOptions{Health: func(context.Context) error { return nil }})
	rec := do(t, h, http.MethodPost, "/healthz", nil)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
	if got := decode[ErrorResponse](t, rec); got.Error != "method_not_allowed" {
		t.Errorf("body = %+v", got)
	}
}

// testRouter mounts extra routes behind the same middleware as the public handler.
func testRouter(logger *slog.Logger, maxBody int64, mount func(chi.Router)) http.Handler {
	r := chi.NewRouter()
	r.Use(logRequests(logger), recoverPanics(logger), limitBody(maxBody))
	r.NotFound(notFound)
	mount(r)
	return r
}

func TestPanicRecovery(t *testing.T) {
	var logs bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logs, &slog.HandlerOptions{Level: slog.LevelInfo}))
	h := testRouter(logger, DefaultMaxBodyBytes, func(r chi.Router) {
		r.Get("/boom/{id}", func(http.ResponseWriter, *http.Request) { panic("kaboom") })
	})

	rec := do(t, h, http.MethodGet, "/boom/secret-page-id", nil, "Authorization", "Bearer tok3n")
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", rec.Code)
	}
	if got := decode[ErrorResponse](t, rec); got.Error != "internal_error" {
		t.Errorf("body = %+v", got)
	}
	out := logs.String()
	for _, want := range []string{"panic in http handler", "kaboom", `"route":"/boom/{id}"`, `"status":500`} {
		if !strings.Contains(out, want) {
			t.Errorf("log missing %q:\n%s", want, out)
		}
	}
	for _, leak := range []string{"secret-page-id", "tok3n", "192.0.2.1"} {
		if strings.Contains(out, leak) {
			t.Errorf("log leaks %q:\n%s", leak, out)
		}
	}
}

func TestNoInfoLevelAccessLog(t *testing.T) {
	var logs bytes.Buffer
	h := NewPublic(PublicOptions{
		Logger: slog.New(slog.NewJSONHandler(&logs, &slog.HandlerOptions{Level: slog.LevelInfo})),
		Health: func(context.Context) error { return nil },
	})
	do(t, h, http.MethodGet, "/healthz", nil)
	do(t, h, http.MethodGet, "/nope", nil)
	if logs.Len() != 0 {
		t.Errorf("successful and 4xx requests logged at info:\n%s", logs.String())
	}
}

func TestDebugAccessLogOmitsClientDetails(t *testing.T) {
	var logs bytes.Buffer
	h := NewPublic(PublicOptions{
		Logger: slog.New(slog.NewJSONHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug})),
		Health: func(context.Context) error { return nil },
	})
	do(t, h, http.MethodGet, "/healthz?token=abc", nil, "Authorization", "Bearer tok3n", "X-Forwarded-For", "198.51.100.9")
	out := logs.String()
	if !strings.Contains(out, `"route":"/healthz"`) {
		t.Errorf("debug log missing request:\n%s", out)
	}
	// httptest requests come from 192.0.2.1.
	for _, leak := range []string{"tok3n", "abc", "192.0.2.1", "198.51.100.9"} {
		if strings.Contains(out, leak) {
			t.Errorf("debug log leaks %q:\n%s", leak, out)
		}
	}
}

func TestBodyLimit(t *testing.T) {
	h := testRouter(slog.New(slog.DiscardHandler), 16, func(r chi.Router) {
		r.Post("/echo", func(w http.ResponseWriter, r *http.Request) {
			b, err := io.ReadAll(r.Body)
			var mbe *http.MaxBytesError
			if errors.As(err, &mbe) {
				writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
				return
			}
			_, _ = w.Write(b)
		})
	})

	if rec := do(t, h, http.MethodPost, "/echo", strings.NewReader("small")); rec.Code != http.StatusOK || rec.Body.String() != "small" {
		t.Errorf("small body: %d %q", rec.Code, rec.Body)
	}
	// Declared too large: rejected before the handler runs.
	if rec := do(t, h, http.MethodPost, "/echo", strings.NewReader(strings.Repeat("x", 17))); rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("large body: status %d, want 413", rec.Code)
	}
	// Undeclared length (chunked): the reader enforces the cap.
	req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/echo", io.MultiReader(strings.NewReader(strings.Repeat("x", 17))))
	req.ContentLength = -1
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("chunked large body: status %d, want 413", rec.Code)
	}
}

func TestInternalStatus(t *testing.T) {
	started := time.Date(2026, 10, 24, 12, 0, 0, 0, time.UTC)
	opts := InternalOptions{
		Version:        "1.2.3",
		Registry:       metrics.New("1.2.3", func() int { return 0 }),
		Started:        started,
		Now:            func() time.Time { return started.Add(90*time.Second + 500*time.Millisecond) },
		ActiveSessions: func() int { return 0 },
		DBPing:         func(context.Context) error { return nil },
		SchemaVersion:  func(context.Context) (int64, error) { return 1, nil },
		ActivePages:    func(context.Context) (int64, error) { return 3, nil },
	}
	rec := do(t, NewInternal(opts), http.MethodGet, "/internal/status", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	want := Status{Version: "1.2.3", UptimeSeconds: 90, ActiveSessions: 0, ActivePages: 3, DBOK: true, SchemaVersion: 1}
	if got := decode[Status](t, rec); got != want {
		t.Errorf("status = %+v, want %+v", got, want)
	}

	// Exact field names are part of the contract with deploy tooling.
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"version", "uptime_seconds", "active_sessions", "active_pages", "db_ok", "schema_version"} {
		if _, ok := raw[k]; !ok {
			t.Errorf("status JSON missing %q: %s", k, rec.Body)
		}
	}
}

func TestInternalStatusDatabaseDown(t *testing.T) {
	down := errors.New("sql: database is closed")
	opts := InternalOptions{
		Version:        "dev",
		Registry:       metrics.New("dev", func() int { return 0 }),
		Started:        time.Now(),
		ActiveSessions: func() int { return 0 },
		DBPing:         func(context.Context) error { return down },
		SchemaVersion:  func(context.Context) (int64, error) { return 0, down },
		ActivePages:    func(context.Context) (int64, error) { return 0, down },
	}
	rec := do(t, NewInternal(opts), http.MethodGet, "/internal/status", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if got := decode[Status](t, rec); got.DBOK || got.ActivePages != 0 || got.SchemaVersion != 0 {
		t.Errorf("status = %+v, want db_ok=false and zero db fields", got)
	}
}

func TestMetrics(t *testing.T) {
	opts := InternalOptions{
		Registry:       metrics.New("1.2.3", func() int { return 0 }),
		Started:        time.Now(),
		ActiveSessions: func() int { return 0 },
		DBPing:         func(context.Context) error { return nil },
		SchemaVersion:  func(context.Context) (int64, error) { return 1, nil },
		ActivePages:    func(context.Context) (int64, error) { return 0, nil },
	}
	rec := do(t, NewInternal(opts), http.MethodGet, "/metrics", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{
		`pttd_build_info{version="1.2.3"} 1`,
		"pttd_active_sessions 0",
		"go_goroutines ",
		"process_start_time_seconds ",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("metrics missing %q", want)
		}
	}
}

func TestInternalNotFound(t *testing.T) {
	h := NewInternal(InternalOptions{Registry: metrics.New("dev", func() int { return 0 })})
	rec := do(t, h, http.MethodGet, "/healthz", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	if got := decode[ErrorResponse](t, rec); got.Error != "not_found" {
		t.Errorf("body = %+v", got)
	}
}
