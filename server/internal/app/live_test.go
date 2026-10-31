package app

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"log/slog"
	mrand "math/rand/v2"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// logBuf collects log output across goroutines.
type logBuf struct {
	mu sync.Mutex
	sb strings.Builder
}

func (l *logBuf) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.sb.Write(p)
}

func (l *logBuf) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.sb.String()
}

func bootLogged(t *testing.T, path string, clk clock.Clock, fake *push.Fake, logs *logBuf) (*App, *sql.DB) {
	t.Helper()
	d, err := db.Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	m, err := db.NewMigrator(d, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Up(t.Context()); err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	a := New("test", logger, d, m, WithSender(fake, fake), WithClock(clk))
	a.Start()
	return a, d
}

// liveClient is a WebSocket client of the real HTTP server.
type liveClient struct {
	t  *testing.T
	ws *websocket.Conn
}

func dialLive(t *testing.T, srv *httptest.Server) *liveClient {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	c, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/v1/ws", nil)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	return &liveClient{t: t, ws: c}
}

func (c *liveClient) send(v any) {
	c.t.Helper()
	raw, _ := json.Marshal(v)
	ctx, cancel := context.WithTimeout(c.t.Context(), 3*time.Second)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageText, raw); err != nil {
		c.t.Fatal(err)
	}
}

