package pages

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/metrics"
)

// Timers of protocol section 8.
const (
	// FirstRepush and SecondRepush are measured from creation: an undelivered page is pushed
	// again, and the sender told not_delivered, at each.
	FirstRepush  = 30 * time.Second
	SecondRepush = 2 * time.Minute
	// MissedAfter is how long a normal page may go unanswered. Urgent pages never time out.
	MissedAfter = 15 * time.Minute
	// AnsweredTTL is how long an answered page lives (the milestone-2 stand-in for a session).
	AnsweredTTL = 15 * time.Minute

	// maxTick caps the scheduler's sleep, so it also notices pages created while it sleeps.
	maxTick = time.Second
	// minWait keeps a due-but-unprocessed page (changed under us) from spinning the loop.
	minWait = 10 * time.Millisecond
)

type step int

const (
	stepNone   step = iota
	stepRepush      // push the page again and report not_delivered
	stepMissed      // a normal page went unanswered
	stepExpire      // an answered page reached its time limit
)

// timers is the part of a page row every deadline is derived from. There is no stored
// "next deadline": it is a pure function of these columns, so a restart (or a row changed by an
// API call) can never leave a stale one behind.
type timers struct {
	level        string
	state        string
	createdAt    time.Time
	delivered    bool
	pushAttempts int64
	answeredAt   sql.NullInt64
}

// nextStep returns the earliest step still ahead of (or due for) the page and when it is due.
// push_attempts counts the page pushes sent so far, the initial one included. If the page's
// end (missed, expiry) is already due at now, it wins over any re-push that is also due, so a
// server that was down for a long time does not re-ring a page it is about to drop.
func nextStep(t timers, now time.Time) (step, time.Time) {
	var (
		push, end     step
		pushAt, endAt time.Time
	)
	switch t.state {
	case StateRinging, StateAcknowledged:
		if t.state == StateRinging && !t.delivered {
			switch t.pushAttempts {
			case 1:
				push, pushAt = stepRepush, t.createdAt.Add(FirstRepush)
			case 2:
				push, pushAt = stepRepush, t.createdAt.Add(SecondRepush)
			}
		}
		if t.level == LevelNormal {
			end, endAt = stepMissed, t.createdAt.Add(MissedAfter)
		}
	case StateAnswered:
		answered := t.createdAt
		if t.answeredAt.Valid {
			answered = time.UnixMilli(t.answeredAt.Int64)
		}
		end, endAt = stepExpire, answered.Add(AnsweredTTL)
	}
	switch {
	case end != stepNone && !endAt.After(now):
		return end, endAt
	case push != stepNone && (end == stepNone || pushAt.Before(endAt)):
		return push, pushAt
	}
	return end, endAt
}

func timersOf(p sqlcdb.ActivePage) timers {
	return timers{
		level: p.Level, state: p.State, createdAt: time.UnixMilli(p.CreatedAt),
		delivered: p.DeliveredAt.Valid, pushAttempts: p.PushAttempts, answeredAt: p.AnsweredAt,
	}
}

// Start runs the scheduler in the background until Close.
func (s *Service) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.schedCancel != nil {
		return
	}
	ctx, cancel := context.WithCancel(s.ctx)
	s.schedCancel = cancel
	s.schedWG.Add(1)
	go func() {
		defer s.schedWG.Done()
		s.Run(ctx)
	}()
}

// Run processes due timers, then sleeps until the next deadline (at most maxTick), until ctx
// ends. The first pass runs at once, so pages already in the database when the process starts
// are picked up, including ones whose deadlines passed while it was down.
func (s *Service) Run(ctx context.Context) {
	for {
		next, ok, err := s.RunDue(ctx)
		if err != nil && ctx.Err() == nil {
			s.logger.LogAttrs(ctx, slog.LevelError, "page scheduler", slog.String("error", err.Error()))
		}
		wait := maxTick
		if ok {
			wait = min(wait, next.Sub(s.clk.Now()))
		}
		wait = max(wait, minWait)
		select {
		case <-ctx.Done():
			return
		case <-s.clk.After(wait):
		}
	}
}

