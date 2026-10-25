// Package app wires pttd's pieces together and runs the server.
package app

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"time"

	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/httpapi"
	"github.com/jonwilkey/ptt/server/internal/metrics"
)

// ShutdownTimeout is how long in-flight requests get to finish after SIGTERM. Docker's default
// stop grace period is 10 s, so compose must set stop_grace_period above this.
const ShutdownTimeout = 15 * time.Second

// App holds the server's long-lived dependencies.
type App struct {
	version  string
	logger   *slog.Logger
	db       *sql.DB
	migrator *db.Migrator
	queries  *sqlcdb.Queries
	started  time.Time
}

// New returns an App over an open, migrated database.
func New(version string, logger *slog.Logger, sqlDB *sql.DB, migrator *db.Migrator) *App {
	return &App{
		version:  version,
		logger:   logger,
		db:       sqlDB,
		migrator: migrator,
		queries:  sqlcdb.New(sqlDB),
		started:  time.Now(),
	}
}

// activeSessions is the number of open talk sessions. Sessions arrive with the WebSocket relay
// in milestone 4; until then there are none, which is what the deploy workflow waits for.
func (a *App) activeSessions() int { return 0 }

// Health reports whether the database answers and the schema is fully migrated.
func (a *App) Health(ctx context.Context) error {
	if err := a.db.PingContext(ctx); err != nil {
		return &httpapi.UnhealthyError{Reason: "database_unavailable", Err: err}
	}
	if _, err := a.migrator.CheckSchema(ctx); err != nil {
		reason := "database_unavailable"
		if errors.Is(err, db.ErrSchemaBehind) {
			reason = "schema_outdated"
		}
		return &httpapi.UnhealthyError{Reason: reason, Err: err}
	}
	return nil
}

// PublicHandler serves the public API.
func (a *App) PublicHandler() http.Handler {
	return httpapi.NewPublic(httpapi.PublicOptions{
		Logger: a.logger,
		Health: a.Health,
		DB:     a.db,
		Now:    time.Now,
	})
}

// InternalHandler serves /metrics and /internal/status.
func (a *App) InternalHandler() http.Handler {
	return httpapi.NewInternal(httpapi.InternalOptions{
		Version:        a.version,
		Logger:         a.logger,
		Registry:       metrics.New(a.version, a.activeSessions),
		Started:        a.started,
		ActiveSessions: a.activeSessions,
		DBPing:         a.db.PingContext,
		SchemaVersion: func(ctx context.Context) (int64, error) {
			current, _, err := a.migrator.Versions(ctx)
			return current, err
		},
		ActivePages:         a.queries.CountActivePages,
		BinarySchemaVersion: a.migrator.LatestVersion(),
	})
}

// Serve opens and migrates the database, then serves the public and internal listeners until
// ctx is cancelled, at which point it drains in-flight requests for up to ShutdownTimeout.
func Serve(ctx context.Context, cfg config.Config, logger *slog.Logger, version string) error {
	logger.Info("starting pttd", slog.String("version", version))

	sqlDB, err := db.Open(ctx, cfg.DBPath)
	if err != nil {
		return err
	}
	defer func() {
		if err := sqlDB.Close(); err != nil {
			logger.Error("close database", slog.String("error", err.Error()))
		}
	}()

	migrator, err := db.NewMigrator(sqlDB, logger)
	if err != nil {
		return err
	}
	if _, err := migrator.MigrateUp(ctx, cfg.SnapshotDir, time.Now()); err != nil {
		return err
	}
	current, latest, err := migrator.Versions(ctx)
	if err != nil {
		return err
	}
	if current > latest {
		logger.Warn("database schema is newer than this binary; running in rollback mode",
			slog.Int64("schema_version", current), slog.Int64("binary_schema_version", latest))
	}

	a := New(version, logger, sqlDB, migrator)
	servers := []*http.Server{
		httpapi.NewServer(cfg.ListenAddr, a.PublicHandler(), logger),
		httpapi.NewServer(cfg.InternalAddr, a.InternalHandler(), logger),
	}
	return run(ctx, logger, servers)
}

// run binds every server before serving any, so a port conflict fails startup cleanly.
func run(ctx context.Context, logger *slog.Logger, servers []*http.Server) error {
	var lc net.ListenConfig
	listeners := make([]net.Listener, 0, len(servers))
	for _, s := range servers {
		ln, err := lc.Listen(ctx, "tcp", s.Addr)
		if err != nil {
			for _, l := range listeners {
				_ = l.Close()
			}
			return fmt.Errorf("listen on %s: %w", s.Addr, err)
		}
		listeners = append(listeners, ln)
	}

	errc := make(chan error, len(servers))
	for i, s := range servers {
		logger.Info("listening", slog.String("addr", listeners[i].Addr().String()))
		go func() {
			if err := s.Serve(listeners[i]); !errors.Is(err, http.ErrServerClosed) {
				errc <- fmt.Errorf("serve %s: %w", s.Addr, err)
				return
			}
			errc <- nil
		}()
	}

	var serveErr error
	select {
	case <-ctx.Done():
		logger.Info("shutting down", slog.Duration("drain_timeout", ShutdownTimeout))
	case serveErr = <-errc:
		logger.Error("listener failed; shutting down", slog.String("error", serveErr.Error()))
	}

	// The parent context is already cancelled; the drain gets its own deadline.
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), ShutdownTimeout)
	defer cancel()
	var errs []error
	for _, s := range servers {
		if err := s.Shutdown(shutdownCtx); err != nil {
			errs = append(errs, fmt.Errorf("shutdown %s: %w", s.Addr, err))
		}
	}
	if err := errors.Join(errs...); err != nil {
		return errors.Join(serveErr, err)
	}
	logger.Info("stopped")
	return serveErr
}
