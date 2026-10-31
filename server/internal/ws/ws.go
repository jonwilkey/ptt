// Package ws serves the live-talk WebSocket of docs/protocol.md section 11: authentication
// (hello), joining the session of a page, the session lifecycle with its timers, ping/pong,
// revocation and the per-socket ordered send queue.
//
// A Hub owns every socket. Its state (sockets by device, sessions by page) is guarded by one
// mutex, mu; a socket's outbound queue has its own, leaf mutex, so the hub may enqueue while it
// holds mu and nothing ever waits on a network write or the database under mu. The page
// lifecycle (internal/pages) and the hub reach each other through two small interfaces:
// pages.Notifier (page transitions into the hub, implemented by *Hub) and Ender (session ends
// out of the hub, implemented by *pages.Service), so the two packages do not import each other.
//
// Floor control and frame relay (protocol sections 11.6 and 11.7) live in floor.go; this
// file authenticates, joins, queues and ends.
//
// Privacy (protocol section 1): nothing here logs, labels a metric with, or audits a page id,
// session id or device id. The metrics are unlabelled gauges and frame counters.
package ws

import (
	"context"
	"database/sql"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/clock"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

// ProtocolVersion is the highest protocol this server speaks (protocol section 11.1).
const ProtocolVersion = 1

// Limits and timers of protocol sections 8 and 11.
const (
	helloDeadline  = 10 * time.Second // upgrade -> hello
	joinDeadline   = 30 * time.Second // hello -> first successful join
	silenceTimeout = 30 * time.Second // no message of any kind
	liveWindow     = 15 * time.Second // "live": joined and heard from within this long
	idleTimeout    = 2 * time.Minute  // no floor activity once both joined
	sweepInterval  = 5 * time.Second  // revoked_at sweep
	writeTimeout   = 10 * time.Second // one frame to a peer that does not read
	dbTimeout      = 5 * time.Second  // one lookup on behalf of a message

	// restartGrace is how long after startup a page the hub knows nothing about is presumed to
	// have a session about to be rebuilt by its participants rejoining (protocol section 11.5).
	restartGrace = idleTimeout
	// idleRetry is the wait before trying again to end an idle session whose end failed.
	idleRetry = 5 * time.Second

	notDeliveredAfter = 30 * time.Second // snapshot: undelivered this long after created_at

	maxPreAuthBytes = 512
	maxTextBytes    = 1024
	maxBinaryBytes  = audioframe.MaxFrameSize
	maxUnauth       = 64

	// maxQueue is the total entries (control messages and frames) a socket may have queued.
	maxQueue = 256
	// maxQueuedFrames is the frame cap of protocol section 11.7 (500 ms of audio). Frames are
	// enqueued by the relay (14c); control messages are never dropped.
	maxQueuedFrames = 25

	textRate, textBurst     = 20, 40
	binaryRate, binaryBurst = 100, 200
)

// Close codes (protocol section 11.1) with their fixed reason tokens.
const (
	codeNormal    = websocket.StatusNormalClosure
	codeProtocol  = websocket.StatusCode(4400)
	codeUnauth    = websocket.StatusCode(4401)
	codeRevoked   = websocket.StatusCode(4403)
	codeTimeout   = websocket.StatusCode(4408)
	codeReplaced  = websocket.StatusCode(4409)
	codeTooLarge  = websocket.StatusCode(4413)
	codeRateLimit = websocket.StatusCode(4429)
	codeServerErr = websocket.StatusCode(4500)
	codeRestart   = websocket.StatusCode(4503)
)

func reasonOf(c websocket.StatusCode) string {
	switch c {
	case codeNormal:
		return "normal"
	case codeProtocol:
		return "protocol_error"
	case codeUnauth:
		return "unauthorized"
	case codeRevoked:
		return "revoked"
	case codeTimeout:
		return "timeout"
	case codeReplaced:
		return "replaced"
	case codeTooLarge:
		return "too_large"
	case codeRateLimit:
		return "rate_limited"
	case codeRestart:
		return "server_restarting"
	default:
		return "server_error"
	}
}

// Ender ends sessions through the page lifecycle, which owns the database. *pages.Service
// implements it. A WebSocket session_end and REST cancel are the same Cancel call
// (protocol section 11.11).
type Ender interface {
	// Cancel is REST cancel / the answerer's End / a WebSocket session_end, by deviceID.
	Cancel(ctx context.Context, deviceID, pageID string) error
	// EndIdle ends an answered page whose session idled out.
	EndIdle(ctx context.Context, pageID string) error
	// EndRevoked ends an answered page because deviceID, a participant, was revoked.
	EndRevoked(ctx context.Context, deviceID, pageID string) error
}

// Options configures a Hub.
type Options struct {
	DB     *sql.DB
	Auth   *auth.Authenticator
	Ender  Ender
	Logger *slog.Logger
	// Clock drives every timer and deadline; defaults to the wall clock.
	Clock clock.Clock
}

// Hub is the set of live-talk sockets and sessions.
type Hub struct {
	q      *sqlcdb.Queries
	auth   *auth.Authenticator
	ender  Ender
	logger *slog.Logger
	clk    clock.Clock

	started time.Time       // when the hub was created: the restart grace runs from here
	ctx     context.Context // cancelled by Shutdown
	cancel  context.CancelFunc
	wg      sync.WaitGroup // connections and the sweep

	mu     sync.Mutex
	closed bool
	conns  map[*conn]struct{}  // every open socket, authenticated or not
	socks  map[string]*conn    // device id -> its authenticated socket
	rooms  map[string]*session // page id -> session
	unauth int                 // sockets that have not completed hello yet

	// relayed and dropped count audio frames from talkers: aggregate only, never labelled.
	relayed, dropped atomic.Uint64
}

// New returns a Hub. Call Start to run the revocation sweep and Shutdown at exit.
func New(o Options) *Hub {
	if o.Logger == nil {
		o.Logger = slog.New(slog.DiscardHandler)
	}
	if o.Clock == nil {
		o.Clock = clock.Real{}
	}
	if o.Auth == nil {
		o.Auth = auth.NewAuthenticator(o.DB, o.Logger, o.Clock.Now)
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Hub{
		q: sqlcdb.New(o.DB), auth: o.Auth, ender: o.Ender, logger: o.Logger, clk: o.Clock,
		ctx: ctx, cancel: cancel, started: o.Clock.Now(),
		conns: map[*conn]struct{}{}, socks: map[string]*conn{}, rooms: map[string]*session{},
	}
}

// SetEnder connects the page lifecycle (the hub and the Service depend on each other, so one
// must be built first). Call it before Start and before serving.
func (h *Hub) SetEnder(e Ender) { h.ender = e }

// Sockets is the number of open sockets, for the active-sockets gauge.
func (h *Hub) Sockets() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns)
}

