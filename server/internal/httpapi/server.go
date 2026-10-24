package httpapi

import (
	"log/slog"
	"net/http"
	"time"
)

// NewServer wraps h in an http.Server with timeouts suited to a small JSON API.
//
// The milestone-4 WebSocket route will need longer-lived connections; it should clear these
// deadlines per connection with http.ResponseController rather than loosening them here.
func NewServer(addr string, h http.Handler, logger *slog.Logger) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
		// net/http's own errors (malformed requests, handler misuse) go to the JSON log at
		// warn. They can include the peer address, but every peer of the public listener is
		// Caddy, so no phone's IP reaches the log.
		ErrorLog: slog.NewLogLogger(logger.Handler(), slog.LevelWarn),
	}
}
