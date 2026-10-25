package httpapi

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/adminops"
	"github.com/jonwilkey/ptt/server/internal/signed"
)

// member is an enrolled device whose Ed25519 key the test holds.
type member struct {
	enrolled
	priv ed25519.PrivateKey
}

func (m member) entry() map[string]any {
	return map[string]any{
		"id": m.DeviceID, "user_id": m.UserID, "display_name": m.DisplayName,
		"device_name": "Test phone", "admin": m.Admin,
		"ed25519": m.field("ed25519"), "x25519": m.field("x25519"),
	}
}

func (e *testEnv) enrollKeyed(code, mac string) member {
	e.t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		e.t.Fatal(err)
	}
	body := enrollBody(code, mac)
	body["ed25519"] = b64(pub)
	return member{enrolled: e.enrollWith(body), priv: priv}
}

func (e *testEnv) keyedAdmin() member {
	e.t.Helper()
	code, err := adminops.Bootstrap(e.t.Context(), e.db, "Alex", false, e.now)
	if err != nil {
		e.t.Fatal(err)
	}
	return e.enrollKeyed(code, "")
}

func (e *testEnv) keyedMember(admin member, name string, makeAdmin bool) member {
	e.t.Helper()
	c := e.newCode(admin.enrolled, CreateCodeRequest{DisplayName: name, MakeAdmin: &makeAdmin})
	return e.enrollKeyed(c.Code, randB64(32))
}

// rosterPayload builds a payload with entries sorted by id.
func rosterPayload(version int64, entries ...map[string]any) []byte {
	sort.Slice(entries, func(i, j int) bool { return fmt.Sprint(entries[i]["id"]) < fmt.Sprint(entries[j]["id"]) })
	b, _ := json.Marshal(map[string]any{"format": 1, "version": version, "issued_at": 1789920000000, "devices": entries})
	return b
}

func signRoster(signer member, payload []byte) signed.Wire {
	return signed.Sign(signer.priv, signer.DeviceID, signed.ContextRoster, payload).Encode()
}

func (e *testEnv) putRoster(caller member, body any) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.call(http.MethodPut, "/v1/roster", caller.Token, body)
}

func TestRosterPublishFlow(t *testing.T) {
	e := newEnv(t)
	admin := e.keyedAdmin()

	// No roster yet.
	wantError(t, e.call(http.MethodGet, "/v1/roster", admin.Token, nil), http.StatusNotFound, "not_found")

	first := signRoster(admin, rosterPayload(1, admin.entry()))
	if rec := e.putRoster(admin, first); rec.Code != http.StatusNoContent {
		t.Fatalf("first roster: %d %s", rec.Code, rec.Body)
	}
	rec := e.call(http.MethodGet, "/v1/roster", admin.Token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("get: %d %s", rec.Code, rec.Body)
	}
	if got := decode[signed.Wire](t, rec); got != first {
		t.Errorf("stored roster = %+v, want %+v", got, first)
	}
	if want, _ := json.Marshal(first); !bytes.Equal(rec.Body.Bytes(), want) {
		t.Errorf("GET body = %s, want %s", rec.Body.Bytes(), want)
	}

	// A second admin-signed roster adds an enrolled device; any device can read it.
	sam := e.keyedMember(admin, "Sam", false)
	second := signRoster(admin, rosterPayload(2, admin.entry(), sam.entry()))
	if rec := e.putRoster(admin, second); rec.Code != http.StatusNoContent {
		t.Fatalf("second roster: %d %s", rec.Code, rec.Body)
	}
	rec = e.call(http.MethodGet, "/v1/roster", sam.Token, nil)
	if got := decode[signed.Wire](t, rec); rec.Code != http.StatusOK || got != second {
		t.Errorf("sam get = %d %+v", rec.Code, got)
	}
	if want, _ := json.Marshal(second); !bytes.Equal(rec.Body.Bytes(), want) {
		t.Errorf("sam GET body = %s, want %s", rec.Body.Bytes(), want)
	}

	if n := e.auditCount("roster_published"); n != 2 {
		t.Errorf("roster_published rows = %d, want 2", n)
	}
	var actor, detail string
	err := e.db.QueryRowContext(t.Context(),
		`SELECT actor_device, detail FROM audit_log WHERE action = 'roster_published' ORDER BY id DESC LIMIT 1`).Scan(&actor, &detail)
	if err != nil || actor != admin.DeviceID || detail != `{"devices":2,"version":2}` {
		t.Errorf("audit = %q %q %v", actor, detail, err)
	}
	var signer string
	if err := e.db.QueryRowContext(t.Context(), `SELECT signer_device FROM roster`).Scan(&signer); err != nil || signer != admin.DeviceID {
		t.Errorf("signer_device = %q %v", signer, err)
	}

	// Sam, listed as a non-admin, cannot publish.
	wantError(t, e.putRoster(sam, signRoster(sam, rosterPayload(3, admin.entry(), sam.entry()))), http.StatusForbidden, "forbidden")
	// GET without a token is 401.
	wantError(t, e.call(http.MethodGet, "/v1/roster", "", nil), http.StatusUnauthorized, "unauthorized")
}

