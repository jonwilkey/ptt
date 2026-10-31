package ws

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

var t0 = time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)

const recvTimeout = 3 * time.Second

// fixture is a Hub and a page Service over a temp database, a fake clock and a fake FCM, with
// two people: Alex (device A, the sender) and Sam (devices S1 and S2).
type fixture struct {
	t    *testing.T
	db   *sql.DB
	clk  *clock.Fake
	fcm  *push.Fake
	svc  *pages.Service
	hub  *Hub
	srv  *httptest.Server
	priv ed25519.PrivateKey
	// authHook, when set, runs inside every Authenticate (it is the authenticator's clock).
	authHook atomic.Pointer[func()]

	alexUser, samUser string
	ids               map[string]string // device name -> id
	tokens            map[string]string // device name -> bearer token
}

func newFixture(t *testing.T) *fixture {
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
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	f := &fixture{
		t: t, db: d, clk: clock.NewFake(t0), fcm: push.NewFake(), priv: priv,
		alexUser: uuid.NewString(), samUser: uuid.NewString(),
		ids: map[string]string{}, tokens: map[string]string{},
	}
	f.svc = pages.NewService(pages.Options{DB: d, Sender: f.fcm, Clock: f.clk})
	f.hub = New(Options{DB: d, Ender: f.svc, Clock: f.clk, Auth: auth.NewAuthenticator(d, nil, func() time.Time {
		if h := f.authHook.Load(); h != nil {
			(*h)()
		}
		return f.clk.Now()
	})})
	f.svc.SetNotifier(f.hub)
	// A server that has been up for a while; the restart grace has its own tests.
	f.hub.started = t0.Add(-time.Hour)
	f.srv = httptest.NewServer(f.hub)
	t.Cleanup(func() {
		f.srv.CloseClientConnections()
		f.srv.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		f.hub.Shutdown(ctx)
		f.svc.Close(ctx)
	})

	f.exec(`INSERT INTO users (id, display_name, created_at) VALUES (?, 'Alex', 0), (?, 'Sam', 0)`, f.alexUser, f.samUser)
	f.device("A", f.alexUser)
	f.device("S1", f.samUser)
	f.device("S2", f.samUser)
	return f
}

func (f *fixture) exec(q string, args ...any) {
	f.t.Helper()
	if _, err := f.db.ExecContext(f.t.Context(), q, args...); err != nil {
		f.t.Fatalf("%s: %v", q, err)
	}
}

func (f *fixture) count(q string, args ...any) int {
	f.t.Helper()
	var n int
	if err := f.db.QueryRowContext(f.t.Context(), q, args...).Scan(&n); err != nil {
		f.t.Fatalf("%s: %v", q, err)
	}
	return n
}

// device inserts a device with a real token and FCM token "tok-<name>".
func (f *fixture) device(name, user string) {
	f.t.Helper()
	tok, hash, err := auth.NewToken()
	if err != nil {
		f.t.Fatal(err)
	}
	id := uuid.NewString()
	f.exec(`INSERT INTO devices (id, user_id, name, token_hash, fcm_token, created_at) VALUES (?, ?, ?, ?, ?, 0)`,
		id, user, name, hash, "tok-"+name)
	f.ids[name], f.tokens[name] = id, tok
}

type page struct{ id, session string }

// page creates a normal page from A to Sam's S1 and S2 (or to Alex's own device with toSelf).
func (f *fixture) page() page { return f.pageTo(f.samUser, "S1", "S2") }

func (f *fixture) pageTo(user string, keyDevices ...string) page {
	f.t.Helper()
	p := page{id: uuid.NewString(), session: uuid.NewString()}
	keys := map[string]string{}
	for _, k := range keyDevices {
		keys[f.ids[k]] = wire.B64u.EncodeToString(make([]byte, pages.WrappedKeyBytes))
	}
	payload, _ := json.Marshal(map[string]any{
		"format": 1, "page_id": p.id, "session_id": p.session, "from_device": f.ids["A"],
		"to_user": user, "level": "normal", "created_at": f.clk.Now().UnixMilli(), "keys": keys,
	})
	req := pages.CreateRequest{
		ID: p.id, To: user, Level: "normal", SessionID: p.session,
		Envelope: signed.Sign(f.priv, f.ids["A"], signed.ContextEnvelope, payload).Encode(),
	}
	res, err := f.svc.Create(f.t.Context(), pages.Caller{DeviceID: f.ids["A"], UserID: f.alexUser}, req)
	if err != nil || res.State != pages.StateRinging {
		f.t.Fatalf("Create = %+v, %v", res, err)
	}
	f.svc.Wait()
	f.fcm.Clear()
	return p
}

// fcmNow returns the FCM messages recorded so far, as "type[:detail]->token", sorted.
func (f *fixture) fcmNow() []string {
	var out []string
	for _, m := range f.fcm.Messages() {
		s := m.Data["type"]
		if d := m.Data["reason"] + m.Data["state"]; d != "" {
			s += ":" + d
		}
		out = append(out, s+"->"+m.Token)
	}
	sort.Strings(out)
	return out
}

