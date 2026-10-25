// Package pages implements the page lifecycle of docs/protocol.md sections 6 to 9: creating a
// page, the delivered / acknowledge / answer / cancel transitions, the timers that re-push and
// miss pages, and the FCM fan-out each transition produces.
//
// The state machine lives in SQLite (the active_pages row), so a restart loses nothing: the
// scheduler derives every deadline from the rows. Pushes go out after the transaction commits,
// on background goroutines with bounded concurrency, and never delay an HTTP response.
//
// Privacy (protocol section 1): nothing here logs, labels a metric with, or audits a page id,
// session id, sender or recipient.
package pages

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/metrics"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/ratelimit"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// Page states as stored in active_pages.state, and the pseudo-state of a page that has ended.
const (
	StateRinging      = "ringing"
	StateAcknowledged = "acknowledged"
	StateAnswered     = "answered"
	StateEnded        = "ended"
)

const (
	// createLimit is POST /v1/pages' budget per device (protocol section 1).
	createLimit  = 30
	createWindow = time.Minute
	// TombstoneTTL is how long an ended page's id is remembered for idempotent retries.
	TombstoneTTL = 24 * time.Hour
)

// Outcomes the HTTP layer maps to status codes (protocol sections 1 and 6).
var (
	ErrInvalid         = errors.New("invalid page request")     // 400 invalid_request
	ErrForbidden       = errors.New("not allowed on this page") // 403 forbidden
	ErrNotFound        = errors.New("no such page")             // 404 not_found
	ErrConflict        = errors.New("page id reused")           // 409 conflict
	ErrAlreadyAnswered = errors.New("page already answered")    // 409 already_answered
	ErrEnded           = errors.New("page has ended")           // 410 page_ended
)

// RateLimitedError means the device exceeded its page budget.
type RateLimitedError struct{ RetryAfter time.Duration }

func (e *RateLimitedError) Error() string { return "page rate limit exceeded" }

// Caller is the authenticated device making a request.
type Caller struct {
	DeviceID string
	UserID   string
}

// CreateRequest is the body of POST /v1/pages.
type CreateRequest struct {
	ID        string      `json:"id"`
	To        string      `json:"to"`
	Level     string      `json:"level"`
	SessionID string      `json:"session_id"`
	Envelope  signed.Wire `json:"envelope"`
}

// CreateResult is the outcome of a successful Create. Created is true for a new page (201,
// including one that ended at once) and false for an idempotent retry (200).
type CreateResult struct {
	State   string
	Created bool
}

// Options configures a Service.
type Options struct {
	DB     *sql.DB
	Sender push.Sender
	Logger *slog.Logger
	// Clock defaults to the wall clock.
	Clock clock.Clock
	// Metrics defaults to a private, unexported set.
	Metrics *metrics.Pages
}

// Service is the page lifecycle. Call Start to run the scheduler and Close at shutdown.
type Service struct {
	db      *sql.DB
	q       *sqlcdb.Queries
	sender  push.Sender
	logger  *slog.Logger
	clk     clock.Clock
	metrics *metrics.Pages
	limiter *ratelimit.Limiter

	// ctx bounds background pushes; Close cancels it.
	ctx    context.Context
	cancel context.CancelFunc
	gate   chan struct{}

	mu     sync.Mutex // guards closed and wg.Add
	closed bool
	wg     sync.WaitGroup

	schedCancel context.CancelFunc
	schedWG     sync.WaitGroup
}