func (c *liveClient) recv() map[string]any {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(c.t.Context(), 3*time.Second)
	defer cancel()
	_, raw, err := c.ws.Read(ctx)
	if err != nil {
		c.t.Fatalf("read: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		c.t.Fatal(err)
	}
	return m
}

func (c *liveClient) want(typ string, kv ...string) map[string]any {
	c.t.Helper()
	m := c.recv()
	if m["type"] != typ {
		c.t.Fatalf("got %v, want a %s", m, typ)
	}
	for i := 0; i+1 < len(kv); i += 2 {
		if m[kv[i]] != kv[i+1] {
			c.t.Fatalf("%s = %v in %v, want %s", kv[i], m[kv[i]], m, kv[i+1])
		}
	}
	return m
}

func (c *liveClient) wantClose(code int) {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(c.t.Context(), 3*time.Second)
	defer cancel()
	for {
		_, _, err := c.ws.Read(ctx)
		if err == nil {
			continue
		}
		if got := int(websocket.CloseStatus(err)); got != code {
			c.t.Fatalf("closed with %d (%v), want %d", got, err, code)
		}
		return
	}
}

func (c *liveClient) hello(p person) {
	c.t.Helper()
	c.send(map[string]any{"type": "hello", "protocol": 1, "token": p.token, "app_version": "0.0.1"})
	c.want("hello", "device_id", p.device)
}

func metricsText(t *testing.T, a *App) string {
	t.Helper()
	rec := httptest.NewRecorder()
	a.InternalHandler().ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/metrics", nil))
	return rec.Body.String()
}

// makePage creates a page from alex to sam over REST.
func makePage(t *testing.T, h http.Handler, now time.Time, alex, sam person) (id, session string) {
	t.Helper()
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	id, session = uuid.NewString(), uuid.NewString()
	payload, _ := json.Marshal(map[string]any{
		"format": 1, "page_id": id, "session_id": session, "from_device": alex.device, "to_user": sam.user,
		"level": "normal", "created_at": now.UnixMilli(),
		"keys": map[string]string{sam.device: wire.B64u.EncodeToString(make([]byte, 80))},
	})
	rec := call(t, h, alex.token, "/v1/pages", map[string]any{
		"id": id, "to": sam.user, "level": "normal", "session_id": session,
		"envelope": signed.Sign(priv, alex.device, signed.ContextEnvelope, payload).Encode(),
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create page = %d %s", rec.Code, rec.Body)
	}
	return id, session
}

// TestLiveTalkOverRealHTTP runs the whole milestone-4 control flow of task 14b against a real
// HTTP server: two WebSocket clients, a temp SQLite database and the fake FCM.
func TestLiveTalkOverRealHTTP(t *testing.T) {
	start := time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)
	fake := push.NewFake()
	logs := &logBuf{}
	a, d := bootLogged(t, filepath.Join(t.TempDir(), "pttd.db"), clock.NewFake(start), fake, logs)
	t.Cleanup(func() { a.Close(context.WithoutCancel(t.Context())); _ = d.Close() })
	alex, sam := addPerson(t, d, "alex"), addPerson(t, d, "sam")
	h := a.PublicHandler()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)

	id, session := makePage(t, h, start, alex, sam)
	a.pageSvc.Wait()
	if got := summarize(fake); !equal(got, []string{"page->tok-sam"}) {
		t.Fatalf("pushes after create = %v", got)
	}

	// The sender joins and waits.
	alexWS := dialLive(t, srv)
	alexWS.hello(alex)
	alexWS.send(map[string]any{"type": "join", "session_id": session})
	alexWS.want("page_state", "page_id", id, "state", "ringing")

	// Sam's phone reports delivery: it reaches the sender over the socket, not FCM.
	if rec := call(t, h, sam.token, "/v1/pages/"+id+"/delivered", nil); rec.Code != http.StatusOK {
		t.Fatalf("delivered = %d %s", rec.Code, rec.Body)
	}
	alexWS.want("page_state", "state", "delivered")

	// Sam answers: the sender hears it on the socket and, always, by FCM.
	if rec := call(t, h, sam.token, "/v1/pages/"+id+"/answer", nil); rec.Code != http.StatusNoContent {
		t.Fatalf("answer = %d %s", rec.Code, rec.Body)
	}
	alexWS.want("page_state", "state", "answered")
	a.pageSvc.Wait()
	if got := summarize(fake); !equal(got, []string{"page_answered->tok-alex"}) {
		t.Fatalf("pushes after answer = %v", got)
	}

	samWS := dialLive(t, srv)
	samWS.hello(sam)
	samWS.send(map[string]any{"type": "join", "session_id": session})
	samWS.want("page_state", "state", "answered")
	samWS.want("peer_joined", "device_id", alex.device)
	alexWS.want("peer_joined", "device_id", sam.device)

	out := metricsText(t, a)
	for _, want := range []string{"pttd_active_sessions 1", "pttd_ws_active_sockets 2"} {
		if !strings.Contains(out, want) {
			t.Errorf("/metrics lacks %q", want)
		}
	}

	// One side ends the session: both hear it, the row is gone, the tombstone is there.
	samWS.send(map[string]any{"type": "session_end"})
	samWS.want("session_end", "reason", "ended_by_you")
	alexWS.want("session_end", "reason", "ended_by_peer")
	samWS.wantClose(1000)
	alexWS.wantClose(1000)
	var active, tombstones int
	_ = d.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM active_pages`).Scan(&active)
	_ = d.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM page_tombstones WHERE id = ?`, id).Scan(&tombstones)
	if active != 0 || tombstones != 1 {
		t.Errorf("active pages = %d, tombstones = %d", active, tombstones)
	}
	// Both were live: no FCM fallback for the end.
	a.pageSvc.Wait()
	if got := summarize(fake); len(got) != 0 {
		t.Errorf("pushes after the end = %v", got)
	}
	// The end is final over REST too (idempotent cancel, then a late retry of the create).
	if rec := call(t, h, alex.token, "/v1/pages/"+id+"/cancel", nil); rec.Code != http.StatusNoContent {
		t.Errorf("cancel after end = %d", rec.Code)
	}
	waitFor(t, func() bool {
		out := metricsText(t, a)
		return strings.Contains(out, "pttd_active_sessions 0") && strings.Contains(out, "pttd_ws_active_sockets 0")
	})

	// Privacy: no page, session, device or user id, and no token, reached the logs or metrics.
	all := logs.String() + metricsText(t, a)
	for _, secret := range []string{id, session, alex.device, sam.device, alex.user, sam.user, alex.token, sam.token} {
		if strings.Contains(all, secret) {
			t.Errorf("an identifier or token appears in logs or metrics")
		}
	}
	var auditDetail sql.NullString
	_ = d.QueryRowContext(t.Context(), `SELECT group_concat(coalesce(detail, ''), ' ') FROM audit_log`).Scan(&auditDetail)
	for _, secret := range []string{id, session} {
		if strings.Contains(auditDetail.String, secret) {
			t.Error("a page or session id reached the audit log")
		}
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatal("condition not reached")
}

