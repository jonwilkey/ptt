package pages

import (
	"context"
	"database/sql"
	"errors"
	"slices"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/metrics"
)

// load fetches a live page, or reports why there is none: ErrEnded for a tombstoned id and
// ErrNotFound for an unknown one.
func load(ctx context.Context, q *sqlcdb.Queries, id string) (sqlcdb.ActivePage, error) {
	p, err := q.GetActivePage(ctx, id)
	if err == nil {
		return p, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return p, err
	}
	ended, terr := tombstoned(ctx, q, id)
	switch {
	case terr != nil:
		return p, terr
	case ended:
		return p, ErrEnded
	}
	return p, ErrNotFound
}

// loadForRecipient is load plus the check that deviceID is one of the page's recipients.
func loadForRecipient(ctx context.Context, q *sqlcdb.Queries, id, deviceID string) (sqlcdb.ActivePage, error) {
	p, err := load(ctx, q, id)
	if err != nil {
		return p, err
	}
	if !slices.Contains(recipientsOf(p), deviceID) {
		return p, ErrForbidden
	}
	return p, nil
}

// Delivered handles POST /v1/pages/{id}/delivered and returns the page's current state for the
// phone to act on: ringing (alert), acknowledged (post silently) or answered (this device
// answered it). The first call sets the flag and tells the sender, if the page is still
// ringing. A page another device answered is over for this device: ErrEnded.
func (s *Service) Delivered(ctx context.Context, deviceID, pageID string) (string, error) {
	var (
		state  string
		set    bool // this call set the flag
		first  bool // ... and the sender should hear of it
		sender string
		took   time.Duration
	)
	now := s.clk.Now()
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		p, err := loadForRecipient(ctx, q, pageID, deviceID)
		if err != nil {
			return err
		}
		if p.State == StateAnswered && p.AnsweredBy.String != deviceID {
			return ErrEnded
		}
		n, err := q.MarkPageDelivered(ctx, sqlcdb.MarkPageDeliveredParams{
			DeliveredAt: sql.NullInt64{Int64: now.UnixMilli(), Valid: true}, ID: pageID,
		})
		if err != nil {
			return err
		}
		state = p.State
		set = n == 1
		first = set && p.State == StateRinging
		sender = p.FromDevice
		took = now.Sub(time.UnixMilli(p.CreatedAt))
		return nil
	})
	if err != nil {
		return "", err
	}
	if set {
		s.metrics.Delivery.WithLabelValues().Observe(took.Seconds())
	}
	if first {
		s.dispatch(func(ctx context.Context) { s.sendTo(ctx, []string{sender}, stateMsg(pageID, "delivered")) })
	}
	return state, nil
}

// Acknowledge handles POST /v1/pages/{id}/acknowledge: ringing becomes acknowledged, the other
// devices stop ringing, and the sender hears. Anything later is a no-op.
func (s *Service) Acknowledge(ctx context.Context, deviceID, pageID string) error {
	var (
		changed bool
		sender  string
		others  []string
	)
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		p, err := loadForRecipient(ctx, q, pageID, deviceID)
		if err != nil {
			return err
		}
		if p.State != StateRinging {
			return nil
		}
		n, err := q.MarkPageAcknowledged(ctx, pageID)
		if err != nil {
			return err
		}
		changed, sender, others = n == 1, p.FromDevice, except(recipientsOf(p), deviceID)
		return nil
	})
	if err != nil || !changed {
		return err
	}
	s.dispatch(func(ctx context.Context) {
		s.sendTo(ctx, others, ackMsg(pageID))
		s.sendTo(ctx, []string{sender}, stateMsg(pageID, "acknowledged"))
	})
	return nil
}

// Answer handles POST /v1/pages/{id}/answer: the first answer wins atomically (the check and
// the update share one write transaction); the same device repeating it is a no-op, another
// device gets ErrAlreadyAnswered.
func (s *Service) Answer(ctx context.Context, deviceID, pageID string) error {
	var (
		won    bool
		sender string
		others []string
		took   time.Duration
	)
	now := s.clk.Now()
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		p, err := loadForRecipient(ctx, q, pageID, deviceID)
		if err != nil {
			return err
		}
		if p.State == StateAnswered {
			if p.AnsweredBy.String == deviceID {
				return nil
			}
			return ErrAlreadyAnswered
		}
		n, err := q.MarkPageAnswered(ctx, sqlcdb.MarkPageAnsweredParams{
			AnsweredBy: sql.NullString{String: deviceID, Valid: true},
			AnsweredAt: sql.NullInt64{Int64: now.UnixMilli(), Valid: true},
			ID:         pageID,
		})
		if err != nil {
			return err
		}
		won, sender, others = n == 1, p.FromDevice, except(recipientsOf(p), deviceID)
		took = now.Sub(time.UnixMilli(p.CreatedAt))
		return nil
	})
	if err != nil || !won {
		return err
	}
	s.metrics.Answer.WithLabelValues().Observe(took.Seconds())
	s.dispatch(func(ctx context.Context) {
		s.sendTo(ctx, []string{sender}, answeredMsg(pageID))
		s.sendTo(ctx, others, cancelMsg(pageID, "answered_elsewhere"))
	})
	return nil
}

// Cancel handles POST /v1/pages/{id}/cancel. The sender may cancel at any time; after an
// answer the answering device may end the page too. Cancelling a page that is already over is
// a success, whoever asks: nothing is left to tell who the sender was.
func (s *Service) Cancel(ctx context.Context, deviceID, pageID string) error {
	var (
		bySender bool
		p        sqlcdb.ActivePage
	)
	now := s.clk.Now()
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		var err error
		p, err = load(ctx, q, pageID)
		if errors.Is(err, ErrEnded) {
			return errAlreadyOver
		}
		if err != nil {
			return err
		}
		bySender = p.FromDevice == deviceID
		byAnswerer := p.State == StateAnswered && p.AnsweredBy.String == deviceID
		if !bySender && !byAnswerer {
			return ErrForbidden
		}
		return s.endPage(ctx, q, pageID, now)
	})
	if errors.Is(err, errAlreadyOver) {
		return nil
	}
	if err != nil {
		return err
	}
	if bySender {
		s.metrics.Ended.WithLabelValues(metrics.EndCancelled).Inc()
		// After an answer the other devices already heard "answered elsewhere"; only the
		// answering device is still showing the page.
		targets := recipientsOf(p)
		if p.State == StateAnswered {
			targets = []string{p.AnsweredBy.String}
		}
		s.dispatch(func(ctx context.Context) { s.sendTo(ctx, targets, cancelMsg(pageID, "cancelled")) })
		return nil
	}
	s.metrics.Ended.WithLabelValues(metrics.EndEnded).Inc()
	s.dispatch(func(ctx context.Context) { s.sendTo(ctx, []string{p.FromDevice}, stateMsg(pageID, "ended")) })
	return nil
}

// errAlreadyOver unwinds the Cancel transaction for a tombstoned page.
var errAlreadyOver = errors.New("page already over")