func TestRosterRejections(t *testing.T) {
	e := newEnv(t)
	admin := e.keyedAdmin()
	sam := e.keyedMember(admin, "Sam", false)
	ann := e.keyedMember(admin, "Ann", true)
	if rec := e.putRoster(admin, signRoster(admin, rosterPayload(5, admin.entry(), sam.entry(), ann.entry()))); rec.Code != http.StatusNoContent {
		t.Fatalf("setup: %d %s", rec.Code, rec.Body)
	}
	late := e.keyedMember(admin, "Late", false)

	mod := func(m member, key string, v any) map[string]any {
		c := m.entry()
		c[key] = v
		return c
	}
	// A signer's own key signs everything below unless a case overrides it.
	cases := []struct {
		name   string
		caller member
		body   func() any
		status int
		code   string
	}{
		{"stale equal version", admin, func() any { return signRoster(admin, rosterPayload(5, admin.entry(), sam.entry(), ann.entry())) }, 409, "roster_stale"},
		{"stale lower version", admin, func() any { return signRoster(admin, rosterPayload(4, admin.entry(), sam.entry(), ann.entry())) }, 409, "roster_stale"},
		{"signer not admin in current roster", admin, func() any { return signRoster(sam, rosterPayload(6, admin.entry(), sam.entry(), ann.entry())) }, 400, "roster_invalid"},
		{"signer not in current roster", admin, func() any { return signRoster(late, rosterPayload(6, admin.entry(), sam.entry(), ann.entry())) }, 400, "roster_invalid"},
		{"mismatched ed25519", admin, func() any {
			return signRoster(admin, rosterPayload(6, admin.entry(), mod(sam, "ed25519", randB64(32))))
		}, 400, "roster_invalid"},
		{"mismatched x25519", admin, func() any {
			return signRoster(admin, rosterPayload(6, admin.entry(), mod(sam, "x25519", randB64(32))))
		}, 400, "roster_invalid"},
		{"mismatched user", admin, func() any {
			return signRoster(admin, rosterPayload(6, admin.entry(), mod(sam, "user_id", uuid.NewString())))
		}, 400, "roster_invalid"},
		{"mismatched admin flag", admin, func() any {
			return signRoster(admin, rosterPayload(6, admin.entry(), mod(sam, "admin", true)))
		}, 400, "roster_invalid"},
		{"mismatched display name", admin, func() any {
			return signRoster(admin, rosterPayload(6, admin.entry(), mod(sam, "display_name", "Samantha")))
		}, 400, "roster_invalid"},
		{"unknown device", admin, func() any {
			ghost := mod(sam, "id", uuid.NewString())
			return signRoster(admin, rosterPayload(6, admin.entry(), ghost))
		}, 400, "roster_invalid"},
		{"unsorted devices", admin, func() any {
			ents := []map[string]any{admin.entry(), sam.entry()}
			if fmt.Sprint(ents[0]["id"]) < fmt.Sprint(ents[1]["id"]) {
				ents[0], ents[1] = ents[1], ents[0]
			}
			b, _ := json.Marshal(map[string]any{"format": 1, "version": 6, "issued_at": 1, "devices": ents})
			return signRoster(admin, b)
		}, 400, "roster_invalid"},
		{"unknown payload field", admin, func() any {
			var m map[string]any
			_ = json.Unmarshal(rosterPayload(6, admin.entry()), &m)
			m["extra"] = 1
			b, _ := json.Marshal(m)
			return signRoster(admin, b)
		}, 400, "roster_invalid"},
		{"payload not json", admin, func() any { return signRoster(admin, []byte("nope")) }, 400, "roster_invalid"},
		{"bad signature", admin, func() any {
			w := signRoster(admin, rosterPayload(6, admin.entry(), sam.entry(), ann.entry()))
			w.Sig = b64(make([]byte, 64))
			return w
		}, 400, "roster_invalid"},
		{"signed by another key", admin, func() any {
			w := signRoster(admin, rosterPayload(6, admin.entry(), sam.entry(), ann.entry()))
			w.Signer = ann.DeviceID
			return w
		}, 400, "roster_invalid"},
		{"wrong context", admin, func() any {
			return signed.Sign(admin.priv, admin.DeviceID, signed.ContextEnvelope, rosterPayload(6, admin.entry())).Encode()
		}, 400, "roster_invalid"},
		{"unknown signer", admin, func() any {
			w := signRoster(admin, rosterPayload(6, admin.entry()))
			w.Signer = uuid.NewString()
			return w
		}, 400, "roster_invalid"},
		{"non-admin caller", sam, func() any { return signRoster(admin, rosterPayload(6, admin.entry(), sam.entry())) }, 403, "forbidden"},
		{"malformed signer id", admin, func() any {
			w := signRoster(admin, rosterPayload(6, admin.entry()))
			w.Signer = "nope"
			return w
		}, 400, "invalid_request"},
		{"short signature", admin, func() any {
			w := signRoster(admin, rosterPayload(6, admin.entry()))
			w.Sig = w.Sig[:80]
			return w
		}, 400, "invalid_request"},
		{"unknown envelope field", admin, func() any { return `{"payload":"AAAA","signer":"x","sig":"y","extra":1}` }, 400, "invalid_request"},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			wantError(t, e.putRoster(tt.caller, tt.body()), tt.status, tt.code)
			var v int64
			if err := e.db.QueryRowContext(t.Context(), `SELECT version FROM roster`).Scan(&v); err != nil || v != 5 {
				t.Fatalf("stored version = %d %v, want 5", v, err)
			}
		})
	}

	t.Run("revoked device listed", func(t *testing.T) {
		e.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, late.DeviceID)
		wantError(t, e.putRoster(admin, signRoster(admin, rosterPayload(6, admin.entry(), late.entry()))), 400, "roster_invalid")
	})
	t.Run("test device listed", func(t *testing.T) {
		e.exec(`UPDATE devices SET is_test = 1 WHERE id = ?`, sam.DeviceID)
		wantError(t, e.putRoster(admin, signRoster(admin, rosterPayload(6, admin.entry(), sam.entry()))), 400, "roster_invalid")
		e.exec(`UPDATE devices SET is_test = 0 WHERE id = ?`, sam.DeviceID)
	})
	t.Run("signer revoked", func(t *testing.T) {
		e.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, ann.DeviceID)
		wantError(t, e.putRoster(admin, signRoster(ann, rosterPayload(6, admin.entry(), sam.entry()))), 400, "roster_invalid")
	})
	t.Run("test device signer", func(t *testing.T) {
		e.exec(`UPDATE devices SET is_test = 1 WHERE id = ?`, ann.DeviceID)
		e.exec(`UPDATE devices SET revoked_at = NULL WHERE id = ?`, ann.DeviceID)
		wantError(t, e.putRoster(admin, signRoster(ann, rosterPayload(6, admin.entry(), sam.entry()))), 400, "roster_invalid")
	})
}

