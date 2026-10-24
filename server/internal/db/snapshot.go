package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

const (
	preMigratePrefix = "pre-migrate-"
	// snapshotTimeFormat is fixed-width UTC, so file names sort chronologically.
	snapshotTimeFormat = "20060102T150405Z"
	// PreMigrateKeep is how many pre-migration snapshots are retained.
	PreMigrateKeep = 5
)

// Snapshot writes a consistent copy of db to path with VACUUM INTO. It is safe while the
// database is in use, and the result is a standalone file (no -wal or -shm) that can simply
// be copied back over the live database to restore it.
func Snapshot(ctx context.Context, db *sql.DB, path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("create snapshot directory: %w", err)
	}
	if _, err := db.ExecContext(ctx, "VACUUM INTO ?", path); err != nil {
		return fmt.Errorf("snapshot to %s: %w", path, err)
	}
	return nil
}

// MigrateUp applies pending migrations, first snapshotting the database into snapshotDir if
// it already holds data (an applied schema). The deploy rollback restores from that snapshot.
// It returns the snapshot path, or "" if no snapshot was needed.
func (m *Migrator) MigrateUp(ctx context.Context, snapshotDir string, now time.Time) (string, error) {
	snap, err := m.SnapshotIfPending(ctx, snapshotDir, now)
	if err != nil {
		return "", err
	}
	return snap, m.Up(ctx)
}

// SnapshotIfPending takes a pre-migration snapshot when migrations are pending and the
// database is not empty, then prunes old pre-migration snapshots. A fresh database (version 0)
// has nothing worth restoring, and an up-to-date one is not about to change.
func (m *Migrator) SnapshotIfPending(ctx context.Context, snapshotDir string, now time.Time) (string, error) {
	pending, err := m.Pending(ctx)
	if err != nil || !pending {
		return "", err
	}
	current, _, err := m.Versions(ctx)
	if err != nil || current == 0 {
		return "", err
	}
	return m.SnapshotNow(ctx, snapshotDir, now)
}

// SnapshotNow unconditionally writes a pre-migration snapshot of the database at its current
// version and prunes old ones. It is used before destructive operations such as migrate down.
func (m *Migrator) SnapshotNow(ctx context.Context, snapshotDir string, now time.Time) (string, error) {
	current, _, err := m.Versions(ctx)
	if err != nil {
		return "", err
	}
	name := fmt.Sprintf("%s%s-v%d.db", preMigratePrefix, now.UTC().Format(snapshotTimeFormat), current)
	path := filepath.Join(snapshotDir, name)
	if err := Snapshot(ctx, m.db, path); err != nil {
		return "", err
	}
	m.logger.Info("wrote pre-migration snapshot", slog.String("path", path), slog.Int64("schema_version", current))
	if err := PrunePreMigrateSnapshots(snapshotDir, PreMigrateKeep); err != nil {
		// The new snapshot exists, so migrating is still safe; a full disk will show up soon
		// enough without blocking a deploy on cleanup.
		m.logger.Warn("prune old pre-migration snapshots", slog.String("error", err.Error()))
	}
	return path, nil
}

// PrunePreMigrateSnapshots deletes all but the newest keep pre-migration snapshots in dir.
// Other files in dir (such as the nightly snapshots) are left alone.
func PrunePreMigrateSnapshots(dir string, keep int) error {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var names []string
	for _, e := range entries {
		n := e.Name()
		if e.Type().IsRegular() && strings.HasPrefix(n, preMigratePrefix) && strings.HasSuffix(n, ".db") {
			names = append(names, n)
		}
	}
	if len(names) <= keep {
		return nil
	}
	// Names embed a fixed-width UTC timestamp right after the prefix, so lexical order is age.
	slices.Sort(names)
	var errs []error
	for _, n := range names[:len(names)-keep] {
		if err := os.Remove(filepath.Join(dir, n)); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}
