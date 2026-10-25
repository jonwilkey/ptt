package httpapi

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/signed"
)

// envClock reads the test env's clock, so rate limits and page times follow e.advance.
type envClock struct {
	clock.Clock
	e *testEnv
}

func (c envClock) Now() time.Time { return c.e.now }

// pageEnv is the router with the page endpoints over a fake FCM sender, plus Alex (sender) and
// Sam (two phones) enrolled through the real API.
type pageEnv struct {
	*testEnv
	fake *push.Fake
	svc  *pages.Service
	priv ed25519.PrivateKey

	alex, sam1, sam2 enrolled
}

func newPageEnv(t *testing.T) *pageEnv {
	t.Helper()
	e := newEnv(t)
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	p := &pageEnv{testEnv: e, fake: push.NewFake(), priv: priv}
	p.svc = pages.NewService(pages.Options{DB: e.db, Sender: p.fake, Clock: envClock{clock.Real{}, e}})
	t.Cleanup(func() { p.svc.Close(t.Context()) })
	p.h = NewPublic(PublicOptions{DB: e.db, Now: func() time.Time { return e.now }, Pages: p.svc})

	enrollAs := func(admin enrolled, token string, req CreateCodeRequest) enrolled {
		code := e.newCode(admin, req)
		body := enrollBody(code.Code, randB64(32))
		body["fcm_token"] = token
		return e.enrollWith(body)
	}
	p.alex = e.bootstrapAdmin()
	e.exec(`UPDATE devices SET fcm_token = 'tok-alex' WHERE id = ?`, p.alex.DeviceID)
	p.sam1 = enrollAs(p.alex, "tok-sam1", CreateCodeRequest{DisplayName: "Sam"})
	p.sam2 = enrollAs(p.alex, "tok-sam2", CreateCodeRequest{UserID: p.sam1.UserID})
	return p
}

// pageBody is a valid POST /v1/pages body from Alex to Sam's phones.
func (p *pageEnv) pageBody(id string, mutate func(payload map[string]any, body map[string]any)) map[string]any {
	session := uuid.NewString()
	key := b64(make([]byte, 80))
	payload := map[string]any{
		"format": 1, "page_id": id, "session_id": session, "from_device": p.alex.DeviceID,
		"to_user": p.sam1.UserID, "level": "normal", "created_at": p.now.UnixMilli(),
		"keys": map[string]string{p.sam1.DeviceID: key, p.sam2.DeviceID: key},
	}
	body := map[string]any{"id": id, "to": p.sam1.UserID, "level": "normal", "session_id": session}
	if mutate != nil {
		mutate(payload, body)
	}
	raw, _ := json.Marshal(payload)
	signer, _ := body["signer"].(string)
	delete(body, "signer")
	if signer == "" {
		signer = p.alex.DeviceID
	}
	body["envelope"] = signed.Sign(p.priv, signer, signed.ContextEnvelope, raw).Encode()
	return body
}

func (p *pageEnv) post(token, path string, body any) *httptest.ResponseRecorder {
	return p.call(http.MethodPost, path, token, body)
}

func (p *pageEnv) wantNoContent(rec *httptest.ResponseRecorder) {
	p.t.Helper()
	if rec.Code != http.StatusNoContent || rec.Body.Len() != 0 {
		p.t.Fatalf("status = %d, body %s, want empty 204", rec.Code, rec.Body)
	}
}

func (p *pageEnv) wantDelivered(token, id, state string) {
	p.t.Helper()
	rec := p.post(token, "/v1/pages/"+id+"/delivered", nil)
	if rec.Code != http.StatusOK {
		p.t.Fatalf("delivered = %d %s", rec.Code, rec.Body)
	}
	if got := decode[DeliveredResponse](p.t, rec); got.State != state {
		p.t.Fatalf("delivered state = %q, want %q", got.State, state)
	}
}

func (p *pageEnv) sent() []string {
	p.t.Helper()
	p.svc.Wait()
	var out []string
	for _, m := range p.fake.Messages() {
		s := m.Data["type"]
		if r := m.Data["reason"] + m.Data["state"]; r != "" {
			s += ":" + r
		}
		out = append(out, s+"->"+m.Token)
	}
	p.fake.Clear()
	return out
}