// TestRestartWhileRingingClosesSocketsAndKeepsPages is a pttd restart with a page ringing and a
// sender waiting on its socket: the socket gets 4503, the page survives in the database, and the
// sender reconnects and joins the page again.
func TestRestartWhileRingingClosesSocketsAndKeepsPages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pttd.db")
	start := time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)
	fake := push.NewFake()
	logs := &logBuf{}

	a1, d1 := bootLogged(t, path, clock.NewFake(start), fake, logs)
	alex, sam := addPerson(t, d1, "alex"), addPerson(t, d1, "sam")
	h1 := a1.PublicHandler()
	srv1 := httptest.NewServer(h1)
	id, session := makePage(t, h1, start, alex, sam)
	alexWS := dialLive(t, srv1)
	alexWS.hello(alex)
	alexWS.send(map[string]any{"type": "join", "session_id": session})
	alexWS.want("page_state", "state", "ringing")

	// Restart: the app is closed (sockets first), the database too.
	a1.Close(context.WithoutCancel(t.Context()))
	alexWS.wantClose(4503)
	srv1.Close()
	if err := d1.Close(); err != nil {
		t.Fatal(err)
	}

	// Forty seconds later the new process finds the page, still ringing and undelivered.
	clk2 := clock.NewFake(start.Add(40 * time.Second))
	a2, d2 := bootLogged(t, path, clk2, fake, logs)
	t.Cleanup(func() { a2.Close(context.WithoutCancel(t.Context())); _ = d2.Close() })
	var n int
	if err := d2.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM active_pages WHERE id = ?`, id).Scan(&n); err != nil || n != 1 {
		t.Fatalf("the page did not survive the restart: %d %v", n, err)
	}
	srv2 := httptest.NewServer(a2.PublicHandler())
	t.Cleanup(srv2.Close)
	again := dialLive(t, srv2)
	again.hello(alex)
	again.send(map[string]any{"type": "join", "session_id": session})
	again.want("page_state", "state", "not_delivered")

	// A new server refuses new upgrades while shutting down.
	a2.hub.Shutdown(context.WithoutCancel(t.Context()))
	again.wantClose(4503)
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, srv2.URL+"/v1/ws", nil)
	resp, err := http.DefaultClient.Do(req)
	if err == nil {
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusServiceUnavailable {
			t.Errorf("upgrade during shutdown = %d, want 503", resp.StatusCode)
		}
	}
}

func (c *liveClient) sendBinary(b []byte) {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(c.t.Context(), 3*time.Second)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageBinary, b); err != nil {
		c.t.Fatal(err)
	}
}

func (c *liveClient) recvFrame() []byte {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(c.t.Context(), 3*time.Second)
	defer cancel()
	typ, raw, err := c.ws.Read(ctx)
	if err != nil {
		c.t.Fatalf("read: %v", err)
	}
	if typ != websocket.MessageBinary {
		c.t.Fatalf("got text %q, want a frame", raw)
	}
	return raw
}

func liveFrame(tx string, seq uint32, body []byte) []byte {
	id := uuid.MustParse(tx)
	b := append([]byte{0x01}, id[:]...)
	b = binary.BigEndian.AppendUint32(b, seq)
	return append(b, body...)
}

// TestLiveTalkRelaysOpaqueFramesBothWays is the milestone-4 integration test: two real
// clients over a real HTTP server join, the sender talks (opaque frames arrive identically and
// in order), releases, the answerer talks back, and the session ends.
func TestLiveTalkRelaysOpaqueFramesBothWays(t *testing.T) {
	start := time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)
	fake := push.NewFake()
	a, d := bootLogged(t, filepath.Join(t.TempDir(), "pttd.db"), clock.NewFake(start), fake, &logBuf{})
	t.Cleanup(func() { a.Close(context.WithoutCancel(t.Context())); _ = d.Close() })
	alex, sam := addPerson(t, d, "alex"), addPerson(t, d, "sam")
	h := a.PublicHandler()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)

	id, session := makePage(t, h, start, alex, sam)
	alexWS := dialLive(t, srv)
	alexWS.hello(alex)
	alexWS.send(map[string]any{"type": "join", "session_id": session})
	alexWS.want("page_state", "state", "ringing")
	if rec := call(t, h, sam.token, "/v1/pages/"+id+"/answer", nil); rec.Code != http.StatusNoContent {
		t.Fatalf("answer = %d %s", rec.Code, rec.Body)
	}
	alexWS.want("page_state", "state", "answered")
	samWS := dialLive(t, srv)
	samWS.hello(sam)
	samWS.send(map[string]any{"type": "join", "session_id": session})
	samWS.want("page_state", "state", "answered")
	samWS.want("peer_joined", "device_id", alex.device)
	alexWS.want("peer_joined", "device_id", sam.device)

	// Alex talks: N opaque frames reach Sam identically and in order.
	tx1 := uuid.NewString()
	alexWS.send(map[string]any{"type": "floor_request", "transmission_id": tx1})
	alexWS.want("floor_granted", "talker", alex.device, "transmission_id", tx1)
	samWS.want("floor_granted", "talker", alex.device, "transmission_id", tx1)
	r := mrand.New(mrand.NewPCG(7, 8))
	for i := range 100 {
		body := make([]byte, 40+i%30)
		for j := range body {
			body[j] = byte(r.UintN(256))
		}
		fr := liveFrame(tx1, uint32(i), body)
		alexWS.sendBinary(fr)
		// In lock step: a listener that does not read loses frames past 25 queued (by design).
		if got := samWS.recvFrame(); !bytes.Equal(got, fr) {
			t.Fatalf("frame %d differs from what was sent", i)
		}
	}
	alexWS.send(map[string]any{"type": "floor_release", "transmission_id": tx1})
	alexWS.want("floor_released", "transmission_id", tx1, "reason", "released")
	samWS.want("floor_released", "transmission_id", tx1, "reason", "released")

	// Sam talks back; Alex receives.
	tx2 := uuid.NewString()
	samWS.send(map[string]any{"type": "floor_request", "transmission_id": tx2})
	samWS.want("floor_granted", "talker", sam.device, "transmission_id", tx2)
	alexWS.want("floor_granted", "talker", sam.device, "transmission_id", tx2)
	back := liveFrame(tx2, 0, bytes.Repeat([]byte{0xAB}, 48))
	samWS.sendBinary(back)
	if got := alexWS.recvFrame(); !bytes.Equal(got, back) {
		t.Fatal("reply frame differs")
	}
	samWS.send(map[string]any{"type": "floor_release", "transmission_id": tx2})
	samWS.want("floor_released", "reason", "released")
	alexWS.want("floor_released", "reason", "released")

	samWS.send(map[string]any{"type": "session_end"})
	samWS.want("session_end", "reason", "ended_by_you")
	alexWS.want("session_end", "reason", "ended_by_peer")
	samWS.wantClose(1000)
	alexWS.wantClose(1000)
	waitFor(t, func() bool { return strings.Contains(metricsText(t, a), "pttd_ws_frames_relayed_total 101") })
}
