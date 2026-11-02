package ws

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// connectProto dials and completes hello announcing protocol announced; the reply must carry
// replied.
func (f *fixture) connectProto(name string, announced, replied int) *client {
	f.t.Helper()
	c := f.dial()
	c.name = name
	c.send(map[string]any{"type": "hello", "protocol": announced, "token": f.tokens[name], "app_version": "0.0.1"})
	m := c.recv()
	if m["type"] != "hello" || m["protocol"] != float64(replied) {
		f.t.Fatalf("hello reply = %v, want protocol %d", m, replied)
	}
	return c
}

// joinedProto is joined for a socket that announced protocol 2.
func (f *fixture) joinedProto(name string, p page, protocol int) (*client, map[string]any) {
	f.t.Helper()
	c := f.connectProto(name, protocol, protocol)
	c.send(map[string]any{"type": "join", "session_id": p.session})
	return c, c.recv()
}

// senderKeyFor is A's signed sender key for p, with the wrapped key filled with fill.
func (f *fixture) senderKeyFor(p page, fill byte, mutate func(map[string]any)) signed.Wire {
	f.t.Helper()
	m := map[string]any{
		"format": 1, "page_id": p.id, "session_id": p.session, "from_device": f.ids["A"],
		"key": wire.B64u.EncodeToString(bytes.Repeat([]byte{fill}, pages.WrappedKeyBytes)),
	}
	if mutate != nil {
		mutate(m)
	}
	payload, err := json.Marshal(m)
	if err != nil {
		f.t.Fatal(err)
	}
	return signed.Sign(f.priv, f.ids["A"], "ptt-sender-key-v1", payload).Encode()
}

func (f *fixture) storedSenderKey(p page) []byte {
	f.t.Helper()
	var b []byte
	if err := f.db.QueryRowContext(f.t.Context(), `SELECT sender_key FROM active_pages WHERE id = ?`, p.id).Scan(&b); err != nil {
		f.t.Fatal(err)
	}
	return b
}

func TestFloorRequestClipField(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	// Required in protocol 2, and a boolean.
	for _, bad := range []string{
		`{"type":"floor_request","transmission_id":"` + uuid.NewString() + `"}`,
		`{"type":"floor_request","transmission_id":"` + uuid.NewString() + `","clip":"true"}`,
		`{"type":"floor_request","transmission_id":"` + uuid.NewString() + `","clip":1}`,
		`{"type":"floor_request","transmission_id":"` + uuid.NewString() + `","clip":null}`,
		`{"type":"floor_request","transmission_id":"` + uuid.NewString() + `","clip":true,"x":1}`,
	} {
		c, _ := f.joinedProto("A", p, 2)
		c.sendRaw([]byte(bad))
		c.wantClose(4400, "protocol_error")
	}

	a, _ := f.joinedProto("A", p, 2)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	s, _ := f.joinedProto("S1", p, 2)
	s.want("peer_joined")
	a.want("peer_joined")
	tx := uuid.NewString()
	a.send(map[string]any{"type": "floor_request", "transmission_id": tx, "clip": true})
	a.want("floor_granted", "transmission_id", tx)
	s.want("floor_granted", "transmission_id", tx)
	f.hub.mu.Lock()
	clip := f.hub.rooms[p.id].floor.clip
	f.hub.mu.Unlock()
	if !clip {
		t.Error("clip flag not stored on the transmission")
	}
	a.release(tx)
	a.want("floor_released")
	s.want("floor_released")
	tx2 := uuid.NewString()
	a.send(map[string]any{"type": "floor_request", "transmission_id": tx2, "clip": false})
	a.want("floor_granted", "transmission_id", tx2)
	f.hub.mu.Lock()
	clip = f.hub.rooms[p.id].floor.clip
	f.hub.mu.Unlock()
	if clip {
		t.Error("clip flag leaked from the previous transmission")
	}
}

