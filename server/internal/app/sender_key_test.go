package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/push"
)

func getAs(t *testing.T, h http.Handler, token, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func errorCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var b struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &b); err != nil {
		t.Fatalf("body %q: %v", rec.Body, err)
	}
	return b.Error
}

// addDeviceWithID inserts a user and a device with fixed ids (the shared vectors name them).
func addDeviceWithID(t *testing.T, d *sql.DB, user, device string) person {
	t.Helper()
	tok, hash, err := auth.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`INSERT OR IGNORE INTO users (id, display_name, created_at) VALUES (?, 'u', 0)`, []any{user}},
		{`INSERT INTO devices (id, user_id, name, token_hash, fcm_token, created_at) VALUES (?, ?, 'd', ?, ?, 0)`,
			[]any{device, user, hash, "tok-" + device}},
	} {
		if _, err := d.ExecContext(t.Context(), q.sql, q.args...); err != nil {
			t.Fatal(err)
		}
	}
	return person{user: user, device: device, token: tok}
}

// TestSenderKeyRoundTripFromSharedVectors sends the shared vectors' envelope and sender key
// through POST /v1/pages, the protocol-2 WebSocket and GET /v1/pages/{id}, and checks that the
// signed objects come back byte for byte, with the error order of protocol section 6.
func TestSenderKeyRoundTripFromSharedVectors(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "testdata", "protocol-vectors.json"))
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		SenderKeys struct {
			Recover []struct {
				Name      string `json:"name"`
				Envelope  string `json:"envelope"`
				SenderKey string `json:"sender_key"`
			} `json:"recover"`
		} `json:"sender_keys"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatal(err)
	}
	var envelope, senderKey map[string]string
	for _, r := range file.SenderKeys.Recover {
		if r.Name == "ok" {
			if json.Unmarshal([]byte(r.Envelope), &envelope) != nil || json.Unmarshal([]byte(r.SenderKey), &senderKey) != nil {
				t.Fatal("bad vector")
			}
		}
	}
	if envelope == nil || senderKey == nil {
		t.Fatal("no ok recover vector")
	}
	const (
		pageID    = "5f0c2a9e-3b1d-4c7a-9e21-6d8b0f4a7c13"
		sessionID = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
		senderDev = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
		toUser    = "5d1a9f30-2c7e-4b16-8a40-e3b9d6c25f07"
		recvDev   = "7b2d0e94-a1c8-4f53-9d16-b5e8a3c47f20"
		recvDev2  = "a4f6b830-9e21-4c7d-b5a8-1f0d6e2c8b93"
	)

	start := time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)
	a, d := bootLogged(t, filepath.Join(t.TempDir(), "pttd.db"), clock.NewFake(start), push.NewFake(), &logBuf{})
	t.Cleanup(func() { a.Close(context.WithoutCancel(t.Context())); _ = d.Close() })
	sender := addDeviceWithID(t, d, uuid.NewString(), senderDev)
	recv := addDeviceWithID(t, d, toUser, recvDev)
	addDeviceWithID(t, d, toUser, recvDev2)
	stranger := addPerson(t, d, "stranger")
	h := a.PublicHandler()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)

	rec := call(t, h, sender.token, "/v1/pages", map[string]any{
		"id": pageID, "to": toUser, "level": "normal", "session_id": sessionID, "envelope": envelope,
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create = %d %s", rec.Code, rec.Body)
	}
	path := "/v1/pages/" + pageID

	// Error order: unauthenticated, malformed id, unknown page, not the sender, no key yet.
	if rec := getAs(t, h, "", path); rec.Code != http.StatusUnauthorized {
		t.Errorf("no token = %d", rec.Code)
	}
	if rec := getAs(t, h, sender.token, "/v1/pages/nope"); rec.Code != http.StatusBadRequest {
		t.Errorf("bad id = %d", rec.Code)
	}
	if rec := getAs(t, h, sender.token, "/v1/pages/"+uuid.NewString()); rec.Code != http.StatusNotFound || errorCode(t, rec) != "not_found" {
		t.Errorf("unknown page = %d %s", rec.Code, rec.Body)
	}
	for name, p := range map[string]person{"a recipient": recv, "a stranger": stranger} {
		if rec := getAs(t, h, p.token, path); rec.Code != http.StatusForbidden || errorCode(t, rec) != "forbidden" {
			t.Errorf("%s = %d %s, want 403 forbidden", name, rec.Code, rec.Body)
		}
	}
	if rec := getAs(t, h, sender.token, path); rec.Code != http.StatusNotFound || errorCode(t, rec) != "not_found" {
		t.Errorf("no sender key yet = %d %s", rec.Code, rec.Body)
	}

	// The sender joins on protocol 2 and sends its sender key.
	ws := dialLive(t, srv)
	ws.send(map[string]any{"type": "hello", "protocol": 2, "token": sender.token, "app_version": "0.0.1"})
	if m := ws.recv(); m["type"] != "hello" || m["protocol"] != float64(2) || m["device_id"] != senderDev {
		t.Fatalf("hello reply = %v", m)
	}
	ws.send(map[string]any{"type": "join", "session_id": sessionID})
	ws.want("page_state", "page_id", pageID, "state", "ringing")
	ws.send(map[string]any{"type": "sender_key", "key": senderKey})
	ws.send(map[string]any{"type": "ping", "t": 1})
	ws.want("pong")

	rec = getAs(t, h, sender.token, path)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET = %d %s", rec.Code, rec.Body)
	}
	var got struct {
		Envelope  map[string]string `json:"envelope"`
		SenderKey map[string]string `json:"sender_key"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	for name, pair := range map[string][2]map[string]string{
		"envelope": {got.Envelope, envelope}, "sender_key": {got.SenderKey, senderKey},
	} {
		if len(pair[0]) != 3 {
			t.Errorf("%s has fields %v, want exactly payload, signer, sig", name, pair[0])
		}
		for _, f := range []string{"payload", "signer", "sig"} {
			if pair[0][f] != pair[1][f] {
				t.Errorf("%s.%s = %q, want the vector's %q", name, f, pair[0][f], pair[1][f])
			}
		}
	}

	// No side effects: a repeat returns the same body and the page has not been "delivered".
	if again := getAs(t, h, sender.token, path); again.Code != http.StatusOK || again.Body.String() != rec.Body.String() {
		t.Errorf("repeat GET = %d %s", again.Code, again.Body)
	}
	var state string
	var delivered sql.NullInt64
	if err := d.QueryRowContext(t.Context(), `SELECT state, delivered_at FROM active_pages WHERE id = ?`, pageID).Scan(&state, &delivered); err != nil {
		t.Fatal(err)
	}
	if state != "ringing" || delivered.Valid {
		t.Errorf("GET changed the page: state %q, delivered_at %v", state, delivered)
	}
	// Still the sender only, with a key stored.
	if rec := getAs(t, h, recv.token, path); rec.Code != http.StatusForbidden {
		t.Errorf("recipient after key = %d", rec.Code)
	}

	// The end deletes the row, and with it the key: the tombstone answers 410 to anyone.
	if rec := call(t, h, sender.token, path+"/cancel", nil); rec.Code != http.StatusNoContent {
		t.Fatalf("cancel = %d", rec.Code)
	}
	for name, p := range map[string]person{"sender": sender, "recipient": recv, "stranger": stranger} {
		if rec := getAs(t, h, p.token, path); rec.Code != http.StatusGone || errorCode(t, rec) != "page_ended" {
			t.Errorf("%s after the end = %d %s, want 410 page_ended", name, rec.Code, rec.Body)
		}
	}
	var rows int
	_ = d.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM active_pages WHERE sender_key IS NOT NULL`).Scan(&rows)
	if rows != 0 {
		t.Errorf("%d rows still hold a sender key", rows)
	}
}
