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
	"strconv"
	"strings"
	"time"
)

const (
	preMigratePrefix = "pre-migrate-"
	// snapshotTimeFormat is fixed-width UTC, so file names sort chronologically.
	snapshotTimeFormat = "20060102T150405Z"
	// PreMigrateKeep is how many of the newest pre-migration snapshots are always retained.
	PreMigrateKeep = 5
	// PreMigrateKeepVersions is how many distinct schema versions keep their first (oldest)
	// snapshot regardless of age. Total retention is at most PreMigrateKeep +
	// PreMigrateKeepVersions files.
	PreMigrateKeepVersions = 5
	// SnapshotReuseWindow is how recent a snapshot at the current version must be to be reused
	// instead of taking another. A container crash-looping on a failed migration would otherwise take
	// a fresh snapshot every restart and rotate the real pre-deploy one out.
	SnapshotReuseWindow = 10 * time.Minute
)

// Snapshot writes a consistent copy of db to path with VACUUM INTO. It is safe while the
// database is in use, and the result is a standalone file. To restore it, stop pttd, copy the
// snapshot over the live database file, and delete the live -wal and -shm files (they belong
// to the database being replaced).
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

// SnapshotNow writes a pre-migration snapshot of the database at its current version and
// prunes old ones. If a snapshot at the current version was taken within SnapshotReuseWindow,
// that one is returned instead. It is also used before destructive operations such as
// migrate down.
func (m *Migrator) SnapshotNow(ctx context.Context, snapshotDir string, now time.Time) (string, error) {
	current, _, err := m.Versions(ctx)
	if err != nil {
		return "", err
	}
	now = now.UTC()

	existing, err := listPreMigrate(snapshotDir)
	if err != nil {
		return "", fmt.Errorf("list snapshots: %w", err)
	}
	for _, s := range slices.Backward(existing) {
		if s.version != current {
			continue
		}
		if age := now.Sub(s.at); age >= 0 && age < SnapshotReuseWindow {
			path := filepath.Join(snapshotDir, s.name)
			m.logger.Info("reusing recent pre-migration snapshot",
				slog.String("path", path), slog.Int64("schema_version", current), slog.Duration("age", age))
			return path, nil
		}
		break
	}

	name := fmt.Sprintf("%s%s-v%d.db", preMigratePrefix, now.Format(snapshotTimeFormat), current)
	path := filepath.Join(snapshotDir, name)
	if err := Snapshot(ctx, m.db, path); err != nil {
		return "", err
	}
	m.logger.Info("wrote pre-migration snapshot", slog.String("path", path), slog.Int64("schema_version", current))
	if err := PrunePreMigrateSnapshots(snapshotDir, PreMigrateKeep, PreMigrateKeepVersions); err != nil {
		// The new snapshot exists, so migrating is still safe; a full disk will show up soon
		// enough without blocking a deploy on cleanup.
		m.logger.Warn("prune old pre-migration snapshots", slog.String("error", err.Error()))
	}
	return path, nil
}

type snapshotFile struct {
	name    string
	at      time.Time
	version int64
}

// parseSnapshotName parses "pre-migrate-<timestamp>-v<version>.db".
func parseSnapshotName(name string) (snapshotFile, bool) {
	rest, ok := strings.CutPrefix(name, preMigratePrefix)
	if !ok {
		return snapshotFile{}, false
	}
	rest, ok = strings.CutSuffix(rest, ".db")
	if !ok {
		return snapshotFile{}, false
	}
	ts, v, ok := strings.Cut(rest, "-v")
	if !ok {
		return snapshotFile{}, false
	}
	at, err := time.Parse(snapshotTimeFormat, ts)
	if err != nil {
		return snapshotFile{}, false
	}
	version, err := strconv.ParseInt(v, 10, 64)
	if err != nil || version < 0 {
		return snapshotFile{}, false
	}
	return snapshotFile{name: name, at: at, version: version}, true
}

// listPreMigrate returns the pre-migration snapshots in dir, oldest first. Files whose names
// don't parse are ignored (and therefore never pruned).
func listPreMigrate(dir string) ([]snapshotFile, error) {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []snapshotFile
	for _, e := range entries {
		if !e.Type().IsRegular() {
			continue
		}
		if s, ok := parseSnapshotName(e.Name()); ok {
			out = append(out, s)
		}
	}
	// Fixed-width UTC timestamps right after the prefix: lexical order is age order.
	slices.SortFunc(out, func(a, b snapshotFile) int { return strings.Compare(a.name, b.name) })
	return out, nil
}

// PrunePreMigrateSnapshots deletes pre-migration snapshots in dir except the newest keep and
// the oldest snapshot of each of the keepVersions highest schema versions. The oldest one per
// version is the true pre-deploy state; later ones at the same version come from restarts.
// Other files in dir (such as the nightly snapshots) are left alone.
func PrunePreMigrateSnapshots(dir string, keep, keepVersions int) error {
	snaps, err := listPreMigrate(dir)
	if err != nil {
		return err
	}
	retain := make(map[string]bool, keep+keepVersions)
	for _, s := range snaps[max(0, len(snaps)-keep):] {
		retain[s.name] = true
	}

	firstByVersion := map[int64]string{}
	var versions []int64
	for _, s := range snaps {
		if _, seen := firstByVersion[s.version]; !seen {
			firstByVersion[s.version] = s.name
			versions = append(versions, s.version)
		}
	}
	slices.Sort(versions)
	for _, v := range versions[max(0, len(versions)-keepVersions):] {
		retain[firstByVersion[v]] = true
	}

	var errs []error
	for _, s := range snaps {
		if retain[s.name] {
			continue
		}
		if err := os.Remove(filepath.Join(dir, s.name)); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}
