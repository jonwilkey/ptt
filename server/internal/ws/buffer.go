package ws

import (
	"sync/atomic"
	"time"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
)

// The server's transmission buffer of protocol section 13.3: every transmission granted on a
// protocol-2 socket, held in memory only, until its listener has heard it, it is evicted, or
// the session ends. This file is the data and its accounting (the caps and eviction); delivery
// to the listener, which needs the sockets, is in delivery.go. Everything here is guarded by the
// hub's mu, except the metric counters, which are atomics so the metrics scrape takes no lock.

// Bounds of protocol sections 13.3 and 13.10.
const (
	maxTxFrames      = 3000 // seq 0 to 2999
	txOverhead       = 512  // charged per held transmission besides its frame bytes
	sessionCapBytes  = 4 << 20
	sessionCapHeld   = 64
	processCapBytes  = 64 << 20
	resumeWindow     = 30 * time.Second
	clipSilenceLimit = 5 * time.Second // a clip transmission's silence allowance (section 13.9)
)

type txState uint8

const (
	txLive   txState = iota // the floor is held for it
	txOpen                  // the floor was released by the server; its talker may still send the rest
	txClosed                // complete: no more frames
)

// transmission is one protocol-2 transmission (the table of section 13.3).
type transmission struct {
	id       [audioframe.IDSize]byte
	idStr    string
	talker   string // device ids; the listener is the session's other participant
	listener string
	clip     bool
	state    txState
	// frames holds the frames as received, indexed by seq; a frame freed by heard is nil.
	frames [][]byte
	// next is how many frames were accepted (also counted, not held, while unbuffered).
	next  int
	heard int // highest heard last_seq, or -1
	sent  int // highest seq queued in order to the listener's current socket, or -1
	// gapped: live relay has stopped for good. unbuffered: the listener joined on protocol 1
	// while this was unfinished, so its frames are relayed as section 11.7 and never held.
	gapped, unbuffered bool
	// gapSent: listener_gap went to the talker. clipQueued: clip was queued to the listener's
	// current socket (cleared when that socket goes). clipped: a clip was ever queued (metric).
	gapSent, clipQueued, clipped bool
	charged                      int64
	order                        uint64 // grant order across the process
	// windowFrom starts the resume window (rule 4 of section 13.3) while open.
	windowFrom time.Time
	window     timer
	buf        *txBuffer
}

// last is the highest seq received, -1 for none: a closed transmission's final frame.
func (t *transmission) last() int { return t.next - 1 }

// inReplay is section 13.3's "in replay": its clip is queued to the listener's current socket
// and its last frame is not queued yet.
func (t *transmission) inReplay() bool { return t.clipQueued && t.sent < t.last() }

// evictable is the only kind of transmission a cap may remove.
func (t *transmission) evictable() bool { return t.state == txClosed && !t.inReplay() }

// txBuffer is one session's held transmissions, in grant order, both directions.
type txBuffer struct {
	store   *clipStore
	txs     []*transmission
	charged int64
}

// clipStore is the process-wide side of the buffer: the 64 MiB cap over every session, the
// grant order eviction follows across sessions, and the metrics of section 13.11.
type clipStore struct {
	buffers map[*txBuffer]struct{}
	charged int64
	grants  uint64

	transmissions, clipTransmissions, listenerGaps, resumes, evictions atomic.Uint64
	bytes                                                              atomic.Int64
}

func newClipStore() *clipStore { return &clipStore{buffers: map[*txBuffer]struct{}{}} }

func newTxBuffer(store *clipStore) *txBuffer {
	b := &txBuffer{store: store}
	store.buffers[b] = struct{}{}
	return b
}

func (b *txBuffer) find(id [audioframe.IDSize]byte) *transmission {
	for _, t := range b.txs {
		if t.id == id {
			return t
		}
	}
	return nil
}

