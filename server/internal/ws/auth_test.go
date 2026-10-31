package ws

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/jonwilkey/ptt/server/internal/auth"
)

func hello(token string) map[string]any {
	return map[string]any{"type": "hello", "protocol": 1, "token": token, "app_version": "0.1.0"}
}

func (f *fixture) auditRows() int {
	return f.count(`SELECT COUNT(*) FROM audit_log WHERE action = 'auth_failure'`)
}

// TestHelloCloseCodes is the close-code and audit table of protocol section 11.3.
func TestHelloCloseCodes(t *testing.T) {
	f := newFixture(t)
	unknown, _, _ := auth.NewToken()
	f.device("R", f.samUser)
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["R"])

	tests := []struct {
		name  string
		raw   string
		code  int
		audit int // auth_failure rows this case adds
	}{
		{"not json", `hello`, 4400, 0},
		{"array", `[]`, 4400, 0},
		{"first message is not hello", `{"type":"ping","t":1}`, 4400, 0},
		{"unknown type", `{"type":"nope"}`, 4400, 0},
		{"missing type", `{"protocol":1,"token":"x","app_version":"1"}`, 4400, 0},
		{"missing key", `{"type":"hello","protocol":1,"token":"x"}`, 4400, 0},
		{"extra key", `{"type":"hello","protocol":1,"token":"` + unknown + `","app_version":"1","x":1}`, 4400, 0},
		{"wrong case key", `{"type":"hello","Protocol":1,"token":"` + unknown + `","app_version":"1"}`, 4400, 0},
		{"duplicate key", `{"type":"hello","protocol":1,"protocol":1,"token":"` + unknown + `","app_version":"1"}`, 4400, 0},
		{"null value", `{"type":"hello","protocol":1,"token":null,"app_version":"1"}`, 4400, 0},
		{"protocol zero", `{"type":"hello","protocol":0,"token":"` + unknown + `","app_version":"1"}`, 4400, 0},
		{"protocol negative", `{"type":"hello","protocol":-1,"token":"` + unknown + `","app_version":"1"}`, 4400, 0},
		{"protocol fraction", `{"type":"hello","protocol":1.5,"token":"` + unknown + `","app_version":"1"}`, 4400, 0},
		{"protocol string", `{"type":"hello","protocol":"1","token":"` + unknown + `","app_version":"1"}`, 4400, 0},
		{"token number", `{"type":"hello","protocol":1,"token":5,"app_version":"1"}`, 4400, 0},
		{"app_version empty", `{"type":"hello","protocol":1,"token":"` + unknown + `","app_version":""}`, 4400, 0},
		{"app_version too long", `{"type":"hello","protocol":1,"token":"` + unknown + `","app_version":"` + strings.Repeat("v", 33) + `"}`, 4400, 0},
		{"app_version control char", `{"type":"hello","protocol":1,"token":"` + unknown + `","app_version":"a\u0001"}`, 4400, 0},
		{"short token", `{"type":"hello","protocol":1,"token":"abc","app_version":"1"}`, 4401, 0},
		{"padded token", `{"type":"hello","protocol":1,"token":"` + unknown + `=","app_version":"1"}`, 4401, 0},
		{"unknown token", `{"type":"hello","protocol":1,"token":"` + unknown + `","app_version":"1"}`, 4401, 1},
		{"revoked device", `{"type":"hello","protocol":1,"token":"` + f.tokens["R"] + `","app_version":"1"}`, 4401, 1},
		{"over 512 bytes before hello", `{"type":"hello","protocol":1,"token":"` + unknown + `","app_version":"1","pad":"` + strings.Repeat("p", 520) + `"}`, 4413, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// A new throttle window for every case, so each audit row is attributable.
			f.clk.Advance(auth.FailureAuditWindow)
			before := f.auditRows()
			c := f.dial()
			c.sendRaw([]byte(tt.raw))
			c.wantClose(tt.code, reasonOf(websocket.StatusCode(tt.code)))
			if got := f.auditRows() - before; got != tt.audit {
				t.Errorf("auth_failure rows added = %d, want %d", got, tt.audit)
			}
		})
	}
}

