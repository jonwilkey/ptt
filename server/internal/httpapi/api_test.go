package httpapi

import (
	"bytes"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/adminops"
	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db"
)

// testWriter sends server logs to the test log, so an unexpected 500 explains itself.
type testWriter struct{ t *testing.T }

func (w testWriter) Write(p []byte) (int, error) {
	w.t.Helper()
	w.t.Log(strings.TrimSpace(string(p)))
	return len(p), nil
}

// testEnv is a real router over a temp migrated SQLite database and a fake clock.
type testEnv struct {
	t   *testing.T
	db  *sql.DB
	h   http.Handler
	now time.Time
	ips int
}

func newEnv(t *testing.T) *testEnv {
	t.Helper()
	d, err := db.Open(t.Context(), filepath.Join(t.TempDir(), "pttd.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = d.Close() })
	m, err := db.NewMigrator(d, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Up(t.Context()); err != nil {
		t.Fatal(err)
	}
	e := &testEnv{t: t, db: d, now: time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)}
	e.h = NewPublic(PublicOptions{Logger: slog.New(slog.NewTextHandler(testWriter{t}, nil)), DB: d, Now: func() time.Time { return e.now }})
	return e
}

func (e *testEnv) advance(d time.Duration) { e.now = e.now.Add(d) }

// call sends a JSON request; body may be nil, a string (sent verbatim) or a value to marshal.
func (e *testEnv) call(method, path, token string, body any, hdr ...string) *httptest.ResponseRecorder {
	e.t.Helper()
	var rd *bytes.Reader
	switch b := body.(type) {
	case nil:
		rd = bytes.NewReader(nil)
	case string:
		rd = bytes.NewReader([]byte(b))
	default:
		raw, err := json.Marshal(b)
		if err != nil {
			e.t.Fatal(err)
		}
		rd = bytes.NewReader(raw)
	}
	if token != "" {
		hdr = append(hdr, "Authorization", "Bearer "+token)
	}
	return do(e.t, e.h, method, path, rd, hdr...)
}

func (e *testEnv) exec(q string, args ...any) {
	e.t.Helper()
	if _, err := e.db.ExecContext(e.t.Context(), q, args...); err != nil {
		e.t.Fatalf("%s: %v", q, err)
	}
}

func (e *testEnv) count(q string, args ...any) int {
	e.t.Helper()
	var n int
	if err := e.db.QueryRowContext(e.t.Context(), q, args...).Scan(&n); err != nil {
		e.t.Fatalf("%s: %v", q, err)
	}
	return n
}

func b64(raw []byte) string { return base64.RawURLEncoding.EncodeToString(raw) }

func randB64(n int) string {
	raw := make([]byte, n)
	_, _ = rand.Read(raw)
	return b64(raw)
}

// enrollBody is a valid POST /v1/enroll body for code; mac is "" for bootstrap codes.
func enrollBody(code, mac string) map[string]any {
	body := map[string]any{
		"code":        code,
		"device_id":   uuid.NewString(),
		"device_name": "Test phone",
		"ed25519":     randB64(32),
		"x25519":      randB64(32),
		"fcm_token":   "fcm-token",
		"app_version": "0.2.0",
	}
	if mac != "" {
		body["mac"] = mac
	}
	return body
}

// enrolled is a device created through the real API.
type enrolled struct {
	EnrollResponse
	body map[string]any
}

func (d enrolled) field(k string) string {
	s, _ := d.body[k].(string)
	return s
}

func (e *testEnv) enroll(code, mac string) enrolled {
	e.t.Helper()
	return e.enrollWith(enrollBody(code, mac))
}

// enrollWith enrolls using body, which a test may have customised (for example with real keys).
func (e *testEnv) enrollWith(body map[string]any) enrolled {
	e.t.Helper()
	// Each enrollment comes from its own address, so the per-IP limit never gets in the way.
	e.ips++
	rec := e.call(http.MethodPost, "/v1/enroll", "", body, "X-Forwarded-For", "198.51.100."+strconv.Itoa(e.ips))
	if rec.Code != http.StatusCreated {
		e.t.Fatalf("enroll status = %d, body %s", rec.Code, rec.Body)
	}
	e.advance(time.Second)
	return enrolled{EnrollResponse: decode[EnrollResponse](e.t, rec), body: body}
}

// bootstrapAdmin enrolls the first admin the way the family does.
func (e *testEnv) bootstrapAdmin() enrolled {
	e.t.Helper()
	code, err := adminops.Bootstrap(e.t.Context(), e.db, "Alex", false, e.now)
	if err != nil {
		e.t.Fatal(err)
	}
	return e.enroll(code, "")
}

// newCode has admin mint a code; it returns the code and its id.
func (e *testEnv) newCode(admin enrolled, req CreateCodeRequest) CreateCodeResponse {
	e.t.Helper()
	rec := e.call(http.MethodPost, "/v1/enrollment-codes", admin.Token, req)
	if rec.Code != http.StatusCreated {
		e.t.Fatalf("create code status = %d, body %s", rec.Code, rec.Body)
	}
	return decode[CreateCodeResponse](e.t, rec)
}

func (e *testEnv) auditCount(action string) int {
	return e.count(`SELECT COUNT(*) FROM audit_log WHERE action = ?`, action)
}

func wantError(t *testing.T, rec *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if rec.Code != status {
		t.Fatalf("status = %d, want %d (body %s)", rec.Code, status, rec.Body)
	}
	if got := decode[ErrorResponse](t, rec); got.Error != code {
		t.Fatalf("error = %q, want %q", got.Error, code)
	}
}

func TestEnrollmentFlow(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	if admin.DisplayName != "Alex" || !admin.Admin || !validUUID(admin.UserID) || len(admin.Token) != 43 {
		t.Fatalf("bootstrap enroll = %+v", admin.EnrollResponse)
	}

	// The admin invites a second person.
	mk := e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"})
	if want := e.now.Add(24 * time.Hour).UnixMilli(); mk.ExpiresAt != want {
		t.Errorf("expires_at = %d, want %d", mk.ExpiresAt, want)
	}
	status := func(codeID string) CodeStatusResponse {
		rec := e.call(http.MethodGet, "/v1/enrollment-codes/"+codeID, admin.Token, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("code status = %d, body %s", rec.Code, rec.Body)
		}
		return decode[CodeStatusResponse](t, rec)
	}
	if got := status(mk.CodeID); got.Status != "pending" || got.Device != nil {
		t.Fatalf("before redeem: %+v", got)
	}

	mac := randB64(32)
	sam := e.enroll(mk.Code, mac)
	if sam.DisplayName != "Sam" || sam.Admin || sam.UserID == admin.UserID {
		t.Fatalf("second enroll = %+v", sam.EnrollResponse)
	}

	got := status(mk.CodeID)
	want := CodeDevice{
		ID: sam.DeviceID, UserID: sam.UserID, DisplayName: "Sam", DeviceName: "Test phone", Admin: false,
		Ed25519: sam.field("ed25519"), X25519: sam.field("x25519"), MAC: mac,
	}
	if got.Status != "used" || got.Device == nil || *got.Device != want {
		t.Fatalf("after redeem: %+v, want device %+v", got, want)
	}

	// Redeeming again is code_invalid.
	rec := e.call(http.MethodPost, "/v1/enroll", "", enrollBody(mk.Code, mac))
	wantError(t, rec, http.StatusBadRequest, "code_invalid")

	// A second phone for Sam: the code inherits the person.
	more := e.newCode(admin, CreateCodeRequest{UserID: sam.UserID})
	sam2 := e.enroll(more.Code, randB64(32))
	if sam2.UserID != sam.UserID || sam2.DisplayName != "Sam" || sam2.Admin {
		t.Fatalf("add-device enroll = %+v", sam2.EnrollResponse)
	}
	if n := e.count(`SELECT COUNT(*) FROM users`); n != 2 {
		t.Errorf("users = %d, want 2", n)
	}

	// Every enrollment is audited with the new device as actor, and no secret is stored.
	if n := e.auditCount("device_enrolled"); n != 3 {
		t.Errorf("device_enrolled rows = %d, want 3", n)
	}
	var actor, detail string
	err := e.db.QueryRowContext(t.Context(),
		`SELECT actor_device, detail FROM audit_log WHERE action = 'device_enrolled' ORDER BY id LIMIT 1`).Scan(&actor, &detail)
	if err != nil {
		t.Fatal(err)
	}
	if actor != admin.DeviceID || !strings.Contains(detail, admin.UserID) || strings.Contains(detail, admin.Token) {
		t.Errorf("audit actor=%q detail=%q", actor, detail)
	}
	if n := e.count(`SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%' || ? || '%'`, mk.Code); n != 0 {
		t.Error("an audit row contains the enrollment code")
	}

	// The new token works.
	if rec := e.call(http.MethodGet, "/v1/contacts", sam.Token, nil); rec.Code != http.StatusOK {
		t.Errorf("contacts with new token = %d", rec.Code)
	}
}

func TestEnrollmentCodeStatuses(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	c := e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"})

	e.advance(24*time.Hour - time.Millisecond)
	rec := e.call(http.MethodGet, "/v1/enrollment-codes/"+c.CodeID, admin.Token, nil)
	if got := decode[CodeStatusResponse](t, rec); got.Status != "pending" {
		t.Errorf("just before expiry: %+v", got)
	}
	// expires_at is exclusive: at that instant the code is expired and cannot be redeemed.
	e.advance(time.Millisecond)
	rec = e.call(http.MethodGet, "/v1/enrollment-codes/"+c.CodeID, admin.Token, nil)
	if got := decode[CodeStatusResponse](t, rec); got.Status != "expired" || got.Device != nil {
		t.Errorf("at expiry: %+v", got)
	}
	rec = e.call(http.MethodPost, "/v1/enroll", "", enrollBody(c.Code, randB64(32)))
	wantError(t, rec, http.StatusBadRequest, "code_invalid")

	for _, tt := range []struct {
		name, id string
		status   int
		code     string
	}{
		{"unknown", randB64(32), http.StatusNotFound, "not_found"},
		{"short", "abc", http.StatusBadRequest, "invalid_request"},
		{"padded", randB64(32) + "=", http.StatusBadRequest, "invalid_request"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			wantError(t, e.call(http.MethodGet, "/v1/enrollment-codes/"+tt.id, admin.Token, nil), tt.status, tt.code)
		})
	}
}

func TestCreateEnrollmentCode(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	sam := e.enroll(e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code, randB64(32))
	yes, no := true, false

	tests := []struct {
		name string
		body any
		ok   bool
	}{
		{"new person", CreateCodeRequest{DisplayName: "Robin"}, true},
		{"new admin", CreateCodeRequest{DisplayName: "Robin", MakeAdmin: &yes}, true},
		{"existing user", CreateCodeRequest{UserID: sam.UserID}, true},
		{"existing user, matching make_admin", CreateCodeRequest{UserID: sam.UserID, MakeAdmin: &no}, true},
		{"existing user, matching name", CreateCodeRequest{UserID: sam.UserID, DisplayName: "Sam"}, true},
		{"existing user, make_admin differs", CreateCodeRequest{UserID: sam.UserID, MakeAdmin: &yes}, false},
		{"existing user, name differs", CreateCodeRequest{UserID: sam.UserID, DisplayName: "Other"}, false},
		{"unknown user", CreateCodeRequest{UserID: uuid.NewString()}, false},
		{"malformed user id", CreateCodeRequest{UserID: "nope"}, false},
		{"uppercase user id", CreateCodeRequest{UserID: strings.ToUpper(sam.UserID)}, false},
		{"missing name", CreateCodeRequest{}, false},
		{"blank name", CreateCodeRequest{DisplayName: "  "}, false},
		{"long name", CreateCodeRequest{DisplayName: strings.Repeat("x", 65)}, false},
		{"unknown field", `{"display_name":"Robin","extra":1}`, false},
		{"not json", `nope`, false},
		{"empty body", ``, false},
		{"null body", `null`, false},
		{"trailing data", `{"display_name":"Robin"} {}`, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := e.call(http.MethodPost, "/v1/enrollment-codes", admin.Token, tt.body)
			if tt.ok {
				if rec.Code != http.StatusCreated {
					t.Fatalf("status = %d, body %s", rec.Code, rec.Body)
				}
				return
			}
			wantError(t, rec, http.StatusBadRequest, "invalid_request")
		})
	}

	// make_admin takes effect at enrollment.
	c := e.newCode(admin, CreateCodeRequest{DisplayName: "Pat", MakeAdmin: &yes})
	if pat := e.enroll(c.Code, randB64(32)); !pat.Admin {
		t.Error("make_admin code did not produce an admin")
	}

	// Minting is audited with the admin as actor and the public code_id, never the code.
	var actor, detail string
	err := e.db.QueryRowContext(t.Context(),
		`SELECT actor_device, detail FROM audit_log WHERE action = 'enrollment_code_created' ORDER BY id DESC LIMIT 1`).
		Scan(&actor, &detail)
	if err != nil {
		t.Fatal(err)
	}
	var d struct {
		CodeID    string  `json:"code_id"`
		UserID    *string `json:"user_id"`
		MakeAdmin bool    `json:"make_admin"`
	}
	if err := json.Unmarshal([]byte(detail), &d); err != nil {
		t.Fatal(err)
	}
	if actor != admin.DeviceID || d.CodeID != c.CodeID || d.UserID != nil || !d.MakeAdmin || strings.Contains(detail, c.Code) {
		t.Errorf("audit actor=%q detail=%s", actor, detail)
	}
	userCode := e.newCode(admin, CreateCodeRequest{UserID: sam.UserID})
	err = e.db.QueryRowContext(t.Context(),
		`SELECT detail FROM audit_log WHERE action = 'enrollment_code_created' ORDER BY id DESC LIMIT 1`).Scan(&detail)
	if err != nil || !strings.Contains(detail, userCode.CodeID) || !strings.Contains(detail, sam.UserID) {
		t.Errorf("user code audit detail = %s (%v)", detail, err)
	}
}