// grant adds a live transmission, first evicting the session's oldest evictable one if it
// would be the 65th (section 13.3; at most four are unevictable, so there always is one).
func (b *txBuffer) grant(id [audioframe.IDSize]byte, idStr, talker, listener string, clip bool) *transmission {
	if len(b.txs) >= sessionCapHeld {
		if v := oldestEvictable(b.txs); v != nil {
			b.evict(v)
		}
	}
	b.store.grants++
	t := &transmission{
		id: id, idStr: idStr, talker: talker, listener: listener, clip: clip, state: txLive,
		heard: -1, sent: -1, order: b.store.grants, buf: b,
	}
	b.txs = append(b.txs, t)
	b.charge(txOverhead)
	t.charged = txOverhead
	return t
}

// hold keeps frame as t's next one (the caller checked seq == next and the 3000 bound, and
// counts it in next), evicting oldest first until it fits under the session and process caps. It reports false,
// holding nothing, when it cannot fit; the caller then closes t (section 13.3, Caps).
func (b *txBuffer) hold(t *transmission, frame []byte) bool {
	n := int64(len(frame))
	for b.charged+n > sessionCapBytes {
		v := oldestEvictable(b.txs)
		if v == nil {
			return false
		}
		b.evict(v)
	}
	for b.store.charged+n > processCapBytes {
		v := b.store.oldestEvictable()
		if v == nil {
			return false
		}
		v.buf.evict(v)
	}
	t.frames = append(t.frames, frame)
	t.charged += n
	b.charge(n)
	return true
}

// hear records a heard of k (already bounded by next-1) and frees the frames up to it.
func (b *txBuffer) hear(t *transmission, k int) {
	if k <= t.heard {
		return
	}
	for i := t.heard + 1; i <= k && i < len(t.frames); i++ {
		if f := t.frames[i]; f != nil {
			t.charged -= int64(len(f))
			b.charge(-int64(len(f)))
			t.frames[i] = nil
		}
	}
	t.heard = k
	t.sent = max(t.sent, k)
}

// unbuffer frees t's frames and keeps the rest of its record (section 13.3, Unbuffered).
func (b *txBuffer) unbuffer(t *transmission) {
	b.charge(-(t.charged - txOverhead))
	t.charged = txOverhead
	t.frames = nil
	t.unbuffered = true
}

func (b *txBuffer) evict(t *transmission) {
	b.remove(t)
	b.store.evictions.Add(1)
}

// remove drops t and everything it holds. Its id stays in the session's set of granted ids.
func (b *txBuffer) remove(t *transmission) {
	for i, x := range b.txs {
		if x == t {
			b.txs = append(b.txs[:i], b.txs[i+1:]...)
			break
		}
	}
	t.window.stop()
	b.charge(-t.charged)
	t.charged, t.frames = 0, nil
}

// clear drops every transmission (the session ended) and the buffer itself.
func (b *txBuffer) clear() {
	for len(b.txs) > 0 {
		b.remove(b.txs[0])
	}
	delete(b.store.buffers, b)
}

func (b *txBuffer) charge(n int64) {
	b.charged += n
	b.store.charged += n
	b.store.bytes.Store(b.store.charged)
}

func oldestEvictable(txs []*transmission) *transmission {
	for _, t := range txs { // grant order
		if t.evictable() {
			return t
		}
	}
	return nil
}

func (s *clipStore) oldestEvictable() *transmission {
	var best *transmission
	for b := range s.buffers {
		if t := oldestEvictable(b.txs); t != nil && (best == nil || t.order < best.order) {
			best = t
		}
	}
	return best
}

// ClipCounts are the clip-fallback metrics of protocol section 13.11.
type ClipCounts struct {
	Transmissions, ClipTransmissions, ListenerGaps, Resumes, Evictions uint64
	BufferBytes                                                        int64
}

// ClipCounts reads the clip-fallback metrics. Aggregates only, never labelled.
func (h *Hub) ClipCounts() ClipCounts {
	s := h.clips
	return ClipCounts{
		Transmissions: s.transmissions.Load(), ClipTransmissions: s.clipTransmissions.Load(),
		ListenerGaps: s.listenerGaps.Load(), Resumes: s.resumes.Load(), Evictions: s.evictions.Load(),
		BufferBytes: s.bytes.Load(),
	}
}