// NewService returns a Service. It does nothing until requests arrive or Start is called.
func NewService(o Options) *Service {
	if o.Logger == nil {
		o.Logger = slog.New(slog.DiscardHandler)
	}
	if o.Clock == nil {
		o.Clock = clock.Real{}
	}
	if o.Metrics == nil {
		o.Metrics = metrics.NewPages()
	}
	if o.Sender == nil {
		o.Sender = push.Disabled{}
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Service{
		db: o.DB, q: sqlcdb.New(o.DB), sender: o.Sender, logger: o.Logger, clk: o.Clock, metrics: o.Metrics,
		limiter: ratelimit.New(createLimit, createWindow, o.Clock.Now),
		ctx:     ctx, cancel: cancel, gate: make(chan struct{}, sendConcurrency),
	}
}

// Wait blocks until every background push started so far has finished (for tests).
func (s *Service) Wait() { s.wg.Wait() }

func (s *Service) isClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

// Close stops the scheduler, lets running pushes finish until ctx ends, then cancels the rest.
func (s *Service) Close(ctx context.Context) {
	s.mu.Lock()
	s.closed = true
	if s.schedCancel != nil {
		s.schedCancel()
	}
	s.mu.Unlock()
	s.schedWG.Wait()

	done := make(chan struct{})
	go func() { s.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
		s.cancel()
		<-done
	}
	s.cancel()
}

// Create handles POST /v1/pages (protocol sections 6 and 7).
func (s *Service) Create(ctx context.Context, caller Caller, req CreateRequest) (CreateResult, error) {
	obj, env, err := s.checkRequest(caller, req)
	if err != nil {
		return CreateResult{}, err
	}
	stored, err := json.Marshal(obj.Encode())
	if err != nil {
		return CreateResult{}, fmt.Errorf("encode envelope: %w", err)
	}
	// The push must fit FCM's limit; it does not depend on the token, so check it up front.
	probe := pageMsg(req.ID, req.Level, caller.UserID, stored)
	if err := (push.Message{Token: "x", Data: probe.data}).Validate(); err != nil {
		return CreateResult{}, fmt.Errorf("%w: %w", ErrInvalid, err)
	}

	var (
		res        CreateResult
		recipients []string
	)
	now := s.clk.Now()
	err = db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		existing, err := q.GetActivePage(ctx, req.ID)
		switch {
		case err == nil:
			// An idempotent retry: the same page, byte for byte.
			if existing.ToUser != req.To || existing.Level != req.Level || existing.SessionID != req.SessionID ||
				!bytes.Equal(existing.Envelope, stored) {
				return ErrConflict
			}
			res = CreateResult{State: existing.State}
			return nil
		case !errors.Is(err, sql.ErrNoRows):
			return err
		}
		if ended, err := tombstoned(ctx, q, req.ID); err != nil {
			return err
		} else if ended {
			res = CreateResult{State: StateEnded}
			return nil
		}

		recipients, err = liveRecipients(ctx, q, env, caller.DeviceID)
		if err != nil {
			return err
		}
		// A valid new page: only now does it count against the device's budget, so requests
		// rejected above use none of it.
		if ok, retry := s.limiter.Allow(caller.DeviceID); !ok {
			return &RateLimitedError{RetryAfter: retry}
		}
		res = CreateResult{Created: true}
		if len(recipients) == 0 {
			// Nobody to ring: the page fails at once, with no page_state push (section 6).
			res.State = StateEnded
			return q.InsertPageTombstone(ctx, sqlcdb.InsertPageTombstoneParams{
				ID: req.ID, ExpiresAt: now.Add(TombstoneTTL).UnixMilli(),
			})
		}
		list, err := json.Marshal(recipients)
		if err != nil {
			return err
		}
		res.State = StateRinging
		return q.InsertActivePage(ctx, sqlcdb.InsertActivePageParams{
			ID: req.ID, SessionID: req.SessionID, FromDevice: caller.DeviceID, ToUser: req.To,
			Level: req.Level, Envelope: stored, CreatedAt: now.UnixMilli(),
			Recipients: sql.NullString{String: string(list), Valid: true},
		})
	})
	if err != nil {
		return CreateResult{}, err
	}
	if res.Created && res.State == StateEnded {
		s.metrics.Ended.WithLabelValues(metrics.EndFailed).Inc()
	}
	if res.Created && res.State == StateRinging {
		msg := pageMsg(req.ID, req.Level, caller.UserID, stored)
		s.dispatch(func(ctx context.Context) {
			r := s.sendTo(ctx, recipients, msg)
			if r.nobodyReached() {
				s.failPage(ctx, req.ID, caller.DeviceID)
			}
		})
	}
	return res, nil
}

