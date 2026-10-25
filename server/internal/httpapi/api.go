package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/ratelimit"
)

const (
	// enrollLimit is POST /v1/enroll's budget per client IP (docs/protocol.md section 1).
	enrollLimit  = 5
	enrollWindow = time.Hour
	// authFailAuditWindow spaces the auth_failure audit rows: a flood of bad tokens writes one
	// row per window, not one per request.
	authFailAuditWindow = time.Minute
	// lastSeenInterval is the most often a device's last_seen_at is written.
	lastSeenInterval = time.Minute
	// offlineAfter is how long without contact before a person shows as offline.
	offlineAfter = 48 * time.Hour
)

// api holds what the /v1 handlers share.
type api struct {
	db     *sql.DB
	q      *sqlcdb.Queries
	logger *slog.Logger
	now    func() time.Time

	enrollLimiter   *ratelimit.Limiter
	authFailLimiter *ratelimit.Limiter
}

func newAPI(db *sql.DB, logger *slog.Logger, now func() time.Time) *api {
	if now == nil {
		now = time.Now
	}
	return &api{
		db:              db,
		q:               sqlcdb.New(db),
		logger:          logger,
		now:             now,
		enrollLimiter:   ratelimit.New(enrollLimit, enrollWindow, now),
		authFailLimiter: ratelimit.New(1, authFailAuditWindow, now),
	}
}

// routes registers the device-facing REST API (docs/protocol.md section 6) on r.
func (a *api) routes(r chi.Router) {
	// Unauthenticated: the enrollment code is the credential.
	r.Post("/v1/enroll", a.enroll)

	r.Group(func(r chi.Router) {
		r.Use(a.authenticate)
		r.Put("/v1/devices/me", a.updateSelf)
		r.Get("/v1/contacts", a.contacts)

		r.Group(func(r chi.Router) {
			r.Use(requireAdmin)
			r.Post("/v1/enrollment-codes", a.createEnrollmentCode)
			r.Get("/v1/enrollment-codes/{code_id}", a.getEnrollmentCode)
			r.Post("/v1/devices/{id}/revoke", a.revokeDevice)
		})
	})
}

// serverError logs an unexpected failure and answers 500. err comes from the database layer and
// never carries request data such as tokens or codes.
func (a *api) serverError(w http.ResponseWriter, r *http.Request, op string, err error) {
	if !errors.Is(err, context.Canceled) {
		a.logger.LogAttrs(r.Context(), slog.LevelError, "request failed",
			slog.String("route", routePattern(r)), slog.String("op", op), slog.String("error", err.Error()))
	}
	writeError(w, http.StatusInternalServerError, "internal_error")
}
