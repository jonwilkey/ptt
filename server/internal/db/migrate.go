package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"

	"github.com/pressly/goose/v3"

	"github.com/jonwilkey/ptt/server/migrations"
)

// ErrSchemaBehind means the database has migrations this binary knows about but has not applied.
var ErrSchemaBehind = errors.New("database schema is behind this binary")

// Migrator applies the embedded goose migrations to a database.
type Migrator struct {
	db       *sql.DB
	provider *goose.Provider
	logger   *slog.Logger
}

// NewMigrator returns a Migrator for db using the migrations embedded in the binary.
func NewMigrator(db *sql.DB, logger *slog.Logger) (*Migrator, error) {
	return newMigrator(db, migrations.FS, logger)
}

// newMigrator lets tests supply extra migrations to simulate a database from an older release.
func newMigrator(db *sql.DB, fsys fs.FS, logger *slog.Logger) (*Migrator, error) {
	opts := []goose.ProviderOption{
		// Go migrations registered globally (goose.AddMigration) would be invisible in review;
		// only the embedded SQL files count.
		goose.WithDisableGlobalRegistry(true),
	}
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	// goose's own verbose mode logs every SQL statement; logResults writes one line per
	// migration instead.
	p, err := goose.NewProvider(goose.DialectSQLite3, db, fsys, opts...)
	if err != nil {
		return nil, fmt.Errorf("load migrations: %w", err)
	}
	return &Migrator{db: db, provider: p, logger: logger}, nil
}

// Versions returns the database's current schema version and the latest version this binary
// carries. A fresh database is at version 0.
func (m *Migrator) Versions(ctx context.Context) (current, latest int64, err error) {
	current, latest, err = m.provider.GetVersions(ctx)
	if err != nil {
		return 0, 0, fmt.Errorf("read schema version: %w", err)
	}
	return current, latest, nil
}

// LatestVersion is the newest migration embedded in the binary; it needs no database access.
func (m *Migrator) LatestVersion() int64 {
	var latest int64
	for _, s := range m.provider.ListSources() {
		latest = max(latest, s.Version)
	}
	return latest
}

// Pending reports whether any embedded migration has not been applied.
func (m *Migrator) Pending(ctx context.Context) (bool, error) {
	pending, err := m.provider.HasPending(ctx)
	if err != nil {
		return false, fmt.Errorf("check pending migrations: %w", err)
	}
	return pending, nil
}

// Up applies every pending migration.
func (m *Migrator) Up(ctx context.Context) error {
	res, err := m.provider.Up(ctx)
	if err != nil {
		return fmt.Errorf("migrate up: %w", err)
	}
	m.logResults(ctx, res...)
	return nil
}

// Down rolls back the most recently applied migration.
func (m *Migrator) Down(ctx context.Context) error {
	res, err := m.provider.Down(ctx)
	if err != nil {
		return fmt.Errorf("migrate down: %w", err)
	}
	m.logResults(ctx, res)
	return nil
}

// DownTo rolls back migrations until the schema is at version.
func (m *Migrator) DownTo(ctx context.Context, version int64) error {
	res, err := m.provider.DownTo(ctx, version)
	if err != nil {
		return fmt.Errorf("migrate down to %d: %w", version, err)
	}
	m.logResults(ctx, res...)
	return nil
}

func (m *Migrator) logResults(ctx context.Context, results ...*goose.MigrationResult) {
	for _, r := range results {
		if r == nil || r.Source == nil {
			continue
		}
		m.logger.LogAttrs(ctx, slog.LevelInfo, "applied migration",
			slog.String("migration", r.Source.Path),
			slog.Int64("version", r.Source.Version),
			slog.String("direction", r.Direction),
			slog.Duration("duration", r.Duration),
		)
	}
}

// Status lists every known migration and whether it is applied, oldest first.
func (m *Migrator) Status(ctx context.Context) ([]*goose.MigrationStatus, error) {
	st, err := m.provider.Status(ctx)
	if err != nil {
		return nil, fmt.Errorf("migration status: %w", err)
	}
	return st, nil
}

// CheckSchema returns the current schema version, or an error wrapping ErrSchemaBehind if
// migrations are pending. A database that is ahead of the binary is accepted: migrations are
// expand-then-contract, so the previous release must keep working on the newer schema.
func (m *Migrator) CheckSchema(ctx context.Context) (int64, error) {
	current, latest, err := m.Versions(ctx)
	if err != nil {
		return 0, err
	}
	if current < latest {
		return current, fmt.Errorf("%w: at version %d, want %d", ErrSchemaBehind, current, latest)
	}
	return current, nil
}
