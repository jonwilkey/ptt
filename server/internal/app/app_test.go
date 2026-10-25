package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/httpapi"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/migrations"
)

func newApp(t *testing.T, migrate bool) (*App, func() error) {
	t.Helper()
	ctx := t.Context()
	sqlDB, err := db.Open(ctx, filepath.Join(t.TempDir(), "pttd.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sqlDB.Close() })
	m, err := db.NewMigrator(sqlDB, nil)
	if err != nil {
		t.Fatal(err)
	}
	if migrate {
		if err := m.Up(ctx); err != nil {
			t.Fatal(err)
		}
	}
	return New("test", slog.New(slog.DiscardHandler), sqlDB, m), sqlDB.Close
}

func get(t *testing.T, h http.Handler, path string) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil))
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode %s: %v", rec.Body, err)
	}
	return rec.Code, body
}

func TestHealthzWithRealDatabase(t *testing.T) {
	a, closeDB := newApp(t, true)
	h := a.PublicHandler()

	code, body := get(t, h, "/healthz")
	if code != http.StatusOK || body["status"] != "ok" {
		t.Fatalf("healthy: %d %v", code, body)
	}

	if err := closeDB(); err != nil {
		t.Fatal(err)
	}
	code, body = get(t, h, "/healthz")
	if code != http.StatusServiceUnavailable || body["reason"] != "database_unavailable" {
		t.Fatalf("closed db: %d %v", code, body)
	}
}

func TestHealthzUnmigratedDatabase(t *testing.T) {
	a, _ := newApp(t, false)
	code, body := get(t, a.PublicHandler(), "/healthz")
	if code != http.StatusServiceUnavailable || body["reason"] != "schema_outdated" {
		t.Fatalf("unmigrated: %d %v", code, body)
	}
	var ue *httpapi.UnhealthyError
	if err := a.Health(t.Context()); !errors.As(err, &ue) || !errors.Is(err, db.ErrSchemaBehind) {
		t.Fatalf("Health() = %v", err)
	}
}

func TestInternalStatusWithRealDatabase(t *testing.T) {
	a, closeDB := newApp(t, true)
	h := a.InternalHandler()

	code, body := get(t, h, "/internal/status")
	if code != http.StatusOK {
		t.Fatalf("status code %d", code)
	}
	// Migrations are numbered 1..N without gaps, so the newest version is the file count.
	files, err := fs.Glob(migrations.FS, "*.sql")
	if err != nil {
		t.Fatal(err)
	}
	latest := float64(len(files))
	want := map[string]any{
		"version": "test", "active_sessions": 0.0, "active_pages": 0.0,
		"db_ok": true, "schema_version": latest, "binary_schema_version": latest,
	}
	for k, v := range want {
		if body[k] != v {
			t.Errorf("%s = %v, want %v", k, body[k], v)
		}
	}

	if err := closeDB(); err != nil {
		t.Fatal(err)
	}
	if _, body := get(t, h, "/internal/status"); body["db_ok"] != false {
		t.Errorf("db_ok after close = %v, want false", body["db_ok"])
	}
}

func freeAddr(t *testing.T) string {
	t.Helper()
	var lc net.ListenConfig
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()
	return addr
}

func TestServeStartsMigratesAndShutsDown(t *testing.T) {
	dir := t.TempDir()
	cfg := config.Config{
		ListenAddr:   freeAddr(t),
		InternalAddr: freeAddr(t),
		DBPath:       filepath.Join(dir, "pttd.db"),
		SnapshotDir:  filepath.Join(dir, "snapshots"),
	}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- Serve(ctx, cfg, slog.New(slog.DiscardHandler), "test") }()

	client := &http.Client{Timeout: time.Second}
	healthy := false
	for deadline := time.Now().Add(10 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+cfg.ListenAddr+"/healthz", nil)
		resp, err := client.Do(req)
		if err != nil {
			continue
		}
		_ = resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			healthy = true
			break
		}
	}
	if !healthy {
		cancel()
		t.Fatalf("server never became healthy: %v", <-done)
	}

	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+cfg.InternalAddr+"/metrics", nil)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("/metrics = %d", resp.StatusCode)
	}

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Serve returned %v, want nil after graceful shutdown", err)
		}
	case <-time.After(ShutdownTimeout + 5*time.Second):
		t.Fatal("Serve did not return after cancel")
	}
}

func TestServeFailsOnPortConflict(t *testing.T) {
	var lc net.ListenConfig
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = ln.Close() }()

	dir := t.TempDir()
	cfg := config.Config{
		ListenAddr:   freeAddr(t),
		InternalAddr: ln.Addr().String(),
		DBPath:       filepath.Join(dir, "pttd.db"),
		SnapshotDir:  filepath.Join(dir, "snapshots"),
	}
	err = Serve(t.Context(), cfg, slog.New(slog.DiscardHandler), "test")
	if err == nil {
		t.Fatal("Serve succeeded despite a port conflict")
	}
	if want := fmt.Sprintf("listen on %s", ln.Addr()); !strings.Contains(err.Error(), want) {
		t.Errorf("error %q does not mention %q", err, want)
	}
}

func TestFakeFCMEndpointOnlyInFakeMode(t *testing.T) {
	a, _ := newApp(t, true)
	rec := httptest.NewRecorder()
	a.InternalHandler().ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/internal/fake-fcm", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("without the fake sender: %d, want 404", rec.Code)
	}

	f := push.NewFake()
	a.fake = f
	if err := f.Send(t.Context(), push.Message{Token: "tok", Data: map[string]string{"type": "test"}, Priority: push.High}); err != nil {
		t.Fatal(err)
	}
	rec = httptest.NewRecorder()
	a.InternalHandler().ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/internal/fake-fcm", nil))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"type":"test"`) {
		t.Errorf("with the fake sender: %d %s", rec.Code, rec.Body)
	}
}