func TestRosterWithoutAdminRejected(t *testing.T) {
	e := newEnv(t)
	admin := e.keyedAdmin()
	sam := e.keyedMember(admin, "Sam", false)
	if rec := e.putRoster(admin, signRoster(admin, rosterPayload(1, admin.entry(), sam.entry()))); rec.Code != http.StatusNoContent {
		t.Fatalf("setup: %d %s", rec.Code, rec.Body)
	}
	wantError(t, e.putRoster(admin, signRoster(admin, rosterPayload(2, sam.entry()))), 400, "roster_invalid")
}

func TestFirstRosterRules(t *testing.T) {
	t.Run("signer must list itself as admin", func(t *testing.T) {
		e := newEnv(t)
		admin := e.keyedAdmin()
		self := admin.entry()
		self["admin"] = false
		wantError(t, e.putRoster(admin, signRoster(admin, rosterPayload(1, self))), 400, "roster_invalid")
		wantError(t, e.putRoster(admin, signRoster(admin, rosterPayload(1))), 400, "roster_invalid")
	})
	t.Run("signer user must be admin", func(t *testing.T) {
		e := newEnv(t)
		admin := e.keyedAdmin()
		sam := e.keyedMember(admin, "Sam", false)
		// Another admin's call, signed by a non-admin device.
		wantError(t, e.putRoster(admin, signRoster(sam, rosterPayload(1, admin.entry(), sam.entry()))), 400, "roster_invalid")
	})
	t.Run("admin signer revoked", func(t *testing.T) {
		e := newEnv(t)
		admin := e.keyedAdmin()
		other := e.keyedMember(admin, "Ann", true)
		e.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, admin.DeviceID)
		wantError(t, e.putRoster(other, signRoster(admin, rosterPayload(1, admin.entry()))), 400, "roster_invalid")
	})
}

