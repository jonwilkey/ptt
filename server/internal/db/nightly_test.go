package db

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNightlySnapshot(t *testing.T) {
	m := migratedTemp(t)
	dir := filepath.Join(t.TempDir(), "snaps")
	version, _, err := m.Versions(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)

	path, size, err := NightlySnapshot(t.Context(), m, dir, now)
	if err != nil {
		t.Fatal(err)
	}
	wantName := fmt.Sprintf("nightly-20261103T100000.000Z-v%d.db", version)
	if filepath.Base(path) != wantName {
		t.Errorf("name = %s, want %s", filepath.Base(path), wantName)
	}
	info, err := os.Stat(path)
	if err != nil || info.Size() != size || size == 0 {
		t.Fatalf("stat = %v, %v; reported size %d", info, err, size)
	}
	if err := quickCheck(t.Context(), path); err != nil {
		t.Errorf("quick_check on the copy: %v", err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 1 {
		t.Errorf("snapshot dir = %v, %v; want only the snapshot (no -wal/-shm)", entries, err)
	}
}

func TestNightlySnapshotPrunesOnlyNightly(t *testing.T) {
	m := migratedTemp(t)
	dir := t.TempDir()
	others := []string{
		"pre-migrate-20260101T000000.000Z-v1.db", "restored-20260101T000000.000Z.db", "notes.txt",
		"nightly-garbage.db", "nightly-20260101T000000.000Z-v1.db.tmp",
	}
	var nightly []string
	for i := range 9 {
		nightly = append(nightly, fmt.Sprintf("nightly-2026010%dT020000.000Z-v1.db", i+1))
	}
	touch(t, dir, others...)
	touch(t, dir, nightly...)

	now := time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)
	path, _, err := NightlySnapshot(t.Context(), m, dir, now)
	if err != nil {
		t.Fatal(err)
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var gotNightly, gotOthers []string
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "nightly-") && e.Name() != "nightly-garbage.db" && !strings.HasSuffix(e.Name(), ".tmp") {
			gotNightly = append(gotNightly, e.Name())
		} else {
			gotOthers = append(gotOthers, e.Name())
		}
	}
	if len(gotNightly) != NightlyKeep {
		t.Fatalf("nightly files = %v, want %d", gotNightly, NightlyKeep)
	}
	if gotNightly[len(gotNightly)-1] != filepath.Base(path) || gotNightly[0] != nightly[3] {
		t.Errorf("kept %v; want the 6 newest old ones from %s plus the new one", gotNightly, nightly[3])
	}
	if len(gotOthers) != len(others) {
		t.Errorf("other files = %v, want %v untouched", gotOthers, others)
	}
}

func TestNightlySnapshotRefusesExistingTarget(t *testing.T) {
	m := migratedTemp(t)
	dir := t.TempDir()
	touch(t, dir, "nightly-20260101T020000.000Z-v1.db")

	// A same-named file is never overwritten or deleted, and the failed run prunes nothing.
	now := time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)
	version, _, _ := m.Versions(t.Context())
	existing := filepath.Join(dir, fmt.Sprintf("nightly-%s-v%d.db", now.Format(snapshotTimeFormat), version))
	if err := os.WriteFile(existing, []byte("keep me"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := NightlySnapshot(t.Context(), m, dir, now); err == nil {
		t.Fatal("expected an error")
	}
	if b, err := os.ReadFile(existing); err != nil || string(b) != "keep me" {
		t.Errorf("existing file = %q, %v", b, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "nightly-20260101T020000.000Z-v1.db")); err != nil {
		t.Errorf("old snapshot pruned by a failed run: %v", err)
	}
}

func TestQuickCheckRejectsGarbage(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.db")
	if err := os.WriteFile(path, []byte(strings.Repeat("not a database ", 500)), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := quickCheck(t.Context(), path); err == nil {
		t.Error("quick_check accepted a file that is not a database")
	}
}
