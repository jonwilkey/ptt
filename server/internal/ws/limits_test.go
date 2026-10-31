package ws

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
)

// validFrame is a well-formed frame with a 60-byte packet (the header is all the server reads).
func validFrame(seq uint32) []byte {
	h := audioframe.Header{Type: audioframe.TypeLive, Seq: seq}
	h.TransmissionID[0] = 1
	return append(h.AppendHeader(nil), make([]byte, 60+audioframe.TagSize)...)
}

func TestPingPong(t *testing.T) {
	f := newFixture(t)
	c := f.connect("A")
	c.send(map[string]any{"type": "ping", "t": 9007199254740991})
	c.want("pong", "t", "9007199254740991", "server_time", "1792854000000")
	for _, bad := range []string{
		`{"type":"ping"}`, `{"type":"ping","t":-1}`, `{"type":"ping","t":1.5}`, `{"type":"ping","t":9007199254740992}`,
		`{"type":"ping","t":"1"}`, `{"type":"ping","t":1,"x":1}`,
	} {
		c := f.connect("A")
		c.sendRaw([]byte(bad))
		c.wantClose(4400, "protocol_error")
	}
}

func TestSizeLimits(t *testing.T) {
	f := newFixture(t)
	pad := func(n int) string {
		return `{"type":"ping","t":1,"p":"` + strings.Repeat("x", n) + `"}`
	}
	// 1025 bytes of text, valid or not, is over the limit.
	c := f.connect("A")
	c.sendRaw([]byte(pad(1100)))
	c.wantClose(4413, "too_large")

	// Exactly at the limit is a message like any other (here: unknown extra key, so 4400).
	raw := `{"type":"ping","t":1,"p":"` + strings.Repeat("x", 1024-len(`{"type":"ping","t":1,"p":""}`)) + `"}`
	if len(raw) != 1024 {
		t.Fatalf("test bug: %d", len(raw))
	}
	c = f.connect("A")
	c.sendRaw([]byte(raw))
	c.wantClose(4400, "protocol_error")

	// Binary: 1313 bytes is over, 1312 is accepted (and, unjoined, dropped).
	c = f.connect("A")
	c.sendBinary(make([]byte, audioframe.MaxFrameSize))
	c.ping()
	c.sendBinary(make([]byte, audioframe.MaxFrameSize+1))
	c.wantClose(4413, "too_large")
}

func TestTextRateLimit(t *testing.T) {
	f := newFixture(t)
	c := f.connect("A") // hello was the first message
	// Burst 40, no refill on the frozen clock: 39 more pings pass, the 40th after is refused.
	for range 39 {
		c.send(map[string]any{"type": "ping", "t": 1})
	}
	for range 39 {
		c.want("pong")
	}
	c.send(map[string]any{"type": "ping", "t": 1})
	c.wantClose(4429, "rate_limited")

	// Sustained: 20 per second refill.
	c = f.connect("A")
	for range 39 {
		c.send(map[string]any{"type": "ping", "t": 1})
	}
	for range 39 {
		c.want("pong")
	}
	f.clk.Advance(time.Second) // 20 tokens
	for range 20 {
		c.send(map[string]any{"type": "ping", "t": 1})
	}
	for range 20 {
		c.want("pong")
	}
}

func TestBinaryRateLimitAndFrameValidation(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	// A malformed frame on a joined socket is a protocol error; a valid one is accepted (14c
	// relays it; for now dropped).
	a.sendBinary(validFrame(0))
	a.ping()
	b, _ := f.joined("A", p)
	b.sendBinary([]byte{9, 9, 9})
	b.wantClose(4400, "protocol_error")

	c, _ := f.joined("A", p)
	for i := range 200 { // burst 200
		c.sendBinary(validFrame(uint32(i)))
	}
	c.ping()
	for range 201 {
		c.sendBinary(validFrame(1))
	}
	c.wantClose(4429, "rate_limited")
}

func TestUnauthenticatedSocketCap(t *testing.T) {
	f := newFixture(t)
	var held []*client
	for range maxUnauth {
		held = append(held, f.dial())
	}
	url := "ws" + strings.TrimPrefix(f.srv.URL, "http") + "/v1/ws"
	_, resp, err := websocket.Dial(t.Context(), url, nil)
	if resp != nil && resp.Body != nil {
		defer func() { _ = resp.Body.Close() }()
	}
	if err == nil || resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("65th unauthenticated socket: resp=%v err=%v, want 503", resp, err)
	}
	// Authenticating frees a slot.
	held[0].send(hello(f.tokens["A"]))
	held[0].want("hello")
	_ = f.dial().ws.CloseNow()
}
