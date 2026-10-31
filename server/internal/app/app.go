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
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/cleanup"
	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/httpapi"
	"github.com/jonwilkey/ptt/server/internal/metrics"
	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/ws"
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
	sender   push.Sender
	fake     *push.Fake
	notifier *push.RosterNotifier
	clk      clock.Clock
	pageSvc  *pages.Service
	pageMet  *metrics.Pages
	authn    *auth.Authenticator
	hub      *ws.Hub

	// The background loops started by Start.
	bgCancel context.CancelFunc
	bgWG     sync.WaitGroup
}

// Option customizes an App.
type Option func(*App)

// WithSender sets the FCM sender. fake is non-nil only for the fake sender, whose recorded
// messages are then served on the internal listener. Without this option FCM is disabled.
func WithSender(s push.Sender, fake *push.Fake) Option {
	return func(a *App) { a.sender, a.fake = s, fake }
}

// WithClock sets the clock of the page lifecycle, its scheduler and the nightly cleanup; tests
// use a fake one. The default is the wall clock.
func WithClock(c clock.Clock) Option {
	return func(a *App) { a.clk = c }
}

// New returns an App over an open, migrated database.
func New(version string, logger *slog.Logger, sqlDB *sql.DB, migrator *db.Migrator, opts ...Option) *App {
	a := &App{
		version:  version,
		logger:   logger,
		db:       sqlDB,
		migrator: migrator,
		queries:  sqlcdb.New(sqlDB),
		started:  time.Now(),
		sender:   push.Disabled{},
		clk:      clock.Real{},
		pageMet:  metrics.NewPages(),
	}
	for _, o := range opts {
		o(a)
	}
	a.notifier = push.NewRosterNotifier(sqlDB, a.sender, logger)
	a.pageSvc = pages.NewService(pages.Options{
		DB: sqlDB, Sender: a.sender, Logger: logger, Clock: a.clk, Metrics: a.pageMet,
	})
	// The REST middleware and the WebSocket hello share one authenticator, so a flood of bad
	// tokens over both writes one auth_failure audit row per window.
	a.authn = auth.NewAuthenticator(sqlDB, logger, a.clk.Now)
	a.hub = ws.New(ws.Options{DB: sqlDB, Auth: a.authn, Ender: a.pageSvc, Logger: logger, Clock: a.clk})
	a.pageSvc.SetNotifier(a.hub)
	return a
}

// Start runs the background work: the page scheduler (which first picks up any page already in
// the database, so a restart while a page is ringing carries on) and the nightly cleanup, which
// sweeps once now and then every 24 h. Close stops both.
func (a *App) Start() {
	if a.bgCancel != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.bgCancel = cancel
	a.pageSvc.Start()
	a.hub.Start()
	a.bgWG.Add(1)
	go func() {
		defer a.bgWG.Done()
		cleanup.Run(ctx, a.db, a.clk, a.logger)
	}()
}

// Close stops the background loops, then lets in-flight pushes finish until ctx ends and
// cancels whatever is left.
func (a *App) Close(ctx context.Context) {
	// Sockets first: the clients are told 4503 and reconnect when the server is back.
	a.hub.Shutdown(ctx)
	if a.bgCancel != nil {
		a.bgCancel()
		a.bgWG.Wait()
	}
	a.pageSvc.Close(ctx)
	a.notifier.Close(ctx)
}

// activeSessions is the number of open talk sessions, which the deploy workflow waits to drain.
func (a *App) activeSessions() int { return a.hub.Sessions() }

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
		Now:    a.clk.Now,

		RosterNotifier: a.notifier,
		Pages:          a.pageSvc,
		Auth:           a.authn,
		WS:             a.hub,
		OnRevoke:       a.hub.CloseDevice,
	})
}

// InternalHandler serves /metrics and /internal/status.
func (a *App) InternalHandler() http.Handler {
	return httpapi.NewInternal(httpapi.InternalOptions{
		Version:        a.version,
		Logger:         a.logger,
		Registry:       a.registry(),
		Started:        a.started,
		ActiveSessions: a.activeSessions,
		DBPing:         a.db.PingContext,
		SchemaVersion: func(ctx context.Context) (int64, error) {
			current, _, err := a.migrator.Versions(ctx)
			return current, err
		},
		ActivePages:         a.queries.CountActivePages,
		BinarySchemaVersion: a.migrator.LatestVersion(),
		FakeFCM:             fakeHandler(a.fake),
	})
}

func (a *App) registry() *prometheus.Registry {
	reg := metrics.New(a.version, a.activeSessions)
	a.pageMet.Register(reg)
	metrics.RegisterWS(reg, a.hub.Sockets)
	metrics.RegisterFrames(reg, a.hub.FrameCounts)
	return reg
}

func fakeHandler(f *push.Fake) http.Handler {
	if f == nil {
		return nil
	}
	return f.Handler()
}

// Serve opens and migrates the database, then serves the public and internal listeners until
// ctx is cancelled, at which point it drains in-flight requests for up to ShutdownTimeout.
func Serve(ctx context.Context, cfg config.Config, logger *slog.Logger, version string, opts ...Option) error {
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

	a := New(version, logger, sqlDB, migrator, opts...)
	a.Start()
	servers := []*http.Server{
		httpapi.NewServer(cfg.ListenAddr, a.PublicHandler(), logger),
		httpapi.NewServer(cfg.InternalAddr, a.InternalHandler(), logger),
	}
	return run(ctx, logger, servers, a.Close)
}

// run binds every server before serving any, so a port conflict fails startup cleanly.
func run(ctx context.Context, logger *slog.Logger, servers []*http.Server, onDrain func(context.Context)) error {
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
	if onDrain != nil {
		onDrain(shutdownCtx)
	}
	if err := errors.Join(errs...); err != nil {
		return errors.Join(serveErr, err)
	}
	logger.Info("stopped")
	return serveErr
}