func (p *pageEnv) wantSent(want ...string) {
	p.t.Helper()
	got := p.sent()
	if len(got) != len(want) {
		p.t.Fatalf("pushes = %v, want %v", got, want)
	}
	seen := map[string]int{}
	for _, g := range got {
		seen[g]++
	}
	for _, w := range want {
		seen[w]--
	}
	for k, n := range seen {
		if n != 0 {
			p.t.Fatalf("pushes = %v, want %v (diff %q)", got, want, k)
		}
	}
}

func TestPageExchangeBetweenTwoDevices(t *testing.T) {
	p := newPageEnv(t)
	id := uuid.NewString()
	auditBefore := p.count(`SELECT COUNT(*) FROM audit_log`)

	rec := p.post(p.alex.Token, "/v1/pages", p.pageBody(id, nil))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create = %d %s", rec.Code, rec.Body)
	}
	if got := decode[PageResponse](t, rec); got != (PageResponse{ID: id, State: "ringing"}) {
		t.Fatalf("create body = %+v", got)
	}
	p.wantSent("page->tok-sam1", "page->tok-sam2")

	// Sam's first phone reports in and acknowledges; Alex hears of both.
	p.wantDelivered(p.sam1.Token, id, "ringing")
	p.wantSent("page_state:delivered->tok-alex")
	p.wantDelivered(p.sam1.Token, id, "ringing") // a repeat: same answer, no second push
	p.wantSent()
	p.wantNoContent(p.post(p.sam1.Token, "/v1/pages/"+id+"/acknowledge", nil))
	p.wantSent("page_ack->tok-sam2", "page_state:acknowledged->tok-alex")
	// The other phone now gets a page that is already acknowledged: post silently.
	p.wantDelivered(p.sam2.Token, id, "acknowledged")
	p.wantSent()

	// Sam's second phone answers; the first loses the race.
	p.wantNoContent(p.post(p.sam2.Token, "/v1/pages/"+id+"/answer", nil))
	p.wantSent("page_answered->tok-alex", "page_cancel:answered_elsewhere->tok-sam1")
	wantError(t, p.post(p.sam1.Token, "/v1/pages/"+id+"/answer", nil), http.StatusConflict, "already_answered")
	p.wantNoContent(p.post(p.sam2.Token, "/v1/pages/"+id+"/answer", nil)) // idempotent
	p.wantDelivered(p.sam2.Token, id, "answered")
	wantError(t, p.post(p.sam1.Token, "/v1/pages/"+id+"/delivered", nil), http.StatusGone, "page_ended")

	// A retry of the create shows the current state and rings nobody.
	rec = p.post(p.alex.Token, "/v1/pages", p.pageBody(id, nil))
	wantError(t, rec, http.StatusConflict, "conflict") // a re-signed body differs
	p.wantSent()

	// Alex ends the conversation; the answering phone is told.
	p.wantNoContent(p.post(p.alex.Token, "/v1/pages/"+id+"/cancel", nil))
	p.wantSent("page_cancel:cancelled->tok-sam2")
	wantError(t, p.post(p.sam1.Token, "/v1/pages/"+id+"/delivered", nil), http.StatusGone, "page_ended")
	p.wantNoContent(p.post(p.alex.Token, "/v1/pages/"+id+"/cancel", nil))

	if n := p.count(`SELECT COUNT(*) FROM active_pages`); n != 0 {
		t.Errorf("%d page rows remain", n)
	}
	// Pages leave nothing in the audit log.
	if n := p.count(`SELECT COUNT(*) FROM audit_log`); n != auditBefore {
		t.Errorf("%d audit rows written by the page", n-auditBefore)
	}
}

func TestCreatePageIdempotentRetryAndEnded(t *testing.T) {
	p := newPageEnv(t)
	id := uuid.NewString()
	body := p.pageBody(id, nil)

	if rec := p.post(p.alex.Token, "/v1/pages", body); rec.Code != http.StatusCreated {
		t.Fatalf("create = %d", rec.Code)
	}
	p.sent()
	rec := p.post(p.alex.Token, "/v1/pages", body)
	if rec.Code != http.StatusOK || decode[PageResponse](t, rec) != (PageResponse{ID: id, State: "ringing"}) {
		t.Fatalf("retry = %d %s", rec.Code, rec.Body)
	}
	p.wantSent()

	p.wantNoContent(p.post(p.alex.Token, "/v1/pages/"+id+"/cancel", nil))
	p.sent()
	rec = p.post(p.alex.Token, "/v1/pages", body)
	if rec.Code != http.StatusOK || decode[PageResponse](t, rec) != (PageResponse{ID: id, State: "ended"}) {
		t.Fatalf("retry after end = %d %s", rec.Code, rec.Body)
	}
	p.wantSent()
}

