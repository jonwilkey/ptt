// Package cleanup is the nightly retention sweep (spec, "Data model"): it deletes what the
// server promised not to keep. Ended pages need no sweep (their rows are deleted when they
// end); this removes the tombstones that outlived their 24 hours, stale enrollment codes, and
// old audit rows.
package cleanup

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"time"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

const (
	// Interval is the time between sweeps.
	Interval = 24 * time.Hour
	// CodeRetention is how long an enrollment code is kept after it expired or was used. The
	// admin phone polls a code until it is used, so a day of grace keeps "expired" and "used"
	// visible; after the sweep GET /v1/enrollment-codes/{code_id} is 404 (protocol section 6).
	CodeRetention = 24 * time.Hour
	// AuditRetention is how long audit_log rows are kept.
	AuditRetention = 90 * 24 * time.Hour
)

// Counts is how many rows one sweep deleted.
type Counts struct {
	Tombstones int64
	Codes      int64
	AuditRows  int64
}

// Once runs one sweep at now.
func Once(ctx context.Context, d *sql.DB, now time.Time) (Counts, error) {
	q := sqlcdb.New(d)
	var c Counts
	var err error
	if c.Tombstones, err = q.DeleteExpiredPageTombstones(ctx, now.UnixMilli()); err != nil {
		return c, fmt.Errorf("delete page tombstones: %w", err)
	}
	cutoff := now.Add(-CodeRetention).UnixMilli()
	if c.Codes, err = q.DeleteStaleEnrollmentCodes(ctx, sqlcdb.DeleteStaleEnrollmentCodesParams{
		ExpiresAt: cutoff, UsedAt: sql.NullInt64{Int64: cutoff, Valid: true},
	}); err != nil {
		return c, fmt.Errorf("delete enrollment codes: %w", err)
	}
	if c.AuditRows, err = q.DeleteAuditLogBefore(ctx, now.Add(-AuditRetention).UnixMilli()); err != nil {
		return c, fmt.Errorf("delete audit rows: %w", err)
	}
	return c, nil
}

// Run sweeps once at once, then every Interval, until ctx ends. Each sweep logs only counts.
func Run(ctx context.Context, d *sql.DB, clk clock.Clock, logger *slog.Logger) {
	for {
		c, err := Once(ctx, d, clk.Now())
		switch {
		case err != nil && ctx.Err() != nil:
			return
		case err != nil:
			logger.LogAttrs(ctx, slog.LevelError, "nightly cleanup failed", slog.String("error", err.Error()))
		default:
			logger.LogAttrs(ctx, slog.LevelInfo, "nightly cleanup",
				slog.Int64("page_tombstones", c.Tombstones),
				slog.Int64("enrollment_codes", c.Codes),
				slog.Int64("audit_rows", c.AuditRows))
		}
		select {
		case <-ctx.Done():
			return
		case <-clk.After(Interval):
		}
	}
}
