package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jonwilkey/ptt/server/internal/auth"
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
// and no token material, at most one per auth.FailureAuditWindow (see auth.Authenticator).
// Nothing here logs the token.
func (a *api) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := bearerToken(r)
		if !ok {
			unauthorized(w)
			return
		}
		dev, err := a.auth.Authenticate(r.Context(), token)
		switch {
		case errors.Is(err, auth.ErrMalformed), errors.Is(err, auth.ErrUnauthorized):
			unauthorized(w)
			return
		case err != nil:
			a.serverError(w, r, "authenticate", err)
			return
		}
		caller := Caller{DeviceID: dev.ID, UserID: dev.UserID, IsAdmin: dev.IsAdmin, IsTest: dev.IsTest}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), callerKey{}, caller)))
	})
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