// bootstrapCode inserts an SSH-minted (MAC-less) code directly, since Bootstrap refuses once an
// admin exists.
func (e *testEnv) bootstrapCode(name string, expiresAt time.Time) string {
	e.t.Helper()
	code, hash, err := auth.NewEnrollmentCode()
	if err != nil {
		e.t.Fatal(err)
	}
	e.exec(`INSERT INTO enrollment_codes (code_hash, display_name, make_admin, created_at, expires_at) VALUES (?, ?, 1, ?, ?)`,
		hash, name, e.now.UnixMilli(), expiresAt.UnixMilli())
	return code
}

func TestEnrollValidation(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	adminCode := e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code
	sshCode := e.bootstrapCode("Root", e.now.Add(time.Hour))
	expiredCode := e.bootstrapCode("Old", e.now)

	existing := admin.DeviceID
	upper := strings.ToUpper(uuid.NewString())
	tests := []struct {
		name   string
		code   string
		mutate func(b map[string]any)
		status int
		errMsg string
	}{
		{"unknown code", adminCode, func(b map[string]any) { b["code"] = randB64(16) }, 400, "code_invalid"},
		{"expired code", expiredCode, nil, 400, "code_invalid"},
		{"malformed code", adminCode, func(b map[string]any) { b["code"] = "short" }, 400, "invalid_request"},
		{"missing code", adminCode, func(b map[string]any) { delete(b, "code") }, 400, "invalid_request"},
		{"existing device id", adminCode, func(b map[string]any) { b["device_id"] = existing }, 409, "conflict"},
		{"uppercase device id", adminCode, func(b map[string]any) { b["device_id"] = upper }, 400, "invalid_request"},
		{"missing device id", adminCode, func(b map[string]any) { delete(b, "device_id") }, 400, "invalid_request"},
		{"padded ed25519", adminCode, func(b map[string]any) { b["ed25519"] = randB64(32) + "=" }, 400, "invalid_request"},
		{"short ed25519", adminCode, func(b map[string]any) { b["ed25519"] = randB64(31) }, 400, "invalid_request"},
		{"std alphabet x25519", adminCode, func(b map[string]any) { b["x25519"] = strings.Repeat("+", 43) }, 400, "invalid_request"},
		{"noncanonical x25519", adminCode, func(b map[string]any) { b["x25519"] = strings.Repeat("A", 42) + "B" }, 400, "invalid_request"},
		{"missing x25519", adminCode, func(b map[string]any) { delete(b, "x25519") }, 400, "invalid_request"},
		{"missing mac on admin code", adminCode, func(b map[string]any) { delete(b, "mac") }, 400, "invalid_request"},
		{"null mac on admin code", adminCode, func(b map[string]any) { b["mac"] = nil }, 400, "invalid_request"},
		{"short mac", adminCode, func(b map[string]any) { b["mac"] = randB64(16) }, 400, "invalid_request"},
		{"mac on bootstrap code", sshCode, func(b map[string]any) { b["mac"] = randB64(32) }, 400, "invalid_request"},
		{"missing device name", adminCode, func(b map[string]any) { delete(b, "device_name") }, 400, "invalid_request"},
		{"long device name", adminCode, func(b map[string]any) { b["device_name"] = strings.Repeat("n", 65) }, 400, "invalid_request"},
		{"control char in name", adminCode, func(b map[string]any) { b["device_name"] = "a\nb" }, 400, "invalid_request"},
		{"long app version", adminCode, func(b map[string]any) { b["app_version"] = strings.Repeat("1", 33) }, 400, "invalid_request"},
		{"huge fcm token", adminCode, func(b map[string]any) { b["fcm_token"] = strings.Repeat("t", 1025) }, 400, "invalid_request"},
		{"unknown field", adminCode, func(b map[string]any) { b["role"] = "admin" }, 400, "invalid_request"},
	}
	for i, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			mac := randB64(32)
			if tt.code == sshCode {
				mac = ""
			}
			body := enrollBody(tt.code, mac)
			if tt.mutate != nil {
				tt.mutate(body)
			}
			rec := e.call(http.MethodPost, "/v1/enroll", "", body, "X-Forwarded-For", "203.0.113."+strconv.Itoa(i+1))
			wantError(t, rec, tt.status, tt.errMsg)
		})
	}

	// Nothing above consumed or half-created anything: both live codes still work.
	if n := e.count(`SELECT COUNT(*) FROM devices`); n != 1 {
		t.Errorf("devices = %d, want only the admin", n)
	}
	if n := e.count(`SELECT COUNT(*) FROM users`); n != 1 {
		t.Errorf("users = %d, want only the admin", n)
	}
	e.enroll(adminCode, randB64(32))
	e.enroll(sshCode, "")

	for name, body := range map[string]string{"not json": "nope", "empty": "", "array": "[]", "trailing": `{} {}`} {
		rec := e.call(http.MethodPost, "/v1/enroll", "", body, "X-Forwarded-For", "192.0.2.1")
		if rec.Code != http.StatusBadRequest {
			t.Errorf("%s: status %d, want 400", name, rec.Code)
		}
	}
}

