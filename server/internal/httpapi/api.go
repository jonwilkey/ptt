package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/ratelimit"
)

const (
	// enrollLimit is POST /v1/enroll's budget per client IP (docs/protocol.md section 1).
	enrollLimit  = 5
	enrollWindow = time.Hour
	// wsUpgradeLimit is GET /v1/ws upgrades per client IP (docs/protocol.md section 11.10).
	wsUpgradeLimit  = 30
	wsUpgradeWindow = time.Minute
	// offlineAfter is how long without contact before a person shows as offline.
	offlineAfter = 48 * time.Hour
)

// api holds what the /v1 handlers share.
type api struct {
	db     *sql.DB
	q      *sqlcdb.Queries
	logger *slog.Logger
	now    func() time.Time

	notifier RosterNotifier
	pages    PageService // nil: the page endpoints are not served

	auth          *auth.Authenticator
	onRevoke      func(deviceID string)
	wsLimiter     *ratelimit.Limiter
	enrollLimiter *ratelimit.Limiter
}

func newAPI(db *sql.DB, logger *slog.Logger, now func() time.Time, authn *auth.Authenticator, notifier RosterNotifier, pages PageService) *api {
	if notifier == nil {
		notifier = NopRosterNotifier{}
	}
	if now == nil {
		now = time.Now
	}
	if authn == nil {
		authn = auth.NewAuthenticator(db, logger, now)
	}
	return &api{
		db:            db,
		q:             sqlcdb.New(db),
		logger:        logger,
		now:           now,
		notifier:      notifier,
		pages:         pages,
		auth:          authn,
		enrollLimiter: ratelimit.New(enrollLimit, enrollWindow, now),
		wsLimiter:     ratelimit.New(wsUpgradeLimit, wsUpgradeWindow, now),
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
		r.Get("/v1/roster", a.getRoster)
		if a.pages != nil {
			a.pageRoutes(r)
		}

		r.Group(func(r chi.Router) {
			r.Use(requireAdmin)
			r.Post("/v1/enrollment-codes", a.createEnrollmentCode)
			r.Get("/v1/enrollment-codes/{code_id}", a.getEnrollmentCode)
			r.Post("/v1/devices/{id}/revoke", a.revokeDevice)
			r.Put("/v1/roster", a.putRoster)
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