// checkRequest applies the request-only checks of protocol section 7: field formats, and that
// the envelope matches the request and the caller. The server does not verify the signature
// (protocol section 7): receivers do, against their own verified roster.
func (s *Service) checkRequest(caller Caller, req CreateRequest) (signed.Object, Envelope, error) {
	if !wire.ValidUUID(req.ID) || !wire.ValidUUID(req.To) || !wire.ValidUUID(req.SessionID) ||
		(req.Level != LevelNormal && req.Level != LevelUrgent) {
		return signed.Object{}, Envelope{}, ErrInvalid
	}
	obj, err := signed.Decode(req.Envelope)
	if err != nil {
		return signed.Object{}, Envelope{}, ErrInvalid
	}
	env, err := ParseEnvelope(obj.Payload)
	if err != nil {
		return signed.Object{}, Envelope{}, fmt.Errorf("%w: %w", ErrInvalid, err)
	}
	if obj.Signer != caller.DeviceID || env.FromDevice != caller.DeviceID ||
		env.PageID != req.ID || env.SessionID != req.SessionID || env.ToUser != req.To || env.Level != req.Level {
		return signed.Object{}, Envelope{}, ErrInvalid
	}
	return obj, env, nil
}

// liveRecipients resolves the envelope's keys: every id must be a device of the addressed user
// (else the request is invalid) with the same is_test as the sender, so a page never crosses
// the test/real boundary; revoked devices are dropped.
func liveRecipients(ctx context.Context, q *sqlcdb.Queries, env Envelope, senderID string) ([]string, error) {
	sender, err := q.GetDeviceForRoster(ctx, senderID)
	if err != nil {
		return nil, err
	}
	var live []string
	for _, id := range env.Keys {
		d, err := q.GetDeviceForRoster(ctx, id)
		if errors.Is(err, sql.ErrNoRows) || (err == nil && d.UserID != env.ToUser) {
			return nil, fmt.Errorf("%w: key for a device that is not the addressee's", ErrInvalid)
		}
		if err != nil {
			return nil, err
		}
		if d.IsTest != sender.IsTest {
			return nil, fmt.Errorf("%w: page between a test and a real device", ErrInvalid)
		}
		if !d.RevokedAt.Valid {
			live = append(live, id)
		}
	}
	return live, nil
}

func tombstoned(ctx context.Context, q *sqlcdb.Queries, id string) (bool, error) {
	n, err := q.PageTombstoneExists(ctx, id)
	return n > 0, err
}

// endPage deletes the page and leaves its tombstone, in the caller's transaction.
func (s *Service) endPage(ctx context.Context, q *sqlcdb.Queries, id string, now time.Time) error {
	if _, err := q.DeleteActivePage(ctx, id); err != nil {
		return err
	}
	return q.InsertPageTombstone(ctx, sqlcdb.InsertPageTombstoneParams{
		ID: id, ExpiresAt: now.Add(TombstoneTTL).UnixMilli(),
	})
}

// failPage ends a page whose every initial push failed permanently and tells the sender. It
// does nothing if the page moved on in the meantime (a device reported it, or it has ended).
func (s *Service) failPage(ctx context.Context, id, sender string) {
	now := s.clk.Now()
	failed := false
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		p, err := q.GetActivePage(ctx, id)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if p.State != StateRinging || p.DeliveredAt.Valid {
			return nil
		}
		failed = true
		return s.endPage(ctx, q, id, now)
	})
	if err != nil {
		s.logger.LogAttrs(ctx, slog.LevelError, "fail page", slog.String("error", err.Error()))
		return
	}
	if failed {
		s.metrics.Ended.WithLabelValues(metrics.EndFailed).Inc()
		s.sendTo(ctx, []string{sender}, stateMsg(id, "failed"))
	}
}

// recipientsOf decodes the stored recipient list.
func recipientsOf(p sqlcdb.ActivePage) []string {
	var ids []string
	if p.Recipients.Valid {
		_ = json.Unmarshal([]byte(p.Recipients.String), &ids) // written by Create; cannot be malformed
	}
	return ids
}

func except(ids []string, id string) []string {
	return slices.DeleteFunc(slices.Clone(ids), func(s string) bool { return s == id })
}