func TestEnrollRateLimit(t *testing.T) {
	e := newEnv(t)
	attempt := func(ip string) *httptest.ResponseRecorder {
		// An invalid body still counts: the limiter exists to slow code guessing.
		return e.call(http.MethodPost, "/v1/enroll", "", `{}`, "X-Forwarded-For", ip)
	}
	for i := range 5 {
		if rec := attempt("198.51.100.1, 10.0.0.1"); rec.Code != http.StatusBadRequest {
			t.Fatalf("attempt %d: status %d, want 400", i, rec.Code)
		}
		e.advance(time.Minute)
	}
	// Five minutes in; the first attempt leaves the window in 55 minutes.
	rec := attempt("198.51.100.1")
	wantError(t, rec, http.StatusTooManyRequests, "rate_limited")
	if got := rec.Header().Get("Retry-After"); got != strconv.Itoa(55*60) {
		t.Errorf("Retry-After = %q, want %d", got, 55*60)
	}
	// Only the first X-Forwarded-For entry identifies the client; other addresses are separate.
	if rec := attempt("198.51.100.2, 198.51.100.1"); rec.Code != http.StatusBadRequest {
		t.Errorf("other IP: status %d, want 400", rec.Code)
	}
	// An IPv6 client is limited by its /64.
	for range 5 {
		attempt("2001:db8::1")
	}
	wantError(t, attempt("2001:db8::ffff"), http.StatusTooManyRequests, "rate_limited")
	// Without X-Forwarded-For the peer address counts (httptest uses 192.0.2.1).
	for range 5 {
		e.call(http.MethodPost, "/v1/enroll", "", `{}`)
	}
	wantError(t, e.call(http.MethodPost, "/v1/enroll", "", `{}`), http.StatusTooManyRequests, "rate_limited")

	e.advance(55 * time.Minute)
	if rec := attempt("198.51.100.1"); rec.Code != http.StatusBadRequest {
		t.Errorf("after the window: status %d, want 400", rec.Code)
	}
}