func TestFloorRequestClipForbiddenInProtocol1(t *testing.T) {
	f := newFixture(t)
	p, a, _ := f.both()
	_ = p
	a.sendRaw([]byte(`{"type":"floor_request","transmission_id":"` + uuid.NewString() + `","clip":false}`))
	a.wantClose(4400, "protocol_error")
}

func TestProtocol2MessagesOnProtocol1SocketAreProtocolErrors(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	tx := uuid.NewString()
	for _, raw := range []string{
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":0}`,
		`{"type":"resume","transmission_id":"` + tx + `"}`,
		`{"type":"sender_key","key":` + mustJSON(t, f.senderKeyFor(p, 1, nil)) + `}`,
	} {
		c, _ := f.joined("A", p)
		c.sendRaw([]byte(raw))
		c.wantClose(4400, "protocol_error")
	}
}

func TestHeardAndResumeAreValidated(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	tx := uuid.NewString()
	// Before join: out-of-state.
	for _, raw := range []string{
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":0}`,
		`{"type":"resume","transmission_id":"` + tx + `"}`,
		`{"type":"sender_key","key":` + mustJSON(t, f.senderKeyFor(p, 1, nil)) + `}`,
	} {
		c := f.connectProto("A", 2, 2)
		c.sendRaw([]byte(raw))
		c.wantClose(4400, "protocol_error")
	}

	c, _ := f.joinedProto("A", p, 2)
	for _, ok := range []string{
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":0}`,
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":4095}`,
	} {
		c.sendRaw([]byte(ok))
		c.ping() // a pong and no close: accepted, no reply
	}
	c.sendRaw([]byte(`{"type":"resume","transmission_id":"` + tx + `"}`))
	c.want("error", "code", "bad_transmission_id")
	c.ping()
	for _, bad := range []string{
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":4096}`,
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":-1}`,
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":1.0}`,
		`{"type":"heard","transmission_id":"` + tx + `","last_seq":"1"}`,
		`{"type":"heard","transmission_id":"` + tx + `"}`,
		`{"type":"heard","last_seq":1}`,
		`{"type":"heard","transmission_id":"` + strings.ToUpper(tx) + `","last_seq":1}`,
		`{"type":"heard","transmission_id":"nope","last_seq":1}`,
		`{"type":"resume","transmission_id":"nope"}`,
		`{"type":"resume"}`,
		`{"type":"resume","transmission_id":"` + tx + `","last_seq":1}`,
	} {
		c, _ := f.joinedProto("A", p, 2)
		c.sendRaw([]byte(bad))
		c.wantClose(4400, "protocol_error")
	}
}

