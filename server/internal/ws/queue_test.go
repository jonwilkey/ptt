package ws

import (
	"fmt"
	"testing"

	"github.com/coder/websocket"
)

func newQueueConn() *conn {
	return &conn{wake: make(chan struct{}, 1), writerDone: make(chan struct{})}
}

func (c *conn) snapshot() (queued []string, closing bool, code websocket.StatusCode) {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	for _, o := range c.queue {
		queued = append(queued, string(o.data))
	}
	return queued, c.closing, c.code
}

func TestQueueKeepsOneOrderAndNeverDropsControl(t *testing.T) {
	c := newQueueConn()
	c.send(errorMsg{Type: "error", Code: "a"})
	for i := range 40 {
		if ok := c.sendFrame([]byte(fmt.Sprintf("f%02d", i))); ok != (i < maxQueuedFrames) {
			t.Fatalf("frame %d accepted = %v", i, ok)
		}
	}
	// 25 frames are queued; control messages still go in, in order, behind them.
	c.send(errorMsg{Type: "error", Code: "b"})
	q, closing, _ := c.snapshot()
	if closing {
		t.Fatal("closed")
	}
	if len(q) != 1+25+1 || q[0] != `{"type":"error","code":"a"}` || q[1] != "f00" || q[25] != "f24" || q[26] != `{"type":"error","code":"b"}` {
		t.Fatalf("queue = %q", q)
	}
}

func TestQueueOverflowClosesWith4408(t *testing.T) {
	c := newQueueConn()
	for i := range maxQueue {
		c.send(errorMsg{Type: "error", Code: fmt.Sprint(i)})
	}
	if _, closing, _ := c.snapshot(); closing {
		t.Fatal("closed at exactly 256 entries")
	}
	c.send(errorMsg{Type: "error", Code: "one too many"})
	q, closing, code := c.snapshot()
	if !closing || code != codeTimeout {
		t.Fatalf("closing=%v code=%d, want a 4408 close", closing, code)
	}
	if len(q) != 0 {
		t.Errorf("queue not discarded: %d entries", len(q))
	}
	// Nothing is accepted afterwards.
	if c.enqueue(out{data: []byte("x")}) {
		t.Error("enqueue after close succeeded")
	}
}

func TestQueueCloseAfterFlushKeepsWhatIsQueued(t *testing.T) {
	c := newQueueConn()
	c.send(sessionEndMsg{Type: "session_end", Reason: "idle"})
	c.closeAfterFlush(codeNormal)
	c.send(errorMsg{Type: "error", Code: "late"}) // refused: closing
	q, closing, code := c.snapshot()
	if !closing || code != codeNormal || len(q) != 1 {
		t.Fatalf("queue=%q closing=%v code=%d", q, closing, code)
	}
	// The first close wins.
	c.closeNow(codeRevoked)
	if _, _, code := c.snapshot(); code != codeNormal {
		t.Errorf("code = %d after a second close", code)
	}
}
