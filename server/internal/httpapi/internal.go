package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Status is the /internal/status body, shared with `pttd admin status`.
type Status struct {
	Version        string `json:"version"`
	UptimeSeconds  int64  `json:"uptime_seconds"`
	ActiveSessions int    `json:"active_sessions"`
	ActivePages    int64  `json:"active_pages"`
	DBOK           bool   `json:"db_ok"`
	SchemaVersion  int64  `json:"schema_version"`
	// BinarySchemaVersion is the newest migration this build carries; deploy tooling compares
	// it with schema_version.
	BinarySchemaVersion int64 `json:"binary_schema_version"`
}

// InternalOptions configures the internal handler.
type InternalOptions struct {
	Version  string
	Logger   *slog.Logger
	Registry *prometheus.Registry
	Started  time.Time
	// Now defaults to time.Now.
	Now            func() time.Time
	ActiveSessions func() int
	// DBPing, SchemaVersion, and ActivePages back the database fields; an error from any of
	// them sets db_ok to false and leaves the corresponding field zero.
	DBPing        func(ctx context.Context) error
	SchemaVersion func(ctx context.Context) (int64, error)
	ActivePages   func(ctx context.Context) (int64, error)
	// BinarySchemaVersion is reported as-is.
	BinarySchemaVersion int64
}

// NewInternal returns the handler for the internal listener: /metrics and /internal/status.
func NewInternal(opts InternalOptions) http.Handler {
	logger := opts.Logger
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}

	r := chi.NewRouter()
	r.Use(logRequests(logger), recoverPanics(logger), limitBody(DefaultMaxBodyBytes))
	r.NotFound(notFound)
	r.MethodNotAllowed(methodNotAllowed(r))

	r.Method(http.MethodGet, "/metrics", promhttp.HandlerFor(opts.Registry, promhttp.HandlerOpts{
		ErrorLog: slog.NewLogLogger(logger.Handler(), slog.LevelError),
	}))
	r.Get("/internal/status", status(opts, logger))
	return r
}

func status(opts InternalOptions, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), healthTimeout)
		defer cancel()

		s := Status{
			Version:             opts.Version,
			UptimeSeconds:       int64(opts.Now().Sub(opts.Started).Seconds()),
			DBOK:                true,
			BinarySchemaVersion: opts.BinarySchemaVersion,
		}
		if opts.ActiveSessions != nil {
			s.ActiveSessions = opts.ActiveSessions()
		}
		fail := func(what string, err error) {
			s.DBOK = false
			logger.LogAttrs(ctx, slog.LevelWarn, "status: "+what+" failed", slog.String("error", err.Error()))
		}
		if err := opts.DBPing(ctx); err != nil {
			fail("database ping", err)
		}
		if v, err := opts.SchemaVersion(ctx); err != nil {
			fail("schema version", err)
		} else {
			s.SchemaVersion = v
		}
		if n, err := opts.ActivePages(ctx); err != nil {
			fail("count active pages", err)
		} else {
			s.ActivePages = n
		}
		writeJSON(w, http.StatusOK, s)
	}
}
