// Package db opens pttd's SQLite database and manages its schema.
//
// The database is modernc.org/sqlite (pure Go, so the binary builds with CGO_ENABLED=0). Typed
// queries live in internal/db/queries and are generated into internal/db/sqlcdb by sqlc.
package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	// Registers the "sqlite" database/sql driver.
	_ "modernc.org/sqlite"
)

// pragmas run on every new connection in the pool.
//
// temp_store=MEMORY keeps SQLite's transient files (sort spills, temp indices, statement
// journals) in memory. The container's root filesystem is read-only and only /data is
// writable; the image also sets SQLITE_TMPDIR=/data as a fallback for anything that still
// needs a temp file.
var pragmas = []string{
	"journal_mode(WAL)",
	"foreign_keys(1)",
	"busy_timeout(5000)",
	"synchronous(NORMAL)",
	"temp_store(MEMORY)",
}

// Open opens (creating if needed) the database at path, applies the connection pragmas, and
// verifies that they took effect. The caller must Close the returned DB.
func Open(ctx context.Context, path string) (*sql.DB, error) {
	if path == "" {
		return nil, errors.New("database path is empty")
	}
	if strings.Contains(path, "?") {
		return nil, fmt.Errorf("database path %q must not contain '?'", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create database directory: %w", err)
	}

	q := url.Values{}
	for _, p := range pragmas {
		q.Add("_pragma", p)
	}
	// BEGIN IMMEDIATE takes the write lock up front, so busy_timeout applies instead of a
	// read transaction failing with SQLITE_BUSY when it later tries to write.
	q.Set("_txlock", "immediate")

	db, err := sql.Open("sqlite", path+"?"+q.Encode())
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	if err := verify(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("open database %s: %w", path, err)
	}
	return db, nil
}

// OpenExisting is Open for inspection commands: it fails instead of creating the database (or
// its directory) when nothing exists at path.
func OpenExisting(ctx context.Context, path string) (*sql.DB, error) {
	info, err := os.Stat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("database %s does not exist", path)
	}
	if err != nil {
		return nil, fmt.Errorf("stat database: %w", err)
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("database %s is not a regular file", path)
	}
	return Open(ctx, path)
}

func verify(ctx context.Context, db *sql.DB) error {
	var mode string
	if err := db.QueryRowContext(ctx, "PRAGMA journal_mode").Scan(&mode); err != nil {
		return err
	}
	if !strings.EqualFold(mode, "wal") {
		return fmt.Errorf("journal_mode is %q, want wal", mode)
	}
	var fk int
	if err := db.QueryRowContext(ctx, "PRAGMA foreign_keys").Scan(&fk); err != nil {
		return err
	}
	if fk != 1 {
		return errors.New("foreign_keys is off")
	}
	return nil
}
