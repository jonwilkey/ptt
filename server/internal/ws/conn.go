package ws

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

// out is one entry of a socket's ordered outbound queue.
type out struct {
	data   []byte
	binary bool
	// frame marks a relayed audio frame, the only kind of entry that may be dropped.
	frame bool
}

// bucket is a token bucket: rate tokens per second, up to burst.
type bucket struct {
	rate, burst float64
	tokens      float64
	last        time.Time
}

func newBucket(rate, burst float64) *bucket { return &bucket{rate: rate, burst: burst, tokens: burst} }

func (b *bucket) allow(now time.Time) bool {
	if !b.last.IsZero() {
		b.tokens = min(b.burst, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// conn is one WebSocket. Three goroutines serve it: the HTTP handler reads and acts
// (readLoop), writeLoop drains the queue, watch enforces the timeouts.
type conn struct {
	h      *Hub
	sock   *websocket.Conn
	ctx    context.Context
	cancel context.CancelFunc

	// The outbound queue. qmu is a leaf lock: the hub may take it while holding h.mu.
	qmu        sync.Mutex
	queue      []out
	frames     int // frames in queue
	closing    bool
	flush      bool // write what is queued before closing
	code       websocket.StatusCode
	wake       chan struct{}
	writerDone chan struct{}

	// lastMsg is the clock time (unix ns) of the latest message of any kind from the client.
	lastMsg atomic.Int64
	text    *bucket // owned by readLoop
	bin     *bucket

	// Guarded by h.mu.
	connectedAt time.Time
	counted     bool // still counted in h.unauth
	authed      bool
	deviceID    string
	protocol    int64
	helloAt     time.Time
	everJoined  bool
	sess        *session
	joined      bool
	role        role
}

func newConn(h *Hub, sock *websocket.Conn) *conn {
	ctx, cancel := context.WithCancel(context.Background())
	now := h.clk.Now()
	c := &conn{
		h: h, sock: sock, ctx: ctx, cancel: cancel,
		wake: make(chan struct{}, 1), writerDone: make(chan struct{}),
		text: newBucket(textRate, textBurst), bin: newBucket(binaryRate, binaryBurst),
		connectedAt: now, counted: true,
	}
	c.lastMsg.Store(now.UnixNano())
	return c
}

// ---- outbound queue --------------------------------------------------------------------

// send queues a control message. Control messages are never dropped: past maxQueue entries the
// socket is closed 4408 instead (a peer that is not reading, protocol section 11.7).
func (c *conn) send(v any) { c.enqueue(out{data: encode(v)}) }

// sendFrame queues a relayed audio frame byte for byte (task 14c). It reports false, and drops
// the frame, when maxQueuedFrames frames are already waiting.
func (c *conn) sendFrame(frame []byte) bool {
	return c.enqueue(out{data: frame, binary: true, frame: true})
}

func (c *conn) enqueue(o out) bool {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	if c.closing {
		return false
	}
	if o.frame && c.frames >= maxQueuedFrames {
		return false
	}
	if len(c.queue) >= maxQueue {
		c.closeLocked(codeTimeout, false)
		return false
	}
	c.queue = append(c.queue, o)
	if o.frame {
		c.frames++
	}
	c.signal()
	return true
}

func (c *conn) signal() {
	select {
	case c.wake <- struct{}{}:
	default:
	}
}

// closeNow drops whatever is queued and closes with code. The first close wins.
func (c *conn) closeNow(code websocket.StatusCode) {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	c.closeLocked(code, false)
}

// closeAfterFlush writes everything queued (a session_end, say) and then closes with code.
func (c *conn) closeAfterFlush(code websocket.StatusCode) {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	c.closeLocked(code, true)
}

func (c *conn) closeLocked(code websocket.StatusCode, flush bool) {
	if c.closing {
		return
	}
	c.closing, c.flush, c.code = true, flush, code
	if !flush {
		c.queue, c.frames = nil, 0
	}
	c.signal()
}

func (c *conn) isClosing() bool {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	return c.closing
}

// writeLoop sends the queue in order and then performs the close handshake. It is the only
// writer, so frames reach the peer in the order the server produced them.
func (c *conn) writeLoop() {
	defer close(c.writerDone)
	for {
		c.qmu.Lock()
		if len(c.queue) > 0 {
			o := c.queue[0]
			c.queue = c.queue[1:]
			if o.frame {
				c.frames--
			}
			c.qmu.Unlock()
			typ := websocket.MessageText
			if o.binary {
				typ = websocket.MessageBinary
			}
			ctx, cancel := context.WithTimeout(c.ctx, writeTimeout)
			err := c.sock.Write(ctx, typ, o.data)
			cancel()
			if err != nil {
				// A peer that cannot be written to is gone; the reader notices and cleans up.
				_ = c.sock.CloseNow()
				return
			}
			continue
		}
		if c.closing {
			code := c.code
			c.qmu.Unlock()
			// Close waits (up to 5 s) for the peer's close frame, which readLoop consumes.
			_ = c.sock.Close(code, reasonOf(code))
			return
		}
		c.qmu.Unlock()
		select {
		case <-c.wake:
		case <-c.ctx.Done():
			return
		}
	}
}

// ---- lifecycle -------------------------------------------------------------------------

func (c *conn) serve() {
	go c.writeLoop()
	go c.watch()
	c.readLoop()
	c.h.drop(c)
	c.closeNow(codeNormal) // a no-op if a close is already under way
	<-c.writerDone
	c.cancel()
	_ = c.sock.CloseNow()
}

// state returns whether hello and join have completed.
func (c *conn) state() (authed, joined bool) {
	c.h.mu.Lock()
	defer c.h.mu.Unlock()
	return c.authed, c.joined
}

// watch enforces the deadlines of protocol sections 11.1, 11.3 and 11.8 with the hub's clock:
// 10 s to hello, 30 s from hello to the first successful join, and 30 s without any message.
func (c *conn) watch() {
	h := c.h
	for {
		h.mu.Lock()
		deadline := time.Unix(0, c.lastMsg.Load()).Add(silenceTimeout)
		switch {
		case !c.authed:
			deadline = c.connectedAt.Add(helloDeadline)
		case !c.everJoined:
			deadline = minTime(deadline, c.helloAt.Add(joinDeadline))
		}
		h.mu.Unlock()
		wait := deadline.Sub(h.clk.Now())
		if wait <= 0 {
			c.closeNow(codeTimeout)
			return
		}
		elapsed := h.clk.After(wait)
		if !h.clk.Now().Before(deadline) {
			continue // the clock moved while the timer was being set
		}
		select {
		case <-elapsed:
		case <-c.ctx.Done():
			return
		}
	}
}

func minTime(a, b time.Time) time.Time {
	if b.Before(a) {
		return b
	}
	return a
}

// readLoop reads and acts on the client's frames until the socket closes.
func (c *conn) readLoop() {
	for !c.isClosing() {
		typ, r, err := c.sock.Reader(c.ctx)
		if err != nil {
			return
		}
		authed, _ := c.state()
		if typ == websocket.MessageBinary && !authed {
			c.closeNow(codeProtocol) // nothing but hello before hello
			return
		}
		limit := int64(maxTextBytes)
		switch {
		case typ == websocket.MessageBinary:
			limit = maxBinaryBytes
		case !authed:
			limit = maxPreAuthBytes
		}
		// Never read past the limit: an oversized frame is refused, not buffered.
		buf, err := io.ReadAll(io.LimitReader(r, limit+1))
		if err != nil {
			return
		}
		if int64(len(buf)) > limit {
			c.closeNow(codeTooLarge)
			return
		}
		now := c.h.clk.Now()
		c.lastMsg.Store(now.UnixNano())
		b := c.text
		if typ == websocket.MessageBinary {
			b = c.bin
		}
		if !b.allow(now) {
			c.closeNow(codeRateLimit)
			return
		}
		if c.isClosing() {
			return
		}
		if typ == websocket.MessageBinary {
			c.onBinary(buf)
		} else {
			c.onText(buf)
		}
	}
}

// onText dispatches one control message by the connection's state (protocol sections 11.3 and
// 11.4): anything wrong for the state is 4400.
func (c *conn) onText(data []byte) {
	m, err := parseClient(data)
	if err != nil {
		c.closeNow(codeProtocol)
		return
	}
	authed, joined := c.state()
	switch {
	case !authed:
		if m.typ != typeHello {
			c.closeNow(codeProtocol)
			return
		}
		c.onHello(m)
	case m.typ == typePing:
		c.send(pongMsg{Type: "pong", T: m.t, ServerTime: c.h.clk.Now().UnixMilli()})
	case m.typ == typeJoin && !joined:
		c.onJoin(m.sessionID)
	case joined && m.typ == typeFloorRequest:
		c.onFloorRequest(m.transmissionID)
	case joined && m.typ == typeFloorRelease:
		c.onFloorRelease(m.transmissionID)
	case joined && m.typ == typeSessionEnd:
		c.onSessionEnd()
	default:
		// hello again, a second join, or a message that needs a joined socket on an unjoined one.
		c.closeNow(codeProtocol)
	}
}

// onBinary handles an audio frame from an authenticated socket.
func (c *conn) onBinary(frame []byte) {
	_, joined := c.state()
	if !joined {
		return // dropped silently (protocol section 11.7)
	}
	hdr, _, err := audioframe.Parse(frame)
	if err != nil {
		c.closeNow(codeProtocol)
		return
	}
	c.h.relayFrame(c, hdr, frame)
}

// ---- hello -----------------------------------------------------------------------------

// onHello authenticates (protocol section 11.3).
func (c *conn) onHello(m clientMsg) {
	h := c.h
	ctx, cancel := context.WithTimeout(c.ctx, dbTimeout)
	defer cancel()
	dev, err := h.auth.Authenticate(ctx, m.token)
	switch {
	case errors.Is(err, auth.ErrMalformed), errors.Is(err, auth.ErrUnauthorized):
		c.closeNow(codeUnauth) // one code for malformed, unknown and revoked; no oracle
		return
	case err != nil:
		h.logger.LogAttrs(ctx, slog.LevelError, "websocket hello", slog.String("error", err.Error()))
		c.closeNow(codeServerErr)
		return
	}

	now := h.clk.Now()
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		c.closeNow(codeRestart)
		return
	}
	if c.isClosing() { // timed out (or was closed) while authenticating: replace nothing
		h.mu.Unlock()
		return
	}
	// One socket per device: the newer replaces the older at once, so the peer hears
	// peer_left before anything the new socket does (section 11.1).
	if old := h.socks[dev.ID]; old != nil && old != c {
		old.closeNow(codeReplaced)
		h.detachLocked(old)
	}
	h.socks[dev.ID] = c
	c.authed, c.deviceID, c.helloAt = true, dev.ID, now
	c.protocol = min(m.protocol, ProtocolVersion)
	if c.counted {
		c.counted = false
		h.unauth--
	}
	c.send(helloMsg{Type: "hello", Protocol: c.protocol, DeviceID: dev.ID, ServerTime: now.UnixMilli()})
	h.mu.Unlock()

	// app_version is informational, stored as PUT /v1/devices/me would; a failure is not fatal.
	err = h.q.UpdateDeviceSelf(ctx, sqlcdb.UpdateDeviceSelfParams{
		AppVersion: sql.NullString{String: m.appVersion, Valid: true}, ID: dev.ID,
	})
	if err != nil && !errors.Is(err, context.Canceled) {
		h.logger.LogAttrs(ctx, slog.LevelWarn, "websocket: store app_version", slog.String("error", err.Error()))
	}
}
