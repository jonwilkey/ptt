package db

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

func openTemp(t *testing.T) (*sql.DB, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "data", "pttd.db")
	db, err := Open(t.Context(), path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db, path
}

func mustMigrator(t *testing.T, db *sql.DB) *Migrator {
	t.Helper()
	m, err := NewMigrator(db, nil)
	if err != nil {
		t.Fatalf("NewMigrator: %v", err)
	}
	return m
}

func userTables(t *testing.T, db *sql.DB) []string {
	t.Helper()
	rows, err := db.QueryContext(t.Context(),
		`SELECT name FROM sqlite_schema WHERE type = 'table'
		   AND name NOT LIKE 'sqlite_%' AND name <> 'goose_db_version' ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var names []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			t.Fatal(err)
		}
		names = append(names, n)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return names
}

func TestOpenAppliesPragmas(t *testing.T) {
	db, _ := openTemp(t)
	// Use several connections so the per-connection pragmas are exercised beyond the first.
	db.SetMaxIdleConns(0)
	ctx := t.Context()
	want := map[string]string{
		"journal_mode": "wal",
		"foreign_keys": "1",
		"busy_timeout": "5000",
		"synchronous":  "1", // NORMAL
		"temp_store":   "2", // MEMORY
	}
	for pragma, v := range want {
		var got string
		if err := db.QueryRowContext(ctx, "PRAGMA "+pragma).Scan(&got); err != nil {
			t.Fatalf("PRAGMA %s: %v", pragma, err)
		}
		if !strings.EqualFold(got, v) {
			t.Errorf("PRAGMA %s = %q, want %q", pragma, got, v)
		}
	}
}

func TestOpenRejectsQueryInPath(t *testing.T) {
	if _, err := Open(t.Context(), filepath.Join(t.TempDir(), "x.db?mode=ro")); err == nil {
		t.Fatal("Open succeeded, want error")
	}
}

// TestMigrationsUpDownUp is CI's migration up/down test: every migration must apply to an
// empty database, roll all the way back to nothing, and apply again cleanly.
func TestMigrationsUpDownUp(t *testing.T) {
	db, _ := openTemp(t)
	m := mustMigrator(t, db)
	ctx := t.Context()

	if err := m.Up(ctx); err != nil {
		t.Fatalf("first up: %v", err)
	}
	current, latest, err := m.Versions(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if current != latest || latest < 1 {
		t.Fatalf("after up: current=%d latest=%d", current, latest)
	}
	wantTables := []string{
		"active_pages", "audit_log", "devices", "enrollment_codes",
		"page_tombstones", "roster", "users",
	}
	if got := userTables(t, db); !slices.Equal(got, wantTables) {
		t.Fatalf("tables after up = %v, want %v", got, wantTables)
	}

	if err := m.DownTo(ctx, 0); err != nil {
		t.Fatalf("down to 0: %v", err)
	}
	if current, _, err := m.Versions(ctx); err != nil || current != 0 {
		t.Fatalf("after down: current=%d err=%v, want 0", current, err)
	}
	if got := userTables(t, db); len(got) != 0 {
		t.Fatalf("tables left after down to 0: %v", got)
	}

	if err := m.Up(ctx); err != nil {
		t.Fatalf("second up: %v", err)
	}
	if got := userTables(t, db); !slices.Equal(got, wantTables) {
		t.Fatalf("tables after second up = %v, want %v", got, wantTables)
	}
	if pending, err := m.Pending(ctx); err != nil || pending {
		t.Fatalf("Pending after up = %v, %v", pending, err)
	}
}

func TestSchemaConstraints(t *testing.T) {
	db, _ := openTemp(t)
	m := mustMigrator(t, db)
	ctx := t.Context()
	if err := m.Up(ctx); err != nil {
		t.Fatal(err)
	}
	exec := func(q string, args ...any) error {
		_, err := db.ExecContext(ctx, q, args...)
		return err
	}
	hash := make([]byte, 32)
	mustExec := func(q string, args ...any) {
		t.Helper()
		if err := exec(q, args...); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	mustExec(`INSERT INTO users (id, display_name, created_at) VALUES ('u1', 'Parent One', 1)`)
	mustExec(`INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d1', 'u1', 'Pixel', ?, 1)`, hash)

	bad := map[string]func() error{
		"device with unknown user (foreign key)": func() error {
			return exec(`INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d2', 'nope', 'x', ?, 1)`, make([]byte, 32))
		},
		"short token hash": func() error {
			return exec(`INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d3', 'u1', 'x', ?, 1)`, []byte{1})
		},
		"duplicate token hash": func() error {
			return exec(`INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d4', 'u1', 'x', ?, 1)`, hash)
		},
		"bad page level": func() error {
			return exec(`INSERT INTO active_pages (id, session_id, from_device, to_user, level, envelope, state, created_at)
			             VALUES ('p1', 's1', 'd1', 'u1', 'loud', x'00', 'ringing', 1)`)
		},
		"second roster row": func() error {
			return exec(`INSERT INTO roster (id, version, signed_roster, signer_device, updated_at) VALUES (2, 1, x'00', 'd1', 1)`)
		},
		"strict typing": func() error {
			return exec(`INSERT INTO page_tombstones (id, expires_at) VALUES ('t1', 'tomorrow')`)
		},
	}
	for name, f := range bad {
		if err := f(); err == nil {
			t.Errorf("%s: insert succeeded, want constraint error", name)
		}
	}

	mustExec(`INSERT INTO active_pages (id, session_id, from_device, to_user, level, envelope, state, created_at)
	          VALUES ('p1', 's1', 'd1', 'u1', 'urgent', x'00', 'ringing', 1)`)
	mustExec(`INSERT INTO roster (id, version, signed_roster, signer_device, updated_at) VALUES (1, 1, x'00', 'd1', 1)`)

	n, err := sqlcdb.New(db).CountActivePages(ctx)
	if err != nil || n != 1 {
		t.Fatalf("CountActivePages = %d, %v; want 1", n, err)
	}
}

func TestCheckSchema(t *testing.T) {
	db, _ := openTemp(t)
	m := mustMigrator(t, db)
	ctx := t.Context()

	if _, err := m.CheckSchema(ctx); !errors.Is(err, ErrSchemaBehind) {
		t.Fatalf("CheckSchema on empty db = %v, want ErrSchemaBehind", err)
	}
	if err := m.Up(ctx); err != nil {
		t.Fatal(err)
	}
	v, err := m.CheckSchema(ctx)
	if err != nil || v < 1 {
		t.Fatalf("CheckSchema after up = %d, %v", v, err)
	}
}

var testNow = time.Date(2026, 10, 24, 15, 4, 5, 0, time.UTC)

func listDir(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	return names
}

func TestMigrateUpFreshDatabaseTakesNoSnapshot(t *testing.T) {
	db, _ := openTemp(t)
	m := mustMigrator(t, db)
	snapDir := filepath.Join(t.TempDir(), "snapshots")

	snap, err := m.MigrateUp(t.Context(), snapDir, testNow)
	if err != nil {
		t.Fatal(err)
	}
	if snap != "" || len(listDir(t, snapDir)) != 0 {
		t.Fatalf("fresh database produced a snapshot: %q %v", snap, listDir(t, snapDir))
	}
}

func TestMigrateUpUpToDateTakesNoSnapshot(t *testing.T) {
	db, _ := openTemp(t)
	m := mustMigrator(t, db)
	ctx := t.Context()
	snapDir := filepath.Join(t.TempDir(), "snapshots")
	if err := m.Up(ctx); err != nil {
		t.Fatal(err)
	}

	snap, err := m.MigrateUp(ctx, snapDir, testNow)
	if err != nil {
		t.Fatal(err)
	}
	if snap != "" || len(listDir(t, snapDir)) != 0 {
		t.Fatalf("up-to-date database produced a snapshot: %q %v", snap, listDir(t, snapDir))
	}
}
