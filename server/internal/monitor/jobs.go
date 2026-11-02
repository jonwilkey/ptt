package monitor

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"
	_ "time/tzdata" // the distroless image may lack zoneinfo; check_in needs America/Los_Angeles

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
	"github.com/jonwilkey/ptt/server/internal/push"
)

// Healthchecks.io slugs (docs/operations.md, "Healthchecks.io").
const (
	SlugUptime = "ptt-uptime"
	SlugFCM    = "ptt-fcm"
	SlugPhones = "ptt-phones"
)

// Job timing.
const (
	UptimeEvery     = 5 * time.Minute
	FCMEvery        = 6 * time.Hour
	FCMFirst        = time.Minute
	PhonesEvery     = time.Hour
	PhonesFirst     = 5 * time.Minute
	fcmTimeout      = 30 * time.Second
	checkInHour     = 11
	checkInLocation = "America/Los_Angeles"
)

// Options configures Run.
type Options struct {
	// Pinger is nil when no ping key is configured: only check_in runs then.
	Pinger  *Pinger
	Clock   clock.Clock
	Logger  *slog.Logger
	DB      *sql.DB
	Version string
	Sender  push.Sender
	// Health is App.Health reduced to its reason: "" when healthy.
	Health func(ctx context.Context) string
}

// Run starts the jobs and blocks until ctx ends. Every timer comes from Options.Clock.
func Run(ctx context.Context, o Options) {
	var wg sync.WaitGroup
	start := func(first, period time.Duration, fn func(context.Context)) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			every(ctx, o.Clock, first, period, fn)
		}()
	}
	if o.Pinger != nil {
		start(0, UptimeEvery, func(ctx context.Context) { o.uptime(ctx) })
		start(FCMFirst, FCMEvery, func(ctx context.Context) { o.fcm(ctx) })
		ph := &phoneJob{Options: o, procStart: o.Clock.Now()}
		ph.init(ctx)
		start(PhonesFirst, PhonesEvery, ph.run)
	}
	if _, off := o.Sender.(push.Disabled); !off {
		wg.Add(1)
		go func() {
			defer wg.Done()
			o.checkInLoop(ctx)
		}()
	}
	wg.Wait()
}

// every runs fn after first, then every period, until ctx ends.
func every(ctx context.Context, clk clock.Clock, first, period time.Duration, fn func(context.Context)) {
	wait := first
	for {
		select {
		case <-ctx.Done():
			return
		case <-clk.After(wait):
		}
		fn(ctx)
		wait = period
	}
}

func (o Options) uptime(ctx context.Context) {
	if reason := o.Health(ctx); reason != "" {
		o.Pinger.Ping(ctx, SlugUptime, true, reason)
		return
	}
	o.Pinger.Ping(ctx, SlugUptime, false, "")
}

func (o Options) fcm(ctx context.Context) {
	if _, off := o.Sender.(push.Disabled); off {
		o.Pinger.Ping(ctx, SlugFCM, true, "fcm not configured")
		return
	}
	vctx, cancel := context.WithTimeout(ctx, fcmTimeout)
	defer cancel()
	if err := o.Sender.ValidateOnly(vctx); err != nil {
		o.Pinger.Ping(ctx, SlugFCM, true, errorClass(err))
		return
	}
	o.Pinger.Ping(ctx, SlugFCM, false, "")
}

// errorClass names a failure without its text, which may come from the SDK.
func errorClass(err error) string {
	switch {
	case errors.Is(err, push.ErrUnregistered):
		return "unregistered"
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, push.ErrPermanent):
		return "permanent"
	default:
		return "transient"
	}
}

type phoneJob struct {
	Options
	procStart time.Time
	restart   bool
	tracker   Tracker
}

func (j *phoneJob) init(ctx context.Context) {
	devs, err := j.devices(ctx)
	if err != nil {
		j.Logger.LogAttrs(ctx, slog.LevelWarn, "phone heartbeat: restart rule skipped", slog.String("error", err.Error()))
		return
	}
	j.restart = RestartMode(devs, j.procStart)
}

func (j *phoneJob) run(ctx context.Context) {
	devs, err := j.devices(ctx)
	if err != nil {
		j.Logger.LogAttrs(ctx, slog.LevelWarn, "phone heartbeat: list devices failed", slog.String("error", err.Error()))
		return
	}
	res := Evaluate(devs, j.Clock.Now(), j.procStart, j.restart, j.Version)
	for _, a := range j.tracker.Next(res) {
		j.Pinger.Ping(ctx, SlugPhones, a.Fail, a.Body)
	}
}

func (j *phoneJob) devices(ctx context.Context) ([]Device, error) {
	rows, err := sqlcdb.New(j.DB).ListHeartbeatDevices(ctx)
	if err != nil {
		return nil, err
	}
	devs := make([]Device, len(rows))
	for i, r := range rows {
		devs[i] = Device{
			ID: r.ID, Person: r.UserDisplayName, Name: r.Name,
			AppVersion: r.AppVersion.String, Checklist: r.ChecklistStatus.String,
			Created: time.UnixMilli(r.CreatedAt), HasToken: r.HasToken == 1,
			Revoked: r.RevokedAt.Valid,
		}
		if r.LastSeenAt.Valid {
			devs[i].LastSeen = time.UnixMilli(r.LastSeenAt.Int64)
		}
	}
	return devs, nil
}

// NextCheckIn is the first 11:00 America/Los_Angeles strictly after now.
func NextCheckIn(now time.Time) time.Time {
	loc := checkInZone()
	l := now.In(loc)
	t := time.Date(l.Year(), l.Month(), l.Day(), checkInHour, 0, 0, 0, loc)
	if !t.After(now) {
		t = time.Date(l.Year(), l.Month(), l.Day()+1, checkInHour, 0, 0, 0, loc)
	}
	return t
}

func checkInZone() *time.Location {
	loc, err := time.LoadLocation(checkInLocation)
	if err != nil {
		panic(err)
	}
	return loc
}

func (o Options) checkInLoop(ctx context.Context) {
	for {
		now := o.Clock.Now()
		select {
		case <-ctx.Done():
			return
		case <-o.Clock.After(NextCheckIn(now).Sub(now)):
		}
		push.SendCheckIn(ctx, o.DB, o.Sender, o.Logger)
	}
}