// wantFCM waits for the FCM messages recorded since the last call to be exactly want, then
// clears the recorder. Pushes go out on background goroutines, so it polls; an empty want waits
// a moment and then checks that nothing arrived.
func (f *fixture) wantFCM(want ...string) {
	f.t.Helper()
	sort.Strings(want)
	if len(want) == 0 {
		time.Sleep(100 * time.Millisecond)
	}
	var got []string
	for deadline := time.Now().Add(recvTimeout); ; time.Sleep(10 * time.Millisecond) {
		got = f.fcmNow()
		if strings.Join(got, ",") == strings.Join(want, ",") || time.Now().After(deadline) {
			break
		}
	}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		f.t.Fatalf("FCM:\n got %q\nwant %q", got, want)
	}
	f.fcm.Clear()
}

func (f *fixture) pageGone(p page) bool {
	f.t.Helper()
	return f.count(`SELECT COUNT(*) FROM active_pages WHERE id = ?`, p.id) == 0 &&
		f.count(`SELECT COUNT(*) FROM page_tombstones WHERE id = ?`, p.id) == 1
}

// ---- a test client -------------------------------------------------------------------

type client struct {
	f    *fixture
	name string
	ws   *websocket.Conn
}

// dial opens a socket without saying hello.
func (f *fixture) dial() *client {
	f.t.Helper()
	ctx, cancel := context.WithTimeout(f.t.Context(), recvTimeout)
	defer cancel()
	c, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(f.srv.URL, "http")+"/v1/ws", nil)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		f.t.Fatalf("dial: %v", err)
	}
	c.SetReadLimit(1 << 20)
	cl := &client{f: f, ws: c}
	f.t.Cleanup(func() { _ = c.CloseNow() })
	return cl
}

// connect dials and completes hello as the named device.
func (f *fixture) connect(name string) *client {
	f.t.Helper()
	c := f.dial()
	c.name = name
	c.send(map[string]any{"type": "hello", "protocol": 1, "token": f.tokens[name], "app_version": "0.0.1"})
	m := c.recv()
	if m["type"] != "hello" || m["device_id"] != f.ids[name] || m["protocol"] != float64(1) {
		f.t.Fatalf("hello reply = %v", m)
	}
	return c
}

// joined connects and joins, returning after the snapshot.
func (f *fixture) joined(name string, p page) (*client, map[string]any) {
	f.t.Helper()
	c := f.connect(name)
	c.send(map[string]any{"type": "join", "session_id": p.session})
	return c, c.recv()
}

func (c *client) send(v any) {
	c.f.t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		c.f.t.Fatal(err)
	}
	c.sendRaw(raw)
}

func (c *client) sendRaw(raw []byte) {
	c.f.t.Helper()
	ctx, cancel := context.WithTimeout(c.f.t.Context(), recvTimeout)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageText, raw); err != nil {
		c.f.t.Fatalf("write: %v", err)
	}
}

func (c *client) sendBinary(b []byte) {
	c.f.t.Helper()
	ctx, cancel := context.WithTimeout(c.f.t.Context(), recvTimeout)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageBinary, b); err != nil {
		c.f.t.Fatalf("write: %v", err)
	}
}

// recv reads the next JSON message.
func (c *client) recv() map[string]any {
	c.f.t.Helper()
	ctx, cancel := context.WithTimeout(c.f.t.Context(), recvTimeout)
	defer cancel()
	typ, raw, err := c.ws.Read(ctx)
	if err != nil {
		c.f.t.Fatalf("%s: read: %v", c.name, err)
	}
	if typ != websocket.MessageText {
		c.f.t.Fatalf("%s: got a binary frame", c.name)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		c.f.t.Fatalf("%s: bad JSON %q", c.name, raw)
	}
	return m
}

// want reads one message and compares it, as "type:key=value,..." for the given keys.
func (c *client) want(typ string, kv ...string) map[string]any {
	c.f.t.Helper()
	m := c.recv()
	if m["type"] != typ {
		c.f.t.Fatalf("%s: got %v, want type %s", c.name, m, typ)
	}
	for i := 0; i+1 < len(kv); i += 2 {
		got := fmt.Sprint(m[kv[i]])
		if n, ok := m[kv[i]].(float64); ok {
			got = strconv.FormatFloat(n, 'f', -1, 64)
		}
		if got != kv[i+1] {
			c.f.t.Fatalf("%s: %s = %v in %v, want %s", c.name, kv[i], m[kv[i]], m, kv[i+1])
		}
	}
	return m
}

// wantClose reads until the socket closes and checks the close code (and reason token).
func (c *client) wantClose(code int, reason string) {
	c.f.t.Helper()
	ctx, cancel := context.WithTimeout(c.f.t.Context(), recvTimeout)
	defer cancel()
	for {
		_, _, err := c.ws.Read(ctx)
		if err == nil {
			continue
		}
		if got := int(websocket.CloseStatus(err)); got != code {
			c.f.t.Fatalf("%s: closed with %d (%v), want %d", c.name, got, err, code)
		}
		var ce websocket.CloseError
		if reason != "" && errors.As(err, &ce) && ce.Reason != reason {
			c.f.t.Fatalf("%s: close reason %q, want %q", c.name, ce.Reason, reason)
		}
		return
	}
}

// ping round-trips a ping, which also proves the server has processed everything before it.
func (c *client) ping() {
	c.f.t.Helper()
	c.send(map[string]any{"type": "ping", "t": 1})
	c.want("pong")
}

// advance moves the fake clock by d in steps short of the silence timeout, with every client
// pinging after each step so none of them is timed out on the way.
func (f *fixture) advance(d time.Duration, clients ...*client) {
	f.t.Helper()
	for d > 0 {
		step := min(d, 20*time.Second)
		f.clk.Advance(step)
		d -= step
		for _, c := range clients {
			c.ping()
		}
	}
}