func TestAuthenticate(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	validButUnknown := randB64(32)

	for _, tt := range []struct {
		name string
		hdr  []string
	}{
		{"no header", nil},
		{"wrong scheme", []string{"Authorization", "Basic " + admin.Token}},
		{"no token", []string{"Authorization", "Bearer"}},
		{"padded token", []string{"Authorization", "Bearer " + admin.Token + "="}},
		{"short token", []string{"Authorization", "Bearer abc"}},
		{"unknown token", []string{"Authorization", "Bearer " + validButUnknown}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			rec := e.call(http.MethodGet, "/v1/contacts", "", nil, tt.hdr...)
			wantError(t, rec, http.StatusUnauthorized, "unauthorized")
		})
	}

	// Only the well-formed unknown token is audited (once per minute), and without any token.
	if n := e.auditCount("auth_failure"); n != 1 {
		t.Fatalf("auth_failure rows = %d, want 1", n)
	}
	for range 3 {
		e.call(http.MethodGet, "/v1/contacts", validButUnknown, nil)
	}
	if n := e.auditCount("auth_failure"); n != 1 {
		t.Errorf("auth_failure rows = %d after a flood, want 1", n)
	}
	e.advance(time.Minute)
	e.call(http.MethodGet, "/v1/contacts", validButUnknown, nil)
	if n := e.auditCount("auth_failure"); n != 2 {
		t.Errorf("auth_failure rows = %d after the window, want 2", n)
	}
	if n := e.count(`SELECT COUNT(*) FROM audit_log WHERE action = 'auth_failure' AND (actor_device IS NOT NULL OR detail IS NOT NULL)`); n != 0 {
		t.Error("auth_failure rows carry an actor or detail")
	}
	// Unauthenticated endpoints stay open.
	if rec := e.call(http.MethodGet, "/healthz", "", nil); rec.Code == http.StatusUnauthorized {
		t.Error("/healthz requires auth")
	}
}

