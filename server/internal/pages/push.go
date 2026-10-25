package pages

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/metrics"
	"github.com/jonwilkey/ptt/server/internal/push"
)

const (
	// jobTimeout bounds one transition's pushes so a hung FCM cannot pin a goroutine forever.
	jobTimeout = time.Minute
	// sendConcurrency bounds in-flight FCM sends across the whole service.
	sendConcurrency = 16

	// Time to live of every message but the page (protocol section 9).
	shortTTL      = 15 * time.Minute
	pageTTLNormal = 15 * time.Minute
	pageTTLUrgent = 24 * time.Hour
)

// outMsg is a message minus its token; sendTo resolves the token of each device when it sends.
type outMsg struct {
	data     map[string]string
	priority push.Priority
	ttl      time.Duration
}

// The message builders below are protocol section 9, one per FCM message a page produces.

func pageMsg(pageID, level, fromUser string, envelope []byte) outMsg {
	ttl := pageTTLNormal
	if level == LevelUrgent {
		ttl = pageTTLUrgent
	}
	return outMsg{
		data: map[string]string{
			push.TypeKey: "page", "page_id": pageID, "level": level,
			"from_user": fromUser, "envelope": string(envelope),
		},
		priority: push.High, ttl: ttl,
	}
}

func cancelMsg(pageID, reason string) outMsg {
	return outMsg{
		data:     map[string]string{push.TypeKey: "page_cancel", "page_id": pageID, "reason": reason},
		priority: push.High, ttl: shortTTL,
	}
}

func ackMsg(pageID string) outMsg {
	return outMsg{data: map[string]string{push.TypeKey: "page_ack", "page_id": pageID}, priority: push.High, ttl: shortTTL}
}

func answeredMsg(pageID string) outMsg {
	return outMsg{data: map[string]string{push.TypeKey: "page_answered", "page_id": pageID}, priority: push.High, ttl: shortTTL}
}

func stateMsg(pageID, state string) outMsg {
	return outMsg{
		data:     map[string]string{push.TypeKey: "page_state", "page_id": pageID, "state": state},
		priority: push.Normal, ttl: shortTTL,
	}
}

// sendResult tallies one fan-out.
type sendResult struct {
	ok int
	// permanent counts devices that cannot be reached: no token, revoked, unregistered, or a
	// permanent FCM error.
	permanent int
	// transient counts other failures, which a later attempt might fix.
	transient int
}

// nobodyReached reports that every target failed permanently (or there were none), the "all
// pushes failed" condition of protocol section 9.
func (r sendResult) nobodyReached() bool { return r.ok == 0 && r.transient == 0 }

// dispatch runs job in the background with a timeout. The caller has already committed; the
// HTTP response never waits on FCM. After Close it does nothing.
func (s *Service) dispatch(job func(ctx context.Context)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		ctx, cancel := context.WithTimeout(s.ctx, jobTimeout)
		defer cancel()
		job(ctx)
	}()
}

// sendTo sends m to every device in deviceIDs concurrently (bounded) and tallies the outcome.
func (s *Service) sendTo(ctx context.Context, deviceIDs []string, m outMsg) sendResult {
	var (
		mu  sync.Mutex
		res sendResult
		wg  sync.WaitGroup
	)
	for _, id := range deviceIDs {
		select {
		case s.gate <- struct{}{}:
		case <-ctx.Done():
			mu.Lock()
			res.transient++
			mu.Unlock()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-s.gate }()
			outcome := s.sendOne(ctx, id, m)
			mu.Lock()
			defer mu.Unlock()
			switch outcome {
			case outcomeOK:
				res.ok++
			case outcomePermanent:
				res.permanent++
			default:
				res.transient++
			}
		}()
	}
	wg.Wait()
	return res
}

type outcome int

const (
	outcomeOK outcome = iota
	outcomePermanent
	outcomeTransient
)

// sendOne resolves the device's current token and sends. Logs never carry a device, page or
// user id (protocol section 1); the error text from the SDK carries none either.
func (s *Service) sendOne(ctx context.Context, deviceID string, m outMsg) outcome {
	info, err := s.q.GetDevicePushInfo(ctx, deviceID)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return outcomePermanent
	case err != nil:
		s.logger.LogAttrs(ctx, slog.LevelWarn, "page push: look up device failed", slog.String("error", err.Error()))
		return outcomeTransient
	}
	if info.RevokedAt.Valid || !info.FcmToken.Valid || info.FcmToken.String == "" {
		return outcomePermanent
	}
	token := info.FcmToken.String

	err = s.sender.Send(ctx, push.Message{Token: token, Data: m.data, Priority: m.priority, TTL: m.ttl})
	switch {
	case err == nil:
		return outcomeOK
	case errors.Is(err, push.ErrUnregistered):
		s.metrics.FCMError.WithLabelValues(metrics.FCMUnregistered).Inc()
		// The token comparison keeps a token the phone registered while this push was in flight.
		if cerr := s.q.ClearFCMToken(ctx, sqlcdb.ClearFCMTokenParams{
			ID: deviceID, FcmToken: sql.NullString{String: token, Valid: true},
		}); cerr != nil {
			s.logger.LogAttrs(ctx, slog.LevelWarn, "page push: clear token failed", slog.String("error", cerr.Error()))
		}
		return outcomePermanent
	case errors.Is(err, push.ErrPermanent):
		s.metrics.FCMError.WithLabelValues(metrics.FCMPermanent).Inc()
		s.logger.LogAttrs(ctx, slog.LevelWarn, "page push failed permanently", slog.String("error", err.Error()))
		return outcomePermanent
	default:
		s.metrics.FCMError.WithLabelValues(metrics.FCMTransient).Inc()
		s.logger.LogAttrs(ctx, slog.LevelWarn, "page push failed", slog.String("error", err.Error()))
		return outcomeTransient
	}
}
