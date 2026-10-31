package httpapi

import "net/http"

// upgradeLimit applies the per-IP budget for WebSocket upgrades (docs/protocol.md section
// 11.10): 30 per minute, then 429 with Retry-After. The address is only a map key.
func (a *api) upgradeLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if ok, retry := a.wsLimiter.Allow(clientIP(r)); !ok {
			rateLimited(w, retry)
			return
		}
		next.ServeHTTP(w, r)
	})
}
