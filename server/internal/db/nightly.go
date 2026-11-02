package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

const (
	nightlyPrefix = "nightly-"
	// NightlyKeep is how many of the newest nightly snapshots are retained.
	NightlyKeep = 7
)

// NightlySnapshot writes "nightly-<UTC timestamp>-v<schema version>.db" into dir with Snapshot,
// runs PRAGMA quick_check on the copy, and removes the copy and fails unless the check says ok.
// Only then does it delete all but the NightlyKeep newest nightly snapshots. Other files in dir
// (pre-migration snapshots, restored copies) are never touched. It returns the new file's path
// and size.
func NightlySnapshot(ctx context.Context, m *Migrator, dir string, now time.Time) (string, int64, error) {
	version, _, err := m.Versions(ctx)
	if err != nil {
		return "", 0, err
	}
	name := fmt.Sprintf("%s%s-v%d.db", nightlyPrefix, now.UTC().Format(snapshotTimeFormat), version)
	path := filepath.Join(dir, name)
	if _, err := os.Lstat(path); err == nil {
		return "", 0, fmt.Errorf("snapshot %s already exists", name)
	}
	if err := Snapshot(ctx, m.db, path); err != nil {
		_ = os.Remove(path)
		return "", 0, err
	}
	if err := quickCheck(ctx, path); err != nil {
		_ = os.Remove(path)
		return "", 0, fmt.Errorf("snapshot %s failed verification and was deleted: %w", name, err)
	}
	info, err := os.Stat(path)
	if err != nil {
		return "", 0, err
	}
	if err := pruneNightly(dir, NightlyKeep); err != nil {
		return "", 0, fmt.Errorf("snapshot %s written, but pruning old nightly snapshots failed: %w", name, err)
	}
	return path, info.Size(), nil
}

// quickCheck opens the snapshot read-only, so the check cannot alter the file.
func quickCheck(ctx context.Context, path string) (err error) {
	d, err := sql.Open("sqlite", "file:"+path+"?mode=ro")
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, d.Close()) }()

	rows, err := d.QueryContext(ctx, "PRAGMA quick_check")
	if err != nil {
		return err
	}
	defer func() { _ = rows.Close() }()
	var results []string
	for rows.Next() {
		var line string
		if err := rows.Scan(&line); err != nil {
			return err
		}
		results = append(results, line)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if len(results) != 1 || results[0] != "ok" {
		return fmt.Errorf("quick_check reported %q", results)
	}
	return nil
}

func pruneNightly(dir string, keep int) error {
	snaps, err := listSnapshots(dir, nightlyPrefix)
	if err != nil {
		return err
	}
	var errs []error
	for _, s := range snaps[:max(0, len(snaps)-keep)] {
		if err := os.Remove(filepath.Join(dir, s.name)); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}
