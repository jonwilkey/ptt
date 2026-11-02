package push

import (
	"context"
	"database/sql"
	"log/slog"
	"time"
)

// CheckInTTL is the FCM time-to-live of `check_in` (protocol section 9).
const CheckInTTL = 24 * time.Hour

// SendCheckIn sends `{type: check_in}` (normal priority, no notification) to every live,
// non-test device that has an FCM token. Phones answer with PUT /v1/devices/me.
func SendCheckIn(ctx context.Context, d *sql.DB, sender Sender, logger *slog.Logger) {
	broadcast(ctx, d, sender, logger, "check_in push", map[string]string{TypeKey: "check_in"}, CheckInTTL)
}
