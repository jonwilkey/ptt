package db

import (
	"database/sql"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"testing/fstest"
	"time"

	"github.com/jonwilkey/ptt/server/migrations"
)

// migrationsPlus returns the embedded migrations plus a fake newer one, so a database migrated
// with only the embedded set looks like one from the previous release.
func migrationsPlus(t *testing.T) fs.FS {
	t.Helper()
	fsys := fstest.MapFS{}
	entries, err := fs.ReadDir(migrations.FS, ".")
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		b, err := fs.ReadFile(migrations.FS, e.Name())
		if err != nil {
			t.Fatal(err)
		}
		fsys[e.Name()] = &fstest.MapFile{Data: b}
	}
	fsys["99999_test_next.sql"] = &fstest.MapFile{Data: []byte(
		"-- +goose Up\nCREATE TABLE next_release (id TEXT PRIMARY KEY) STRICT;\n" +
			"-- +goose Down\nDROP TABLE next_release;\n")}
	return fsys
}

func hasTable(t *testing.T, db *sql.DB, name string) bool {
	t.Helper()
	var n int
	err := db.QueryRowContext(t.Context(),
		`SELECT COUNT(*) FROM sqlite_schema WHERE type = 'table' AND name = ?`, name).Scan(&n)
	if err != nil {
		t.Fatal(err)
	}
	return n == 1
}

func TestMigrateUpSnapshotsExistingDatabaseWithPendingMigrations(t *testing.T) {
	db, _ := openTemp(t)
	ctx := t.Context()
	snapDir := filepath.Join(t.TempDir(), "snapshots")

	// The previous release migrates and stores some data.
	old := mustMigrator(t, db)
	if err := old.Up(ctx); err != nil {
		t.Fatal(err)
	}
	oldVersion, _, err := old.Versions(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, `INSERT INTO users (id, display_name, created_at) VALUES ('u1', 'Parent One', 1)`); err != nil {
		t.Fatal(err)
	}

	// The new release starts with one more migration.
	next, err := newMigrator(db, migrationsPlus(t), nil)
	if err != nil {
		t.Fatal(err)
	}
	snap, err := next.MigrateUp(ctx, snapDir, testNow)
	if err != nil {
		t.Fatalf("MigrateUp: %v", err)
	}

	wantName := "pre-migrate-20261024T150405Z-v1.db"
	if oldVersion != 1 {
		t.Fatalf("test assumes the embedded migrations end at version 1, got %d; update wantName", oldVersion)
	}
	if snap != filepath.Join(snapDir, wantName) {
		t.Fatalf("snapshot = %q, want %q", snap, filepath.Join(snapDir, wantName))
	}
	if got := listDir(t, snapDir); !slices.Equal(got, []string{wantName}) {
		t.Fatalf("snapshot dir = %v, want [%s]", got, wantName)
	}
	if !hasTable(t, db, "next_release") {
		t.Fatal("live database was not migrated")
	}

	// The snapshot is the pre-migration state, with the data, and opens on its own.
	func() {
		sdb, err := Open(ctx, snap)
		if err != nil {
			t.Fatal(err)
		}
		// Closing the last connection checkpoints and removes the -wal/-shm files Open created.
		defer func() { _ = sdb.Close() }()
		if hasTable(t, sdb, "next_release") {
			t.Error("snapshot already contains the new migration")
		}
		var name string
		if err := sdb.QueryRowContext(ctx, `SELECT display_name FROM users WHERE id = 'u1'`).Scan(&name); err != nil || name != "Parent One" {
			t.Errorf("snapshot user = %q, %v; want Parent One", name, err)
		}
	}()

	// Running again is a no-op: nothing pending, no new snapshot.
	snap, err = next.MigrateUp(ctx, snapDir, testNow.Add(time.Hour))
	if err != nil || snap != "" {
		t.Fatalf("second MigrateUp = %q, %v; want no snapshot", snap, err)
	}
	if got := listDir(t, snapDir); len(got) != 1 {
		t.Fatalf("snapshot dir after no-op = %v", got)
	}
}

func TestPrunePreMigrateSnapshotsKeepsNewest(t *testing.T) {
	dir := t.TempDir()
	var all []string
	for i := range 8 {
		ts := testNow.Add(time.Duration(i) * time.Hour).Format(snapshotTimeFormat)
		all = append(all, "pre-migrate-"+ts+"-v"+string(rune('1'+i))+".db")
	}
	others := []string{"nightly-20261024T020000Z.db", "pre-migrate-notes.txt", "README"}
	for _, n := range append(slices.Clone(all), others...) {
		if err := os.WriteFile(filepath.Join(dir, n), nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}

	if err := PrunePreMigrateSnapshots(dir, 5); err != nil {
		t.Fatal(err)
	}
	want := append(slices.Clone(all[3:]), others...)
	slices.Sort(want)
	if got := listDir(t, dir); !slices.Equal(got, want) {
		t.Fatalf("after prune:\n got %v\nwant %v", got, want)
	}
}

func TestPrunePreMigrateSnapshotsMissingDir(t *testing.T) {
	if err := PrunePreMigrateSnapshots(filepath.Join(t.TempDir(), "nope"), 5); err != nil {
		t.Fatal(err)
	}
}

func TestSnapshotNowRetainsFive(t *testing.T) {
	db, _ := openTemp(t)
	m := mustMigrator(t, db)
	ctx := t.Context()
	if err := m.Up(ctx); err != nil {
		t.Fatal(err)
	}
	snapDir := filepath.Join(t.TempDir(), "snapshots")
	var made []string
	for i := range 7 {
		p, err := m.SnapshotNow(ctx, snapDir, testNow.Add(time.Duration(i)*time.Minute))
		if err != nil {
			t.Fatal(err)
		}
		made = append(made, filepath.Base(p))
	}
	if got := listDir(t, snapDir); !slices.Equal(got, made[2:]) {
		t.Fatalf("kept %v, want %v", got, made[2:])
	}
}
