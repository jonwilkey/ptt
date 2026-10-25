package push

import (
	"context"
	"database/sql"
	"log/slog"
	"strconv"
	"sync"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

// Roster push parameters (protocol section 9: `roster` is normal priority, 24 h TTL).
const (
	RosterTTL = 24 * time.Hour
	// rosterTimeout bounds one broadcast so a hung FCM cannot pin a goroutine forever.
	rosterTimeout = 2 * time.Minute
	// sendConcurrency bounds in-flight sends within one broadcast.
	sendConcurrency = 8
)

// RosterNotifier implements httpapi.RosterNotifier: after a roster is stored it sends
// `{type: roster, version}` to every live, non-test device that has an FCM token.
type RosterNotifier struct {
	db     *sql.DB
	sender Sender
	logger *slog.Logger

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	// One worker runs broadcasts back to back. A version published while one is in flight
	// replaces any older queued version: phones only need the latest roster.
	mu      sync.Mutex
	pending *int64
	running bool
}

// NewRosterNotifier returns a notifier. Call Close at shutdown.
func NewRosterNotifier(d *sql.DB, sender Sender, logger *slog.Logger) *RosterNotifier {
	ctx, cancel := context.WithCancel(context.Background())
	return &RosterNotifier{
		db: d, sender: sender, logger: logger,
		ctx: ctx, cancel: cancel,
	}
}

// RosterPublished queues the broadcast and returns immediately. The request context is ignored
// on purpose: the push must outlive the HTTP response. Versions queued behind a running
// broadcast are coalesced: only the newest is sent.
func (n *RosterNotifier) RosterPublished(_ context.Context, version int64) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.pending == nil || version > *n.pending {
		n.pending = &version
	}
	if n.running {
		return
	}
	n.running = true
	n.wg.Add(1)
	go n.work()
}

func (n *RosterNotifier) work() {
	defer n.wg.Done()
	for {
		n.mu.Lock()
		v := n.pending
		n.pending = nil
		if v == nil || n.ctx.Err() != nil {
			n.running = false
			n.mu.Unlock()
			return
		}
		n.mu.Unlock()

		ctx, cancel := context.WithTimeout(n.ctx, rosterTimeout)
		n.broadcast(ctx, *v)
		cancel()
	}
}

// Wait blocks until every started broadcast has finished.
func (n *RosterNotifier) Wait() { n.wg.Wait() }

// Close lets running broadcasts finish until ctx ends, then cancels whatever is left.
func (n *RosterNotifier) Close(ctx context.Context) {
	done := make(chan struct{})
	go func() { n.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
		n.cancel()
		<-done
	}
	n.cancel()
}

func (n *RosterNotifier) broadcast(ctx context.Context, version int64) {
	q := sqlcdb.New(n.db)
	targets, err := q.ListPushTargets(ctx)
	if err != nil {
		n.logger.LogAttrs(ctx, slog.LevelWarn, "roster push: list devices failed", slog.String("error", err.Error()))
		return
	}
	data := map[string]string{TypeKey: "roster", "version": strconv.FormatInt(version, 10)}

	var wg sync.WaitGroup
	gate := make(chan struct{}, sendConcurrency)
	for _, t := range targets {
		if !t.FcmToken.Valid {
			continue
		}
		gate <- struct{}{}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-gate }()
			n.sendOne(ctx, q, t.ID, t.FcmToken.String, data)
		}()
	}
	wg.Wait()
}

func (n *RosterNotifier) sendOne(ctx context.Context, q *sqlcdb.Queries, deviceID, token string, data map[string]string) {
	err := n.sender.Send(ctx, Message{Token: token, Data: data, Priority: Normal, TTL: RosterTTL})
	switch {
	case err == nil:
	case isUnregistered(err):
		if cerr := q.ClearFCMToken(ctx, sqlcdb.ClearFCMTokenParams{
			ID: deviceID, FcmToken: sql.NullString{String: token, Valid: true},
		}); cerr != nil {
			n.logger.LogAttrs(ctx, slog.LevelWarn, "roster push: clear token failed",
				slog.String("device_id", deviceID), slog.String("error", cerr.Error()))
		}
	default:
		// The SDK error text carries no token or payload; the token itself is never logged.
		n.logger.LogAttrs(ctx, slog.LevelWarn, "roster push failed",
			slog.String("device_id", deviceID), slog.String("error", err.Error()))
	}
}
