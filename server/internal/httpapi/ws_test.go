package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/google/uuid"
)

// wsEnv is a testEnv whose router also serves /v1/ws with a stub handler.
func wsEnv(t *testing.T, onRevoke func(string)) (*testEnv, *int) {
	t.Helper()
	e := newEnv(t)
	upgrades := 0
	e.h = NewPublic(PublicOptions{
		DB: e.db, Now: func() time.Time { return e.now }, OnRevoke: onRevoke,
		WS: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			upgrades++
			w.WriteHeader(http.StatusNoContent)
		}),
	})
	return e, &upgrades
}

func TestWSUpgradesAreRateLimitedPerClientIP(t *testing.T) {
	e, upgrades := wsEnv(t, nil)
	get := func(ip string) *httptest.ResponseRecorder {
		return e.call(http.MethodGet, "/v1/ws", "", nil, "X-Forwarded-For", ip)
	}
	for i := range 30 {
		if rec := get("203.0.113.7"); rec.Code != http.StatusNoContent {
			t.Fatalf("upgrade %d = %d", i, rec.Code)
		}
	}
	rec := get("203.0.113.7")
	wantError(t, rec, http.StatusTooManyRequests, "rate_limited")
	if secs, err := strconv.Atoi(rec.Header().Get("Retry-After")); err != nil || secs < 1 || secs > 60 {
		t.Errorf("Retry-After = %q", rec.Header().Get("Retry-After"))
	}
	if *upgrades != 30 {
		t.Errorf("the handler ran %d times, want 30", *upgrades)
	}
	// Another address has its own budget; so does the same one a minute later.
	if rec := get("203.0.113.8"); rec.Code != http.StatusNoContent {
		t.Errorf("other IP = %d", rec.Code)
	}
	e.advance(time.Minute)
	if rec := get("203.0.113.7"); rec.Code != http.StatusNoContent {
		t.Errorf("after the window = %d", rec.Code)
	}
}

func TestWSRouteNeedsNoBearerTokenAndIsGetOnly(t *testing.T) {
	e, _ := wsEnv(t, nil)
	if rec := e.call(http.MethodGet, "/v1/ws", "", nil); rec.Code != http.StatusNoContent {
		t.Errorf("GET without Authorization = %d: credentials belong in the first frame", rec.Code)
	}
	// An Authorization header is ignored, not validated.
	if rec := e.call(http.MethodGet, "/v1/ws", "bogus", nil); rec.Code != http.StatusNoContent {
		t.Errorf("GET with a bad token = %d", rec.Code)
	}
	wantError(t, e.call(http.MethodPost, "/v1/ws", "", nil), http.StatusMethodNotAllowed, "method_not_allowed")
}

func TestRevokeNotifiesTheSocketHub(t *testing.T) {
	var got []string
	e, _ := wsEnv(t, func(id string) { got = append(got, id) })
	admin := e.bootstrapAdmin()
	sam := e.enroll(e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code, randB64(32))

	// A refused revoke does not reach the hub.
	wantError(t, e.call(http.MethodPost, "/v1/devices/"+uuid.NewString()+"/revoke", admin.Token, nil), http.StatusNotFound, "not_found")
	if len(got) != 0 {
		t.Fatalf("OnRevoke called for a refused revoke: %v", got)
	}
	for range 2 { // idempotent: still told, since closing is idempotent
		if rec := e.call(http.MethodPost, "/v1/devices/"+sam.DeviceID+"/revoke", admin.Token, nil); rec.Code != http.StatusNoContent {
			t.Fatalf("revoke = %d", rec.Code)
		}
	}
	if len(got) != 2 || got[0] != sam.DeviceID {
		t.Errorf("OnRevoke calls = %v", got)
	}
}