// RunDue performs every step that is due now and reports the earliest deadline still ahead.
// A step can make another due (a restart after a long outage owes several), so it repeats
// until a pass does nothing.
func (s *Service) RunDue(ctx context.Context) (next time.Time, ok bool, err error) {
	for {
		pages, err := s.q.ListPageTimers(ctx)
		if err != nil {
			return time.Time{}, false, err
		}
		now := s.clk.Now()
		progressed := false
		next, ok = time.Time{}, false
		for _, row := range pages {
			st, at := nextStep(timers{
				level: row.Level, state: row.State, createdAt: time.UnixMilli(row.CreatedAt),
				delivered: row.DeliveredAt.Valid, pushAttempts: row.PushAttempts, answeredAt: row.AnsweredAt,
			}, now)
			switch {
			case st == stepNone:
			case at.After(now):
				if !ok || at.Before(next) {
					next, ok = at, true
				}
			default:
				did, err := s.runStep(ctx, row.ID)
				if err != nil {
					return time.Time{}, false, err
				}
				progressed = progressed || did
			}
		}
		if !progressed {
			return next, ok, nil
		}
	}
}

// runStep re-reads the page inside a write transaction and performs the step that is due, if
// one still is: an API call may have changed the page since the scan.
func (s *Service) runStep(ctx context.Context, id string) (bool, error) {
	var (
		did bool
		st  step
		p   sqlcdb.ActivePage
	)
	// Do not start a step once shutting down: a re-push counted in the database but dropped by
	// dispatch would be lost. A Close that lands between this check and dispatch below can
	// still drop one step's pushes; the page stays in the database, and after the restart the
	// scheduler's first pass handles whatever is then due (a dropped re-push is not repeated,
	// at worst the sender misses one not_delivered).
	if s.isClosed() {
		return false, nil
	}
	now := s.clk.Now()
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		var err error
		p, err = q.GetActivePage(ctx, id)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		var at time.Time
		st, at = nextStep(timersOf(p), now)
		if st == stepNone || at.After(now) {
			return nil
		}
		did = true
		if st == stepRepush {
			// Past both deadlines (an outage): one re-ring covers both.
			attempts := p.PushAttempts + 1
			if p.PushAttempts == 1 && !now.Before(time.UnixMilli(p.CreatedAt).Add(SecondRepush)) {
				attempts = 3
			}
			return q.SetPagePushAttempts(ctx, sqlcdb.SetPagePushAttemptsParams{PushAttempts: attempts, ID: id})
		}
		return s.endPage(ctx, q, id, now)
	})
	if err != nil || !did {
		return false, err
	}

	switch st {
	case stepRepush:
		s.dispatch(func(ctx context.Context) { s.repush(ctx, id) })
	case stepMissed:
		s.metrics.Ended.WithLabelValues(metrics.EndMissed).Inc()
		s.dispatch(func(ctx context.Context) {
			s.sendTo(ctx, recipientsOf(p), cancelMsg(id, "missed"))
			s.sendTo(ctx, []string{p.FromDevice}, stateMsg(id, "missed"))
		})
	case stepExpire:
		// Protocol section 8 lists no push for this expiry.
		s.metrics.Ended.WithLabelValues(metrics.EndExpired).Inc()
	}
	return true, nil
}

// repush sends the page to its recipients again and tells the sender it was not delivered. It
// re-reads the page first and does nothing unless it is still ringing and undelivered. If
// nobody could be reached the page fails, as after a failed initial push.
func (s *Service) repush(ctx context.Context, id string) {
	p, err := s.q.GetActivePage(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return
	}
	if err != nil {
		s.logger.LogAttrs(ctx, slog.LevelError, "page re-push: reload", slog.String("error", err.Error()))
		return
	}
	if p.State != StateRinging || p.DeliveredAt.Valid {
		return
	}
	sender, err := s.q.GetDeviceForRoster(ctx, p.FromDevice)
	if err != nil {
		s.logger.LogAttrs(ctx, slog.LevelError, "page re-push: look up sender", slog.String("error", err.Error()))
		return
	}
	if r := s.sendTo(ctx, recipientsOf(p), pageMsg(p.ID, p.Level, sender.UserID, p.Envelope)); r.nobodyReached() {
		s.failPage(ctx, p.ID, p.FromDevice)
		return
	}
	s.sendTo(ctx, []string{p.FromDevice}, stateMsg(p.ID, "not_delivered"))
}