type recordingNotifier struct {
	mu       sync.Mutex
	versions []int64
}

func (n *recordingNotifier) RosterPublished(_ context.Context, v int64) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.versions = append(n.versions, v)
}

func TestRosterNotifierAndConcurrency(t *testing.T) {
	e := newEnv(t)
	n := &recordingNotifier{}
	e.h = NewPublic(PublicOptions{DB: e.db, Now: func() time.Time { return e.now }, RosterNotifier: n})
	admin := e.keyedAdmin()
	sam := e.keyedMember(admin, "Sam", false)

	if rec := e.putRoster(admin, signRoster(admin, rosterPayload(1, admin.entry()))); rec.Code != http.StatusNoContent {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	// A rejected roster does not notify.
	e.putRoster(admin, signRoster(admin, rosterPayload(1, admin.entry())))

	// Two different version-2 rosters race: exactly one wins, the other is stale.
	bodies := []signed.Wire{
		signRoster(admin, rosterPayload(2, admin.entry())),
		signRoster(admin, rosterPayload(2, admin.entry(), sam.entry())),
	}
	// Requests are built here: t.Fatal must not run in the goroutines.
	reqs := make([]*http.Request, len(bodies))
	for i, b := range bodies {
		raw, err := json.Marshal(b)
		if err != nil {
			t.Fatal(err)
		}
		reqs[i] = httptest.NewRequestWithContext(t.Context(), http.MethodPut, "/v1/roster", bytes.NewReader(raw))
		reqs[i].Header.Set("Authorization", "Bearer "+admin.Token)
	}
	codes := make([]int, len(bodies))
	var wg sync.WaitGroup
	for i := range bodies {
		wg.Add(1)
		go func() {
			defer wg.Done()
			rec := httptest.NewRecorder()
			e.h.ServeHTTP(rec, reqs[i])
			codes[i] = rec.Code
		}()
	}
	wg.Wait()
	if codes[0]+codes[1] != 204+409 || codes[0] == codes[1] {
		t.Fatalf("racing publishes = %v, want one 204 and one 409", codes)
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.versions) != 2 || n.versions[0] != 1 || n.versions[1] != 2 {
		t.Errorf("notified versions = %v, want [1 2]", n.versions)
	}
}