func TestRevokedTokenIsRejectedImmediately(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	sam := e.enroll(e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code, randB64(32))

	if rec := e.call(http.MethodGet, "/v1/contacts", sam.Token, nil); rec.Code != http.StatusOK {
		t.Fatalf("before revoke: %d", rec.Code)
	}
	rec := e.call(http.MethodPost, "/v1/devices/"+sam.DeviceID+"/revoke", admin.Token, nil)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status = %d, body %s", rec.Code, rec.Body)
	}
	wantError(t, e.call(http.MethodGet, "/v1/contacts", sam.Token, nil), http.StatusUnauthorized, "unauthorized")
	wantError(t, e.call(http.MethodPut, "/v1/devices/me", sam.Token, `{}`), http.StatusUnauthorized, "unauthorized")
}

func TestAdminRoutesRequireAdmin(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	sam := e.enroll(e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code, randB64(32))
	code := e.newCode(admin, CreateCodeRequest{DisplayName: "Robin"})

	for _, tt := range []struct{ method, path, body string }{
		{http.MethodPost, "/v1/enrollment-codes", `{"display_name":"X"}`},
		{http.MethodGet, "/v1/enrollment-codes/" + code.CodeID, ""},
		{http.MethodPost, "/v1/devices/" + admin.DeviceID + "/revoke", ""},
	} {
		wantError(t, e.call(tt.method, tt.path, sam.Token, tt.body), http.StatusForbidden, "forbidden")
		wantError(t, e.call(tt.method, tt.path, "", tt.body), http.StatusUnauthorized, "unauthorized")
	}
	if n := e.count(`SELECT COUNT(*) FROM devices WHERE revoked_at IS NOT NULL`); n != 0 {
		t.Error("a non-admin revoked a device")
	}
}