func TestCreatePageWithNoLiveRecipient(t *testing.T) {
	p := newPageEnv(t)
	p.exec(`UPDATE devices SET revoked_at = 1 WHERE user_id = ?`, p.sam1.UserID)
	id := uuid.NewString()
	rec := p.post(p.alex.Token, "/v1/pages", p.pageBody(id, nil))
	if rec.Code != http.StatusCreated || decode[PageResponse](t, rec) != (PageResponse{ID: id, State: "ended"}) {
		t.Fatalf("create = %d %s", rec.Code, rec.Body)
	}
	p.wantSent()
	if p.count(`SELECT COUNT(*) FROM page_tombstones WHERE id = ?`, id) != 1 {
		t.Error("no tombstone")
	}
}

func TestCreatePageValidation(t *testing.T) {
	p := newPageEnv(t)
	tests := []struct {
		name   string
		token  func() string
		mutate func(payload, body map[string]any)
		raw    string
		status int
		code   string
	}{
		{name: "no token", token: func() string { return "" }, status: 401, code: "unauthorized"},
		{name: "wrong signer", mutate: func(_, b map[string]any) { b["signer"] = p.sam1.DeviceID }, status: 400, code: "invalid_request"},
		{name: "wrong from_device", mutate: func(pl, _ map[string]any) { pl["from_device"] = p.sam1.DeviceID }, status: 400, code: "invalid_request"},
		{name: "caller is not the signer", token: func() string { return p.sam1.Token }, status: 400, code: "invalid_request"},
		{name: "mismatched level", mutate: func(pl, _ map[string]any) { pl["level"] = "urgent" }, status: 400, code: "invalid_request"},
		{name: "mismatched to", mutate: func(pl, _ map[string]any) { pl["to_user"] = p.alex.UserID }, status: 400, code: "invalid_request"},
		{name: "mismatched session", mutate: func(pl, _ map[string]any) { pl["session_id"] = uuid.NewString() }, status: 400, code: "invalid_request"},
		{name: "foreign device in keys", mutate: func(pl, _ map[string]any) {
			pl["keys"] = map[string]string{p.alex.DeviceID: b64(make([]byte, 80))}
		}, status: 400, code: "invalid_request"},
		{name: "bad keys map", mutate: func(pl, _ map[string]any) { pl["keys"] = []string{} }, status: 400, code: "invalid_request"},
		{name: "short wrapped key", mutate: func(pl, _ map[string]any) {
			pl["keys"] = map[string]string{p.sam1.DeviceID: b64(make([]byte, 79))}
		}, status: 400, code: "invalid_request"},
		{name: "unknown body field", mutate: func(_, b map[string]any) { b["extra"] = 1 }, status: 400, code: "invalid_request"},
		{name: "not json", raw: "nope", status: 400, code: "invalid_request"},
		{name: "empty", raw: "", status: 400, code: "invalid_request"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			token := p.alex.Token
			if tt.token != nil {
				token = tt.token()
			}
			var body any = p.pageBody(uuid.NewString(), tt.mutate)
			if tt.raw != "" || tt.name == "empty" {
				body = tt.raw
			}
			wantError(t, p.post(token, "/v1/pages", body), tt.status, tt.code)
			if n := p.count(`SELECT COUNT(*) FROM active_pages`); n != 0 {
				t.Errorf("%d rows stored", n)
			}
		})
	}
}

func TestCreatePageRateLimit(t *testing.T) {
	p := newPageEnv(t)
	first := p.pageBody(uuid.NewString(), nil)
	for i := range 30 {
		body := first
		if i > 0 {
			body = p.pageBody(uuid.NewString(), nil)
		}
		if rec := p.post(p.alex.Token, "/v1/pages", body); rec.Code != http.StatusCreated {
			t.Fatalf("create %d = %d %s", i, rec.Code, rec.Body)
		}
	}
	rec := p.post(p.alex.Token, "/v1/pages", p.pageBody(uuid.NewString(), nil))
	wantError(t, rec, http.StatusTooManyRequests, "rate_limited")
	if ra, err := strconv.Atoi(rec.Header().Get("Retry-After")); err != nil || ra < 1 || ra > 60 {
		t.Errorf("Retry-After = %q", rec.Header().Get("Retry-After"))
	}
	// Idempotent retries still work while limited.
	if rec := p.post(p.alex.Token, "/v1/pages", first); rec.Code != http.StatusOK {
		t.Errorf("retry while limited = %d", rec.Code)
	}
}

