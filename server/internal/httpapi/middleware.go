package httpapi

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"runtime/debug"
	"time"

	"github.com/go-chi/chi/v5"
)

// DefaultMaxBodyBytes caps request bodies. Pages carry a small signed envelope; nothing on the
// public API needs more.
const DefaultMaxBodyBytes = 64 << 10

// statusRecorder captures the response status for logging.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.ResponseWriter.Write(b)
}

// Unwrap lets http.ResponseController reach the underlying writer (for deadlines and, later,
// the WebSocket upgrade).
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// routePattern returns the matched chi route (e.g. "/v1/pages/{id}") rather than the raw path,
// so logs never carry IDs from the URL.
func routePattern(r *http.Request) string {
	if rc := chi.RouteContext(r.Context()); rc != nil {
		if p := rc.RoutePattern(); p != "" {
			return p
		}
	}
	return "unmatched"
}

// logRequests logs every 5xx at error level and, at debug level only, every request.
//
// Privacy (spec, "Data model"): logs record errors and admin events, never pages. So there is no
// info-level access log, and nothing here logs client IPs, query strings, raw paths, or headers
// (which would include bearer tokens).
func logRequests(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := &statusRecorder{ResponseWriter: w}
			next.ServeHTTP(rec, r)

			status := rec.status
			if status == 0 {
				status = http.StatusOK
			}
			attrs := []slog.Attr{
				slog.String("method", r.Method),
				slog.String("route", routePattern(r)),
				slog.Int("status", status),
				slog.Duration("duration", time.Since(start)),
			}
			level := slog.LevelDebug
			if status >= http.StatusInternalServerError {
				level = slog.LevelError
			}
			logger.LogAttrs(r.Context(), level, "http request", attrs...)
		})
	}
}

// recoverPanics turns a handler panic into a logged 500 instead of a dropped connection.
func recoverPanics(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				v := recover()
				if v == nil {
					return
				}
				// net/http uses this sentinel to abort a response deliberately; let it through.
				if err, ok := v.(error); ok && errors.Is(err, http.ErrAbortHandler) {
					panic(v)
				}
				logger.LogAttrs(r.Context(), slog.LevelError, "panic in http handler",
					slog.String("method", r.Method),
					slog.String("route", routePattern(r)),
					slog.String("panic", fmt.Sprint(v)),
					slog.String("stack", string(debug.Stack())),
				)
				writeError(w, http.StatusInternalServerError, "internal_error")
			}()
			next.ServeHTTP(w, r)
		})
	}
}

// limitBody caps the request body; reads past the limit fail with *http.MaxBytesError.
func limitBody(n int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.ContentLength > n {
				writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
				return
			}
			r.Body = http.MaxBytesReader(w, r.Body, n)
			next.ServeHTTP(w, r)
		})
	}
}