func TestRevokeDevice(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	sam := e.enroll(e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code, randB64(32))
	path := func(id string) string { return "/v1/devices/" + id + "/revoke" }

	wantError(t, e.call(http.MethodPost, path(uuid.NewString()), admin.Token, nil), http.StatusNotFound, "not_found")
	wantError(t, e.call(http.MethodPost, path("nope"), admin.Token, nil), http.StatusBadRequest, "invalid_request")
	wantError(t, e.call(http.MethodPost, path(strings.ToUpper(sam.DeviceID)), admin.Token, nil), http.StatusBadRequest, "invalid_request")

	for range 2 {
		if rec := e.call(http.MethodPost, path(sam.DeviceID), admin.Token, nil); rec.Code != http.StatusNoContent {
			t.Fatalf("revoke status = %d, body %s", rec.Code, rec.Body)
		}
	}
	var actor string
	err := e.db.QueryRowContext(t.Context(), `SELECT actor_device FROM audit_log WHERE action = 'device_revoked'`).Scan(&actor)
	if err != nil || actor != admin.DeviceID {
		t.Errorf("audit actor = %q, %v; want %q", actor, err, admin.DeviceID)
	}
	if n := e.auditCount("device_revoked"); n != 1 {
		t.Errorf("device_revoked rows = %d, want 1 (idempotent)", n)
	}

	// An admin may revoke their own device, and is then locked out.
	if rec := e.call(http.MethodPost, path(admin.DeviceID), admin.Token, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("self revoke status = %d", rec.Code)
	}
	wantError(t, e.call(http.MethodGet, "/v1/contacts", admin.Token, nil), http.StatusUnauthorized, "unauthorized")
}

func TestUpdateSelf(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	row := func() (name, fcm, ver, checklist sql.NullString) {
		err := e.db.QueryRowContext(t.Context(),
			`SELECT name, fcm_token, app_version, checklist_status FROM devices WHERE id = ?`, admin.DeviceID).
			Scan(&name, &fcm, &ver, &checklist)
		if err != nil {
			t.Fatal(err)
		}
		return
	}

	rec := e.call(http.MethodPut, "/v1/devices/me", admin.Token,
		`{"fcm_token":"new-fcm","app_version":"0.3.0","device_name":"Renamed","checklist":{"battery_ok":false,"dnd_ok":true}}`)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body)
	}
	name, fcm, ver, cl := row()
	var items map[string]bool
	if err := json.Unmarshal([]byte(cl.String), &items); err != nil || items["battery_ok"] || !items["dnd_ok"] || len(items) != 2 {
		t.Errorf("checklist = %q (%v)", cl.String, err)
	}
	if name.String != "Renamed" || fcm.String != "new-fcm" || ver.String != "0.3.0" {
		t.Errorf("row = %q %q %q", name.String, fcm.String, ver.String)
	}

	// Absent fields are unchanged.
	if rec := e.call(http.MethodPut, "/v1/devices/me", admin.Token, `{"fcm_token":"rotated"}`); rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d", rec.Code)
	}
	name, fcm, ver, cl2 := row()
	if name.String != "Renamed" || fcm.String != "rotated" || ver.String != "0.3.0" || cl2 != cl {
		t.Errorf("partial update changed other fields: %q %q %q %q", name.String, fcm.String, ver.String, cl2.String)
	}
	if rec := e.call(http.MethodPut, "/v1/devices/me", admin.Token, `{}`); rec.Code != http.StatusNoContent {
		t.Errorf("empty update status = %d", rec.Code)
	}

	tooMany := map[string]bool{}
	for i := range 33 {
		tooMany["k"+strconv.Itoa(i)] = true
	}
	for name, body := range map[string]any{
		"unknown field":      `{"nope":1}`,
		"empty fcm token":    `{"fcm_token":""}`,
		"long fcm token":     map[string]string{"fcm_token": strings.Repeat("t", 1025)},
		"long device name":   map[string]string{"device_name": strings.Repeat("n", 65)},
		"blank device name":  `{"device_name":" "}`,
		"long version":       map[string]string{"app_version": strings.Repeat("1", 33)},
		"non-bool checklist": `{"checklist":{"a":"yes"}}`,
		"too many items":     map[string]any{"checklist": tooMany},
		"long item key":      map[string]any{"checklist": map[string]bool{strings.Repeat("k", 65): true}},
		"not json":           `nope`,
	} {
		if rec := e.call(http.MethodPut, "/v1/devices/me", admin.Token, body); rec.Code != http.StatusBadRequest {
			t.Errorf("%s: status %d, want 400", name, rec.Code)
		}
	}
}

