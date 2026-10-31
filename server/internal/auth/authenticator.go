package auth

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/ratelimit"
)

const (
	// FailureAuditWindow spaces the auth_failure audit rows: a flood of bad tokens writes one
	// row per window, not one per attempt, whether it arrives over REST or the WebSocket.
	FailureAuditWindow = time.Minute
	// LastSeenInterval is the most often a device's last_seen_at is written.
	LastSeenInterval = time.Minute
)

// Authentication outcomes.
var (
	// ErrMalformed means the token is not a valid unpadded base64url of 32 bytes. Nothing is
	// audited: a malformed token is not a guess at a real one.
	ErrMalformed = errors.New("malformed device token")
	// ErrUnauthorized means a well-formed token that matches no live device. Unknown and revoked
	// are deliberately one outcome (no oracle). One throttled auth_failure row was written.
	ErrUnauthorized = errors.New("unknown or revoked device token")
)

// Device is the authenticated device behind a token.
type Device struct {
	ID     string
	UserID string
	// IsAdmin is the device's user's flag, not a property of the device.
	IsAdmin bool
	IsTest  bool
}

// Authenticator resolves device tokens for the REST middleware and the WebSocket hello, so both
// apply the same rules: the lookup runs every time and excludes revoked devices, a failure with
// a well-formed token writes a throttled auth_failure audit row (no actor, no token, no address)
// and a success refreshes last_seen_at.
type Authenticator struct {
	q       *sqlcdb.Queries
	logger  *slog.Logger
	now     func() time.Time
	limiter *ratelimit.Limiter
}

// NewAuthenticator returns an Authenticator over d. A nil now means time.Now.
func NewAuthenticator(d sqlcdb.DBTX, logger *slog.Logger, now func() time.Time) *Authenticator {
	if now == nil {
		now = time.Now
	}
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	return &Authenticator{
		q: sqlcdb.New(d), logger: logger, now: now,
		limiter: ratelimit.New(1, FailureAuditWindow, now),
	}
}

// Authenticate resolves token to a live device: ErrMalformed, ErrUnauthorized, or an
// infrastructure error.
func (a *Authenticator) Authenticate(ctx context.Context, token string) (Device, error) {
	hash, err := HashToken(token)
	if err != nil {
		return Device{}, ErrMalformed
	}
	row, err := a.q.GetActiveDeviceByTokenHash(ctx, hash)
	if errors.Is(err, sql.ErrNoRows) {
		a.auditFailure(ctx)
		return Device{}, ErrUnauthorized
	}
	if err != nil {
		return Device{}, err
	}
	a.touchLastSeen(ctx, row, a.now())
	return Device{ID: row.ID, UserID: row.UserID, IsAdmin: row.IsAdmin == 1, IsTest: row.IsTest == 1}, nil
}

// touchLastSeen records contact, at most once per LastSeenInterval per device. The previous
// value comes from the lookup, so no extra state is held. A failure is logged and ignored: it
// must not fail an otherwise good request.
func (a *Authenticator) touchLastSeen(ctx context.Context, row sqlcdb.GetActiveDeviceByTokenHashRow, now time.Time) {
	if row.LastSeenAt.Valid && now.Sub(time.UnixMilli(row.LastSeenAt.Int64)) < LastSeenInterval {
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

func (a *Authenticator) auditFailure(ctx context.Context) {
	if ok, _ := a.limiter.Allow("auth_failure"); !ok {
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
