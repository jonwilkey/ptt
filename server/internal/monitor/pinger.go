// Package monitor is pttd's self-reporting (docs/operations.md, "Monitoring"): pings to
// Healthchecks.io, the FCM key check, the phone heartbeat, and the daily check_in push.
package monitor

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// PingTimeout bounds one ping.
const PingTimeout = 10 * time.Second

// Pinger posts to Healthchecks.io. The ping key is a secret and is never logged.
type Pinger struct {
	base   string
	key    string
	client *http.Client
	logger *slog.Logger
}

// NewPinger returns nil when key is empty: monitoring is off and callers skip their jobs.
func NewPinger(baseURL, key string, logger *slog.Logger) *Pinger {
	if key == "" {
		return nil
	}
	return &Pinger{
		base:   strings.TrimRight(baseURL, "/"),
		key:    key,
		client: &http.Client{Timeout: PingTimeout},
		logger: logger,
	}
}

// Ping POSTs body to <base>/<key>/<slug>, or <slug>/fail when fail is set. Failures are logged
// at warn without the URL and never retried.
func (p *Pinger) Ping(ctx context.Context, slug string, fail bool, body string) {
	target := p.base + "/" + url.PathEscape(p.key) + "/" + slug
	if fail {
		target += "/fail"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, strings.NewReader(body))
	if err != nil {
		p.warn(ctx, slug, "build request failed")
		return
	}
	req.Header.Set("Content-Type", "text/plain; charset=utf-8")
	resp, err := p.client.Do(req)
	if err != nil {
		p.warn(ctx, slug, sendClass(err))
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	_ = resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		p.warn(ctx, slug, "HTTP "+resp.Status)
	}
}

// sendClass names a send failure by kind only: error text can carry the URL and so the key.
func sendClass(err error) string {
	var ne net.Error
	var oe *net.OpError
	switch {
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &ne) && ne.Timeout():
		return "timeout"
	case errors.As(err, &oe):
		return "connect"
	default:
		return "other"
	}
}

func (p *Pinger) warn(ctx context.Context, slug, reason string) {
	p.logger.LogAttrs(ctx, slog.LevelWarn, "healthchecks ping failed",
		slog.String("check", slug), slog.String("reason", reason))
}