func TestPageActionStatusCodes(t *testing.T) {
	p := newPageEnv(t)
	id := uuid.NewString()
	if rec := p.post(p.alex.Token, "/v1/pages", p.pageBody(id, nil)); rec.Code != http.StatusCreated {
		t.Fatal(rec.Body)
	}
	actions := []string{"delivered", "acknowledge", "answer", "cancel"}

	for _, a := range actions {
		path := "/v1/pages/" + id + "/" + a
		wantError(t, p.post("", path, nil), 401, "unauthorized")
		wantError(t, p.post(p.sam1.Token, "/v1/pages/"+uuid.NewString()+"/"+a, nil), 404, "not_found")
		wantError(t, p.post(p.sam1.Token, "/v1/pages/not-a-uuid/"+a, nil), 400, "invalid_request")
		if rec := p.call(http.MethodGet, path, p.sam1.Token, nil); rec.Code != http.StatusMethodNotAllowed {
			t.Errorf("GET %s = %d", a, rec.Code)
		}
	}
	// Roles: the sender is not a recipient, a recipient is not the sender.
	for _, a := range actions[:3] {
		wantError(t, p.post(p.alex.Token, "/v1/pages/"+id+"/"+a, nil), 403, "forbidden")
	}
	wantError(t, p.post(p.sam1.Token, "/v1/pages/"+id+"/cancel", nil), 403, "forbidden")
	// A revoked device is simply unauthorized.
	p.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, p.sam2.DeviceID)
	wantError(t, p.post(p.sam2.Token, "/v1/pages/"+id+"/answer", nil), 401, "unauthorized")

	p.wantNoContent(p.post(p.alex.Token, "/v1/pages/"+id+"/cancel", nil))
	for _, a := range actions[:3] {
		wantError(t, p.post(p.sam1.Token, "/v1/pages/"+id+"/"+a, nil), 410, "page_ended")
	}
	p.wantNoContent(p.post(p.sam1.Token, "/v1/pages/"+id+"/cancel", nil)) // final for everyone
}

func TestPageRoutesAbsentWithoutService(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	wantError(t, e.call(http.MethodPost, "/v1/pages", admin.Token, "{}"), http.StatusNotFound, "not_found")
}

func TestPageResponsesNeverEchoPrivateFields(t *testing.T) {
	p := newPageEnv(t)
	id := uuid.NewString()
	rec := p.post(p.alex.Token, "/v1/pages", p.pageBody(id, nil))
	if strings.Contains(rec.Body.String(), p.sam1.UserID) || strings.Contains(rec.Body.String(), "session") {
		t.Errorf("response leaks request fields: %s", rec.Body)
	}
}

func TestRequestBodiesAreStrict(t *testing.T) {
	p := newPageEnv(t)
	good := p.pageBody(uuid.NewString(), nil)
	raw, _ := json.Marshal(good)

	// Wrong-case field names are refused, though encoding/json would match them.
	upper := strings.Replace(string(raw), `"id":`, `"ID":`, 1)
	wantError(t, p.post(p.alex.Token, "/v1/pages", upper), http.StatusBadRequest, "invalid_request")
	// So is a repeated field, whatever the second value.
	dup := strings.Replace(string(raw), `"level":"normal"`, `"level":"normal","level":"normal"`, 1)
	wantError(t, p.post(p.alex.Token, "/v1/pages", dup), http.StatusBadRequest, "invalid_request")
	if n := p.count(`SELECT COUNT(*) FROM active_pages`); n != 0 {
		t.Errorf("%d pages stored from malformed bodies", n)
	}
	if rec := p.post(p.alex.Token, "/v1/pages", string(raw)); rec.Code != http.StatusCreated {
		t.Fatalf("exact body = %d %s", rec.Code, rec.Body)
	}

	// Optional fields stay optional on other endpoints; duplicates and wrong case do not pass.
	for body, want := range map[string]int{
		`{}`:                                    http.StatusNoContent,
		`{"app_version":"1"}`:                   http.StatusNoContent,
		`{"App_Version":"1"}`:                   http.StatusBadRequest,
		`{"app_version":"1","app_version":"2"}`: http.StatusBadRequest,
	} {
		if rec := p.call(http.MethodPut, "/v1/devices/me", p.alex.Token, body); rec.Code != want {
			t.Errorf("PUT devices/me %s = %d, want %d", body, rec.Code, want)
		}
	}
}
