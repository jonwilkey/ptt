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
		sessID string
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
		sender, sessID = p.FromDevice, p.SessionID
		took = now.Sub(time.UnixMilli(p.CreatedAt))
		return nil
	})
	if err != nil {
		return "", err
	}
	if set {
		s.metrics.Delivery.WithLabelValues().Observe(took.Seconds())
	}
	// One channel for the sender: its live socket, else FCM (protocol section 8).
	if first && !s.notify.Progress(sessID, sender, pageID, "delivered") {
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
		sessID  string
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
		changed, sender, sessID, others = n == 1, p.FromDevice, p.SessionID, except(recipientsOf(p), deviceID)
		return nil
	})
	if err != nil || !changed {
		return err
	}
	live := s.notify.Progress(sessID, sender, pageID, "acknowledged")
	s.dispatch(func(ctx context.Context) {
		s.sendTo(ctx, others, ackMsg(pageID))
		if !live {
			s.sendTo(ctx, []string{sender}, stateMsg(pageID, "acknowledged"))
		}
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
		sessID string
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
		won, sender, sessID, others = n == 1, p.FromDevice, p.SessionID, except(recipientsOf(p), deviceID)
		took = now.Sub(time.UnixMilli(p.CreatedAt))
		return nil
	})
	if err != nil || !won {
		return err
	}
	s.metrics.Answer.WithLabelValues().Observe(took.Seconds())
	s.notify.Answered(sessID, pageID, deviceID)
	// A sender with a live socket hears "answered" there as well; the FCM page_answered below
	// is sent regardless: the live window cannot prove the sender's process is alive, and this
	// push is what reopens a dead sender's talk screen (protocol section 8).
	s.notify.Progress(sessID, sender, pageID, "answered")
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
	s.afterCancel(p, deviceID)
	return nil
}

// afterCancel runs once a participant's cancel (REST cancel, the answerer's End, a WebSocket
// session_end, or a revoked participant) has committed: the live sockets hear session_end and
// FCM covers whoever is not live (protocol section 8). The page row is already deleted.
func (s *Service) afterCancel(p sqlcdb.ActivePage, deviceID string) {
	live := s.notify.Ended(Ending{
		PageID: p.ID, SessionID: p.SessionID, Sender: p.FromDevice, Answerer: p.AnsweredBy.String,
		Initiator: deviceID,
	})
	if p.FromDevice == deviceID {
		s.metrics.Ended.WithLabelValues(metrics.EndCancelled).Inc()
		// After an answer the other devices already heard "answered elsewhere"; only the
		// answering device is still showing the page, and not if it is on a live socket.
		targets := recipientsOf(p)
		if p.State == StateAnswered {
			if live.Answerer {
				return
			}
			targets = []string{p.AnsweredBy.String}
		}
		s.dispatch(func(ctx context.Context) { s.sendTo(ctx, targets, cancelMsg(p.ID, "cancelled")) })
		return
	}
	s.metrics.Ended.WithLabelValues(metrics.EndEnded).Inc()
	if !live.Sender {
		s.dispatch(func(ctx context.Context) { s.sendTo(ctx, []string{p.FromDevice}, stateMsg(p.ID, "ended")) })
	}
}

// EndIdle ends an answered page whose session idled out: the WebSocket idle timer (2 minutes
// without floor activity once both participants joined). Nothing happens if the page is gone
// or not answered.
func (s *Service) EndIdle(ctx context.Context, pageID string) error {
	var p sqlcdb.ActivePage
	ended := false
	now := s.clk.Now()
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		var err error
		p, err = q.GetActivePage(ctx, pageID)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if p.State != StateAnswered {
			return nil
		}
		ended = true
		return s.endPage(ctx, q, pageID, now)
	})
	if err != nil || !ended {
		return err
	}
	s.afterIdle(p)
	return nil
}

// afterIdle is the aftermath of an answered page ending by itself: the 2-minute idle timer or
// the 15-minute cap, both "idle" to the participants (protocol sections 8 and 11.5).
func (s *Service) afterIdle(p sqlcdb.ActivePage) {
	s.metrics.Ended.WithLabelValues(metrics.EndExpired).Inc()
	live := s.notify.Ended(Ending{
		PageID: p.ID, SessionID: p.SessionID, Sender: p.FromDevice, Answerer: p.AnsweredBy.String,
		Reason: ReasonIdle,
	})
	if !live.Sender {
		s.dispatch(func(ctx context.Context) { s.sendTo(ctx, []string{p.FromDevice}, stateMsg(p.ID, "ended")) })
	}
}

// EndRevoked ends the session of an answered page because one of its participants was revoked
// (protocol sections 11.5 and 11.9): the peer hears session_end ended_by_peer. It does nothing
// for a page that is gone, not yet answered (whether revocation ends ringing pages is not
// changed), or that deviceID does not take part in.
func (s *Service) EndRevoked(ctx context.Context, deviceID, pageID string) error {
	var p sqlcdb.ActivePage
	ended := false
	now := s.clk.Now()
	err := db.InTx(ctx, s.db, func(q *sqlcdb.Queries) error {
		var err error
		p, err = q.GetActivePage(ctx, pageID)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if p.State != StateAnswered || (p.FromDevice != deviceID && p.AnsweredBy.String != deviceID) {
			return nil
		}
		ended = true
		return s.endPage(ctx, q, pageID, now)
	})
	if err != nil || !ended {
		return err
	}
	s.afterCancel(p, deviceID)
	return nil
}

// errAlreadyOver unwinds the Cancel transaction for a tombstoned page.
var errAlreadyOver = errors.New("page already over")
