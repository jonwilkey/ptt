package pages

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/push"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// errStub lets a test make FCM fail for chosen tokens.
type stubSender struct {
	*push.Fake
	mu   sync.Mutex
	errs map[string]error
}

func (s *stubSender) Send(ctx context.Context, m push.Message) error {
	s.mu.Lock()
	err := s.errs[m.Token]
	s.mu.Unlock()
	if err != nil {
		return err
	}
	return s.Fake.Send(ctx, m)
}

func (s *stubSender) failToken(token string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.errs[token] = err
}

// syncBuf is a goroutine-safe log sink.
type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuf) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

// fixture is a Service over a temp database, a fake clock and a recording sender, with two
// people: Alex (device A, the sender) and Sam (devices S1 and S2).
type fixture struct {
	t      *testing.T
	db     *sql.DB
	clk    *clock.Fake
	sender *stubSender
	logs   *syncBuf
	svc    *Service

	alexUser, samUser string
	ids               map[string]string // device name -> id
	priv              ed25519.PrivateKey
}

var t0 = time.Date(2026, 10, 24, 15, 0, 0, 0, time.UTC)

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
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{
		t: t, db: d, clk: clock.NewFake(t0), logs: &syncBuf{}, ids: map[string]string{}, priv: priv,
		sender:   &stubSender{Fake: push.NewFake(), errs: map[string]error{}},
		alexUser: uuid.NewString(), samUser: uuid.NewString(),
	}
	f.svc = f.newService(d)
	t.Cleanup(func() { f.svc.Close(context.WithoutCancel(t.Context())) })

	f.exec(`INSERT INTO users (id, display_name, created_at) VALUES (?, 'Alex', 0), (?, 'Sam', 0)`, f.alexUser, f.samUser)
	f.device("A", f.alexUser)
	f.device("S1", f.samUser)
	f.device("S2", f.samUser)
	return f
}

func (f *fixture) newService(d *sql.DB) *Service {
	return NewService(Options{
		DB: d, Sender: f.sender, Clock: f.clk,
		Logger: slog.New(slog.NewTextHandler(f.logs, &slog.HandlerOptions{Level: slog.LevelDebug})),
	})
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

// device inserts a device for user with FCM token "tok-<name>".
func (f *fixture) device(name, user string) string {
	f.t.Helper()
	id := uuid.NewString()
	tok := make([]byte, 32)
	_, _ = rand.Read(tok)
	f.exec(`INSERT INTO devices (id, user_id, name, token_hash, fcm_token, created_at) VALUES (?, ?, ?, ?, ?, 0)`,
		id, user, name, tok, "tok-"+name)
	f.ids[name] = id
	return id
}

func (f *fixture) revoke(name string) {
	f.t.Helper()
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids[name])
}

func (f *fixture) caller(name string) Caller {
	user := f.samUser
	if name == "A" {
		user = f.alexUser
	}
	return Caller{DeviceID: f.ids[name], UserID: user}
}

// pageSpec describes a request; the zero value is a normal page from A to S1 and S2.
type pageSpec struct {
	id, session string
	level       string
	to          string   // user id; default Sam
	keys        []string // device ids; default S1 and S2
	from        string   // signer and from_device; default A
	mutate      func(payload map[string]any)
}

func (f *fixture) request(s pageSpec) (CreateRequest, Caller) {
	f.t.Helper()
	if s.id == "" {
		s.id = uuid.NewString()
	}
	if s.session == "" {
		s.session = uuid.NewString()
	}
	if s.level == "" {
		s.level = LevelNormal
	}
	if s.to == "" {
		s.to = f.samUser
	}
	if s.keys == nil {
		s.keys = []string{f.ids["S1"], f.ids["S2"]}
	}
	if s.from == "" {
		s.from = f.ids["A"]
	}
	keys := map[string]string{}
	for _, k := range s.keys {
		keys[k] = wire.B64u.EncodeToString(make([]byte, WrappedKeyBytes))
	}
	payload := map[string]any{
		"format": 1, "page_id": s.id, "session_id": s.session, "from_device": s.from,
		"to_user": s.to, "level": s.level, "created_at": f.clk.Now().UnixMilli(), "keys": keys,
	}
	if s.mutate != nil {
		s.mutate(payload)
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		f.t.Fatal(err)
	}
	obj := signed.Sign(f.priv, s.from, signed.ContextEnvelope, raw)
	return CreateRequest{ID: s.id, To: s.to, Level: s.level, SessionID: s.session, Envelope: obj.Encode()}, f.caller("A")
}

// create makes a default page and fails the test if it is not accepted as ringing.
func (f *fixture) create(s pageSpec) string {
	f.t.Helper()
	req, caller := f.request(s)
	res, err := f.svc.Create(f.t.Context(), caller, req)
	if err != nil || !res.Created || res.State != StateRinging {
		f.t.Fatalf("Create = %+v, %v", res, err)
	}
	f.svc.Wait()
	f.sender.Clear()
	return req.ID
}

// sent is a pushed message in a comparable form.
func sent(r push.Recorded) string {
	var detail string
	switch r.Data["type"] {
	case "page_cancel":
		detail = ":" + r.Data["reason"]
	case "page_state":
		detail = ":" + r.Data["state"]
	}
	return fmt.Sprintf("%s%s -> %s [%s %ds]", r.Data["type"], detail, r.Token, r.Priority, r.TTLSecs)
}

// pushes waits for background sends, returns them sorted and clears the recorder.
func (f *fixture) pushes() []string {
	f.t.Helper()
	f.svc.Wait()
	var out []string
	for _, r := range f.sender.Messages() {
		out = append(out, sent(r))
	}
	f.sender.Clear()
	sort.Strings(out)
	return out
}

func (f *fixture) wantPushes(want ...string) {
	f.t.Helper()
	got := f.pushes()
	sort.Strings(want)
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		f.t.Fatalf("pushes:\n got %q\nwant %q", got, want)
	}
}

func (f *fixture) state(id string) string {
	f.t.Helper()
	var s string
	err := f.db.QueryRowContext(f.t.Context(), `SELECT state FROM active_pages WHERE id = ?`, id).Scan(&s)
	if errors.Is(err, sql.ErrNoRows) {
		if f.count(`SELECT COUNT(*) FROM page_tombstones WHERE id = ?`, id) == 1 {
			return StateEnded
		}
		return "gone"
	}
	if err != nil {
		f.t.Fatal(err)
	}
	return s
}