// Sessions is the number of live talk sessions, for the active-sessions gauge: answered pages
// (not a self page) that have a session in memory, which exists while a participant is
// joined or the idle timer is running.
func (h *Hub) Sessions() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	n := 0
	for _, s := range h.rooms {
		if s.answerer != "" && s.answerer != s.sender {
			n++
		}
	}
	return n
}

// Start runs the revocation sweep until Shutdown.
func (h *Hub) Start() {
	h.wg.Add(1)
	go func() {
		defer h.wg.Done()
		h.sweepLoop()
	}()
}

// Shutdown stops accepting upgrades, closes every socket with 4503 (the clients reconnect and
// join again, protocol section 11.5) and waits for them until ctx ends.
func (h *Hub) Shutdown(ctx context.Context) {
	h.mu.Lock()
	h.closed = true
	for c := range h.conns {
		c.closeNow(codeRestart)
	}
	for _, s := range h.rooms {
		s.stopTimers()
	}
	h.mu.Unlock()
	// Stops the sweep and any pending call into the page lifecycle. The sockets' own contexts
	// are independent: cancelling them would cut the close handshake short.
	h.cancel()

	done := make(chan struct{})
	go func() { h.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
	}
}

// ServeHTTP upgrades GET /v1/ws. The per-IP upgrade rate limit is applied before it (in
// httpapi); this applies the cap on unauthenticated sockets. Credentials are never in the
// upgrade: the token arrives in the first frame (protocol section 11.1).
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.mu.Lock()
	if h.closed || h.unauth >= maxUnauth {
		h.mu.Unlock()
		w.Header().Set("Retry-After", "1")
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(`{"error":"unavailable"}` + "\n"))
		return
	}
	h.unauth++
	h.mu.Unlock()

	// The server's request deadlines suit the JSON API, not a socket that lives for minutes.
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Time{})
	_ = rc.SetWriteDeadline(time.Time{})

	sock, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		h.mu.Lock()
		h.unauth--
		h.mu.Unlock()
		return
	}
	// Frame sizes are enforced by serve itself (it must answer 4413, not the library's 1009);
	// it never reads past the limit.
	sock.SetReadLimit(-1)

	c := newConn(h, sock)
	h.mu.Lock()
	if h.closed {
		h.unauth--
		h.mu.Unlock()
		_ = sock.Close(codeRestart, reasonOf(codeRestart))
		return
	}
	h.conns[c] = struct{}{}
	h.wg.Add(1)
	h.mu.Unlock()
	defer h.wg.Done()
	c.serve()
}