func TestLastSeenIsThrottled(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	lastSeen := func() int64 {
		var ms int64
		if err := e.db.QueryRowContext(t.Context(), `SELECT last_seen_at FROM devices WHERE id = ?`, admin.DeviceID).Scan(&ms); err != nil {
			t.Fatal(err)
		}
		return ms
	}
	start := lastSeen()

	e.now = time.UnixMilli(start).Add(time.Minute - time.Millisecond)
	e.call(http.MethodGet, "/v1/contacts", admin.Token, nil)
	if got := lastSeen(); got != start {
		t.Errorf("last_seen_at moved within a minute: %d -> %d", start, got)
	}
	e.advance(time.Millisecond)
	e.call(http.MethodGet, "/v1/contacts", admin.Token, nil)
	if got := lastSeen(); got != e.now.UnixMilli() {
		t.Errorf("last_seen_at = %d, want %d", got, e.now.UnixMilli())
	}
}

func TestContacts(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	code := func(name string) enrolled {
		return e.enroll(e.newCode(admin, CreateCodeRequest{DisplayName: name}).Code, randB64(32))
	}
	addDevice := func(userID string) enrolled {
		return e.enroll(e.newCode(admin, CreateCodeRequest{UserID: userID}).Code, randB64(32))
	}
	setSeen := func(id string, age time.Duration) {
		e.exec(`UPDATE devices SET last_seen_at = ? WHERE id = ?`, e.now.Add(-age).UnixMilli(), id)
	}
	setChecklist := func(id, js string) { e.exec(`UPDATE devices SET checklist_status = ? WHERE id = ?`, js, id) }

	okU := code("Bea")      // seen, no checklist
	warnU := code("Cy")     // seen, a false item
	offU := code("Dee")     // not seen in 48 h
	edgeU := code("Eve")    // exactly 48 h: offline
	multiU := code("Flo")   // two devices
	revokedU := code("Gus") // only a revoked device: hidden
	testU := code("Hal")    // only a test device: hidden
	cleanU := code("Ivy")   // checklist all true

	setSeen(admin.DeviceID, time.Minute)
	setSeen(okU.DeviceID, time.Hour)
	setSeen(warnU.DeviceID, time.Hour)
	setChecklist(warnU.DeviceID, `{"battery_ok":true,"dnd_ok":false}`)
	setSeen(offU.DeviceID, 49*time.Hour)
	setChecklist(offU.DeviceID, `{"battery_ok":false}`) // offline outranks warning
	setSeen(edgeU.DeviceID, 48*time.Hour)
	setSeen(cleanU.DeviceID, time.Hour)
	setChecklist(cleanU.DeviceID, `{"battery_ok":true}`)

	flo2 := addDevice(multiU.UserID)
	setSeen(multiU.DeviceID, 72*time.Hour) // stale, with a failing checklist
	setChecklist(multiU.DeviceID, `{"battery_ok":false}`)
	setSeen(flo2.DeviceID, time.Hour) // fresh, clean: the person is seen, and the warning counts

	e.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, revokedU.DeviceID)
	e.exec(`UPDATE devices SET is_test = 1 WHERE id = ?`, testU.DeviceID)

	rec := e.call(http.MethodGet, "/v1/contacts", admin.Token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body)
	}
	got := decode[ContactsResponse](t, rec)
	want := []Contact{
		{admin.UserID, "Alex", true, "ok"},
		{okU.UserID, "Bea", false, "ok"},
		{warnU.UserID, "Cy", false, "warning"},
		{offU.UserID, "Dee", false, "offline"},
		{edgeU.UserID, "Eve", false, "offline"},
		{multiU.UserID, "Flo", false, "warning"},
		{cleanU.UserID, "Ivy", false, "ok"},
	}
	if len(got.Users) != len(want) {
		t.Fatalf("contacts = %+v, want %+v", got.Users, want)
	}
	for i := range want {
		if got.Users[i] != want[i] {
			t.Errorf("contact %d = %+v, want %+v", i, got.Users[i], want[i])
		}
	}

	// A fresh server with nobody else still returns an empty array, not null.
	e2 := newEnv(t)
	a2 := e2.bootstrapAdmin()
	e2.exec(`UPDATE devices SET is_test = 1`)
	rec = e2.call(http.MethodGet, "/v1/contacts", a2.Token, nil)
	if !strings.Contains(rec.Body.String(), `"users":[]`) {
		t.Errorf("empty contacts body = %s", rec.Body)
	}
}