func mustJSON(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestSenderKeyStoredOnceAndRepeatsIgnored(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joinedProto("A", p, 2)
	if f.storedSenderKey(p) != nil {
		t.Fatal("sender key stored before any was sent")
	}
	first := f.senderKeyFor(p, 1, nil)
	a.send(map[string]any{"type": "sender_key", "key": first})
	a.ping()
	want, _ := json.Marshal(first)
	if got := f.storedSenderKey(p); !bytes.Equal(got, want) {
		t.Fatalf("stored %s, want %s", got, want)
	}

	// Sent again after a rejoin, and a different one: the first stays.
	a, _ = f.joinedProto("A", p, 2)
	a.send(map[string]any{"type": "sender_key", "key": first})
	a.send(map[string]any{"type": "sender_key", "key": f.senderKeyFor(p, 2, nil)})
	a.ping()
	if got := f.storedSenderKey(p); !bytes.Equal(got, want) {
		t.Fatalf("a repeat replaced the stored key: %s", got)
	}
}

func TestSenderKeyRefusals(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	other := page{id: uuid.NewString(), session: uuid.NewString()}
	good := f.senderKeyFor(p, 1, nil)
	bad := map[string]signed.Wire{
		"other page":      f.senderKeyFor(p, 1, func(m map[string]any) { m["page_id"] = other.id }),
		"other session":   f.senderKeyFor(p, 1, func(m map[string]any) { m["session_id"] = other.session }),
		"other device":    f.senderKeyFor(p, 1, func(m map[string]any) { m["from_device"] = f.ids["S1"] }),
		"format 2":        f.senderKeyFor(p, 1, func(m map[string]any) { m["format"] = 2 }),
		"extra field":     f.senderKeyFor(p, 1, func(m map[string]any) { m["extra"] = 1 }),
		"missing field":   f.senderKeyFor(p, 1, func(m map[string]any) { delete(m, "key") }),
		"short key":       f.senderKeyFor(p, 1, func(m map[string]any) { m["key"] = wire.B64u.EncodeToString(make([]byte, 79)) }),
		"padded key":      f.senderKeyFor(p, 1, func(m map[string]any) { m["key"] = wire.B64u.EncodeToString(make([]byte, 80)) + "=" }),
		"upper-case id":   f.senderKeyFor(p, 1, func(m map[string]any) { m["page_id"] = strings.ToUpper(p.id) }),
		"signer is not A": {Payload: good.Payload, Signer: f.ids["S1"], Sig: good.Sig},
		"bad signer":      {Payload: good.Payload, Signer: "nope", Sig: good.Sig},
		"short sig":       {Payload: good.Payload, Signer: good.Signer, Sig: "AAAA"},
		"empty payload":   {Payload: "", Signer: good.Signer, Sig: good.Sig},
	}
	for name, key := range bad {
		c, _ := f.joinedProto("A", p, 2)
		c.send(map[string]any{"type": "sender_key", "key": key})
		c.wantClose(4400, "protocol_error")
		if f.storedSenderKey(p) != nil {
			t.Errorf("%s: a refused sender key was stored", name)
		}
	}
	// The message itself is as strict as every client message.
	for name, raw := range map[string]string{
		"extra member":     `{"type":"sender_key","key":` + mustJSON(t, good) + `,"x":1}`,
		"extra key member": `{"type":"sender_key","key":{"payload":"` + good.Payload + `","signer":"` + good.Signer + `","sig":"` + good.Sig + `","x":1}}`,
		"key not object":   `{"type":"sender_key","key":"x"}`,
		"null member":      `{"type":"sender_key","key":{"payload":null,"signer":"` + good.Signer + `","sig":"` + good.Sig + `"}}`,
	} {
		c, _ := f.joinedProto("A", p, 2)
		c.sendRaw([]byte(raw))
		c.wantClose(4400, "protocol_error")
		if f.storedSenderKey(p) != nil {
			t.Errorf("%s: a refused sender key was stored", name)
		}
	}

	// The answering device never sends one, even naming itself.
	a, _ := f.joinedProto("A", p, 2)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	s, _ := f.joinedProto("S1", p, 2)
	s.want("peer_joined")
	s.send(map[string]any{"type": "sender_key", "key": good})
	s.wantClose(4400, "protocol_error")
	if f.storedSenderKey(p) != nil {
		t.Error("the answerer's sender key was stored")
	}
}

func TestSenderKeyGoneWithThePage(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joinedProto("A", p, 2)
	a.send(map[string]any{"type": "sender_key", "key": f.senderKeyFor(p, 1, nil)})
	a.ping()
	if f.storedSenderKey(p) == nil {
		t.Fatal("sender key not stored")
	}
	if err := f.svc.Cancel(t.Context(), f.ids["A"], p.id); err != nil {
		t.Fatal(err)
	}
	a.want("session_end")
	if n := f.count(`SELECT COUNT(*) FROM active_pages WHERE id = ?`, p.id); n != 0 {
		t.Fatalf("page row still there: %d", n)
	}
	if n := f.count(`SELECT COUNT(*) FROM page_tombstones WHERE id = ?`, p.id); n != 1 {
		t.Fatalf("tombstones = %d, want 1", n)
	}
}