func TestBinaryBeforeHelloIsProtocolError(t *testing.T) {
	f := newFixture(t)
	c := f.dial()
	c.sendBinary([]byte{1, 2, 3})
	c.wantClose(4400, "protocol_error")
}

func TestHelloAuditIsThrottledLikeREST(t *testing.T) {
	f := newFixture(t)
	bad, _, _ := auth.NewToken()
	for range 5 {
		f.dial().sendRaw([]byte(`{"type":"hello","protocol":1,"token":"` + bad + `","app_version":"1"}`))
	}
	// Wait for every close to have been processed.
	deadline := time.Now().Add(recvTimeout)
	for f.auditRows() == 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	time.Sleep(50 * time.Millisecond)
	if n := f.auditRows(); n != 1 {
		t.Fatalf("auth_failure rows after 5 failed hellos in one window = %d, want 1", n)
	}
	var detail, actor *string
	if err := f.db.QueryRowContext(t.Context(), `SELECT detail, actor_device FROM audit_log WHERE action = 'auth_failure'`).Scan(&detail, &actor); err != nil {
		t.Fatal(err)
	}
	if actor != nil || (detail != nil && strings.Contains(*detail, bad)) {
		t.Errorf("audit row has an actor or the token: %v %v", actor, detail)
	}
}

func TestHelloSuccessAndNegotiation(t *testing.T) {
	f := newFixture(t)
	for _, proto := range []int{1, 2, 99} {
		c := f.dial()
		c.send(map[string]any{"type": "hello", "protocol": proto, "token": f.tokens["A"], "app_version": "9.9.9"})
		m := c.want("hello", "protocol", "1", "device_id", f.ids["A"], "server_time", strconv.FormatInt(t0.UnixMilli(), 10))
		if len(m) != 4 {
			t.Errorf("hello reply has keys %v, want exactly type, protocol, device_id, server_time", m)
		}
		_ = c.ws.CloseNow()
	}
	var ver string
	if err := f.db.QueryRowContext(t.Context(), `SELECT app_version FROM devices WHERE id = ?`, f.ids["A"]).Scan(&ver); err != nil || ver != "9.9.9" {
		t.Errorf("app_version stored = %q, %v", ver, err)
	}
	if f.count(`SELECT COUNT(*) FROM devices WHERE id = ? AND last_seen_at IS NOT NULL`, f.ids["A"]) != 1 {
		t.Error("hello did not refresh last_seen_at")
	}
}

func TestSecondHelloIsProtocolError(t *testing.T) {
	f := newFixture(t)
	c := f.connect("A")
	c.send(hello(f.tokens["A"]))
	c.wantClose(4400, "protocol_error")
}

func TestHelloDeadline(t *testing.T) {
	f := newFixture(t)
	c := f.dial()
	f.clk.BlockUntil(1) // this socket's watcher
	f.clk.Advance(helloDeadline - time.Millisecond)
	c.sendRaw([]byte(`{"type":"hello","protocol":1,"token":"` + f.tokens["A"] + `","app_version":"1"}`))
	c.want("hello")
	c2 := f.dial()
	f.clk.BlockUntil(2) // the first socket's watcher is still waiting too
	f.clk.Advance(helloDeadline)
	c2.wantClose(4408, "timeout")
}

func TestJoinDeadlineAndSilence(t *testing.T) {
	f := newFixture(t)
	c := f.connect("A")
	f.clk.BlockUntil(1)
	// Pings keep the socket alive, but 30 s after hello without a join it is closed.
	f.clk.Advance(20 * time.Second)
	c.ping()
	f.clk.Advance(10 * time.Second)
	c.wantClose(4408, "timeout")

	// After a join only silence matters.
	p := f.page()
	c2, _ := f.joined("A", p)
	f.clk.Advance(20 * time.Second)
	c2.ping()
	f.clk.Advance(29 * time.Second)
	c2.ping()
	f.clk.Advance(31 * time.Second)
	c2.wantClose(4408, "timeout")
}
