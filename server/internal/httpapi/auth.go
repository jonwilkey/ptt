package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

// Caller is the authenticated device behind a request.
type Caller struct {
	DeviceID string
	UserID   string
	// IsAdmin is the device's user's flag, not a property of the device.
	IsAdmin bool
	IsTest  bool
}

type callerKey struct{}

// CallerFrom returns the authenticated device, which the authenticate middleware stored.
func CallerFrom(ctx context.Context) (Caller, bool) {
	c, ok := ctx.Value(callerKey{}).(Caller)
	return c, ok
}

func unauthorized(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", "Bearer")
	writeError(w, http.StatusUnauthorized, "unauthorized")
}

// bearerToken extracts the token from "Authorization: Bearer <token>".
func bearerToken(r *http.Request) (string, bool) {
	scheme, token, ok := strings.Cut(r.Header.Get("Authorization"), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") || token == "" {
		return "", false
	}
	return token, true
}

// authenticate requires a valid device token. The lookup runs on every request and excludes
// revoked devices, so a revocation applies to the very next call.
//
// Privacy: a failure with a well-formed token writes an auth_failure audit row with no actor
// and no token material, at most one per authFailAuditWindow. Nothing here logs the token.
func (a *api) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := bearerToken(r)
		if !ok {
			unauthorized(w)
			return
		}
		hash, err := auth.HashToken(token)
		if err != nil {
			unauthorized(w)
			return
		}
		row, err := a.q.GetActiveDeviceByTokenHash(r.Context(), hash)
		if errors.Is(err, sql.ErrNoRows) {
			a.auditAuthFailure(r.Context())
			unauthorized(w)
			return
		}
		if err != nil {
			a.serverError(w, r, "authenticate", err)
			return
		}

		now := a.now()
		a.touchLastSeen(r.Context(), row, now)
		caller := Caller{DeviceID: row.ID, UserID: row.UserID, IsAdmin: row.IsAdmin == 1, IsTest: row.IsTest == 1}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), callerKey{}, caller)))
	})
}

// touchLastSeen records contact, at most once per lastSeenInterval per device. The previous
// value comes from the lookup, so no extra state is held. A failure is logged and ignored: it
// must not fail an otherwise good request.
func (a *api) touchLastSeen(ctx context.Context, row sqlcdb.GetActiveDeviceByTokenHashRow, now time.Time) {
	if row.LastSeenAt.Valid && now.Sub(time.UnixMilli(row.LastSeenAt.Int64)) < lastSeenInterval {
		return
	}
	err := a.q.TouchDeviceLastSeen(ctx, sqlcdb.TouchDeviceLastSeenParams{
		LastSeenAt: sql.NullInt64{Int64: now.UnixMilli(), Valid: true},
		ID:         row.ID,
	})
	if err != nil && !errors.Is(err, context.Canceled) {
		a.logger.LogAttrs(ctx, slog.LevelWarn, "update last_seen_at", slog.String("error", err.Error()))
	}
}

func (a *api) auditAuthFailure(ctx context.Context) {
	if ok, _ := a.authFailLimiter.Allow("auth_failure"); !ok {
		return
	}
	err := a.q.InsertAuditLog(ctx, sqlcdb.InsertAuditLogParams{
		At:     a.now().UnixMilli(),
		Action: "auth_failure",
	})
	if err != nil && !errors.Is(err, context.Canceled) {
		a.logger.LogAttrs(ctx, slog.LevelWarn, "write auth_failure audit row", slog.String("error", err.Error()))
	}
}

// requireAdmin admits only devices whose user is an admin. Test devices are refused even when
// their user is an admin: their tokens live in CI secrets of a public repo. It must run after
// authenticate.
func requireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if c, ok := CallerFrom(r.Context()); !ok || !c.IsAdmin || c.IsTest {
			writeError(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// rateLimited answers 429 with Retry-After rounded up to whole seconds.
func rateLimited(w http.ResponseWriter, retryAfter time.Duration) {
	secs := max(int64((retryAfter+time.Second-1)/time.Second), 1)
	w.Header().Set("Retry-After", strconv.FormatInt(secs, 10))
	writeError(w, http.StatusTooManyRequests, "rate_limited")
}