func TestRoutingStillJSON(t *testing.T) {
	e := newEnv(t)
	wantError(t, e.call(http.MethodGet, "/v1/enroll", "", nil), http.StatusMethodNotAllowed, "method_not_allowed")
	wantError(t, e.call(http.MethodGet, "/v1/nope", "", nil), http.StatusNotFound, "not_found")
}

func TestTestDevicesAreNotAdmins(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	e.exec(`UPDATE devices SET is_test = 1 WHERE id = ?`, admin.DeviceID)
	wantError(t, e.call(http.MethodPost, "/v1/enrollment-codes", admin.Token, `{"display_name":"X"}`),
		http.StatusForbidden, "forbidden")
	// It is still a valid device for everything else.
	if rec := e.call(http.MethodGet, "/v1/contacts", admin.Token, nil); rec.Code != http.StatusOK {
		t.Errorf("contacts status = %d", rec.Code)
	}
}

func TestConcurrentRedemption(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	code := e.newCode(admin, CreateCodeRequest{DisplayName: "Sam"}).Code

	const n = 8
	recs := make([]*httptest.ResponseRecorder, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			// Not e.call: t.Fatal must not run off the test goroutine.
			raw, _ := json.Marshal(enrollBody(code, randB64(32)))
			req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/v1/enroll", bytes.NewReader(raw))
			req.Header.Set("X-Forwarded-For", "203.0.113."+strconv.Itoa(i+1))
			recs[i] = httptest.NewRecorder()
			e.h.ServeHTTP(recs[i], req)
		}()
	}
	wg.Wait()

	created := 0
	for _, rec := range recs {
		switch rec.Code {
		case http.StatusCreated:
			created++
		default:
			wantError(t, rec, http.StatusBadRequest, "code_invalid")
		}
	}
	if created != 1 {
		t.Errorf("%d redemptions succeeded, want exactly 1", created)
	}
	if got := e.count(`SELECT COUNT(*) FROM devices`); got != 2 {
		t.Errorf("devices = %d, want the admin plus one", got)
	}
	if got := e.count(`SELECT COUNT(*) FROM users`); got != 2 {
		t.Errorf("users = %d, want 2", got)
	}
	if got := e.count(`SELECT COUNT(*) FROM enrollment_codes WHERE used_at IS NOT NULL AND device_id IS NOT NULL AND display_name = 'Sam'`); got != 1 {
		t.Errorf("used Sam codes = %d, want 1", got)
	}
}

func TestRevokeExpiresTheDevicesOpenCodes(t *testing.T) {
	e := newEnv(t)
	root := e.bootstrapAdmin()
	yes := true
	pat := e.enroll(e.newCode(root, CreateCodeRequest{DisplayName: "Pat", MakeAdmin: &yes}).Code, randB64(32))

	open1 := e.newCode(pat, CreateCodeRequest{DisplayName: "A"})
	open2 := e.newCode(pat, CreateCodeRequest{DisplayName: "B"})
	used := e.newCode(pat, CreateCodeRequest{DisplayName: "C"})
	e.enroll(used.Code, randB64(32))
	rootsOpen := e.newCode(root, CreateCodeRequest{DisplayName: "D"})

	if rec := e.call(http.MethodPost, "/v1/devices/"+pat.DeviceID+"/revoke", root.Token, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status = %d", rec.Code)
	}
	for _, c := range []CreateCodeResponse{open1, open2} {
		rec := e.call(http.MethodGet, "/v1/enrollment-codes/"+c.CodeID, root.Token, nil)
		if got := decode[CodeStatusResponse](t, rec); got.Status != "expired" {
			t.Errorf("revoked device's open code is %q, want expired", got.Status)
		}
		wantError(t, e.call(http.MethodPost, "/v1/enroll", "", enrollBody(c.Code, randB64(32))), http.StatusBadRequest, "code_invalid")
	}
	if got := decode[CodeStatusResponse](t, e.call(http.MethodGet, "/v1/enrollment-codes/"+used.CodeID, root.Token, nil)); got.Status != "used" {
		t.Errorf("redeemed code is %q, want used", got.Status)
	}
	if got := decode[CodeStatusResponse](t, e.call(http.MethodGet, "/v1/enrollment-codes/"+rootsOpen.CodeID, root.Token, nil)); got.Status != "pending" {
		t.Errorf("another admin's code is %q, want pending", got.Status)
	}

	var detail string
	err := e.db.QueryRowContext(t.Context(), `SELECT detail FROM audit_log WHERE action = 'device_revoked'`).Scan(&detail)
	if err != nil || !strings.Contains(detail, `"expired_codes":2`) {
		t.Errorf("audit detail = %s (%v)", detail, err)
	}
	// A repeat revoke is a no-op: no second audit row.
	e.call(http.MethodPost, "/v1/devices/"+pat.DeviceID+"/revoke", root.Token, nil)
	if n := e.auditCount("device_revoked"); n != 1 {
		t.Errorf("device_revoked rows = %d, want 1", n)
	}
}
