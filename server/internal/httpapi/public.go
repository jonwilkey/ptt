// Package httpapi holds pttd's HTTP handlers: the public API (behind Caddy) and the internal
// metrics/status listener (host loopback and tailnet only).
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

// healthTimeout bounds a /healthz check so a wedged database reports unhealthy instead of
// hanging the container healthcheck.
const healthTimeout = 3 * time.Second

// HealthFunc reports whether pttd can serve traffic. Return an *UnhealthyError to control the
// reason shown to callers; any other error is reported as "unavailable".
type HealthFunc func(ctx context.Context) error

// UnhealthyError carries a short reason that is safe to show to unauthenticated callers, plus
// the underlying error, which is only logged.
type UnhealthyError struct {
	Reason string
	Err    error
}

func (e *UnhealthyError) Error() string { return e.Reason + ": " + e.Err.Error() }
func (e *UnhealthyError) Unwrap() error { return e.Err }

// HealthResponse is the /healthz body. It deliberately omits the version: the endpoint is
// unauthenticated, and the version is available on the internal listener.
type HealthResponse struct {
	Status string `json:"status"`
	Reason string `json:"reason,omitempty"`
}

// PublicOptions configures the public handler.
type PublicOptions struct {
	Logger *slog.Logger
	Health HealthFunc
	// MaxBodyBytes defaults to DefaultMaxBodyBytes.
	MaxBodyBytes int64
	// HealthTimeout bounds each health check; defaults to 3 s.
	HealthTimeout time.Duration
	// DB backs the /v1 API. Without it only /healthz is served.
	DB *sql.DB
	// Now is the clock for expiry, rate limits and presence; defaults to time.Now.
	Now func() time.Time
}

// NewPublic returns the public API handler.
func NewPublic(opts PublicOptions) http.Handler {
	logger := opts.Logger
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	maxBody := opts.MaxBodyBytes
	if maxBody <= 0 {
		maxBody = DefaultMaxBodyBytes
	}
	timeout := opts.HealthTimeout
	if timeout <= 0 {
		timeout = healthTimeout
	}

	r := chi.NewRouter()
	r.Use(logRequests(logger), recoverPanics(logger), limitBody(maxBody))
	r.NotFound(notFound)
	r.MethodNotAllowed(methodNotAllowed(r))

	health := healthz(opts.Health, timeout, logger)
	r.Get("/healthz", health)
	// net/http drops the body for HEAD; uptime probes often use it.
	r.Head("/healthz", health)

	if opts.DB != nil {
		newAPI(opts.DB, logger, opts.Now).routes(r)
	}
	return r
}

func healthz(check HealthFunc, timeout time.Duration, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), timeout)
		defer cancel()

		var err error
		if check == nil {
			err = errors.New("no health check configured")
		} else {
			err = check(ctx)
		}
		if err != nil {
			reason := "unavailable"
			var ue *UnhealthyError
			if errors.As(err, &ue) {
				reason = ue.Reason
			}
			logger.LogAttrs(r.Context(), slog.LevelWarn, "health check failed",
				slog.String("reason", reason), slog.String("error", err.Error()))
			writeJSON(w, http.StatusServiceUnavailable, HealthResponse{Status: "unavailable", Reason: reason})
			return
		}
		writeJSON(w, http.StatusOK, HealthResponse{Status: "ok"})
	}
}
