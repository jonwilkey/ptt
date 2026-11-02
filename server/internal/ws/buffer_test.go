package ws

import (
	"testing"

	"github.com/google/uuid"
)

// big backs every large test frame: the buffer charges len(frame), so slices of one array
// stand for megabytes of audio without allocating them.
var big = make([]byte, sessionCapBytes)

func grantTx(b *txBuffer, state txState) *transmission {
	id := uuid.New()
	t := b.grant(id, id.String(), "a", "b", false)
	t.state = state
	return t
}

func holdN(t *testing.T, b *txBuffer, tx *transmission, size int) {
	t.Helper()
	if !b.hold(tx, big[:size]) {
		t.Fatalf("hold %d bytes refused", size)
	}
	tx.next++
}

func TestBufferGrantChargesTheOverheadAndStartsLive(t *testing.T) {
	st := newClipStore()
	b := newTxBuffer(st)
	tx := grantTx(b, txLive)
	if tx.heard != -1 || tx.sent != -1 || tx.last() != -1 || tx.state != txLive {
		t.Fatalf("new transmission = %+v", tx)
	}
	if b.charged != txOverhead || st.charged != txOverhead || st.bytes.Load() != txOverhead {
		t.Fatalf("charged %d/%d/%d, want %d", b.charged, st.charged, st.bytes.Load(), txOverhead)
	}
	if b.find(tx.id) != tx {
		t.Fatal("find does not return the granted transmission")
	}
}

func TestBufferHoldKeepsTheBytesAndHeardFreesThem(t *testing.T) {
	b := newTxBuffer(newClipStore())
	tx := grantTx(b, txLive)
	frames := [][]byte{{1, 1}, {2, 2, 2}, {3, 3, 3, 3}}
	for _, f := range frames {
		if !b.hold(tx, f) {
			t.Fatal("hold refused")
		}
		tx.next++
	}
	if b.charged != txOverhead+9 || tx.charged != txOverhead+9 {
		t.Fatalf("charged %d, want %d", b.charged, txOverhead+9)
	}
	if &tx.frames[1][0] != &frames[1][0] {
		t.Fatal("the frame was copied, not held as received")
	}
	b.hear(tx, 1)
	if tx.frames[0] != nil || tx.frames[1] != nil || tx.frames[2] == nil {
		t.Fatal("heard 1 must free frames 0 and 1 only")
	}
	if b.charged != txOverhead+4 || tx.heard != 1 || tx.sent != 1 {
		t.Fatalf("after heard 1: charged %d heard %d sent %d", b.charged, tx.heard, tx.sent)
	}
	b.hear(tx, 0) // lower: no change
	if tx.heard != 1 || b.charged != txOverhead+4 {
		t.Fatal("a lower heard changed the record")
	}
	tx.sent = 2
	b.hear(tx, 2)
	if tx.sent != 2 || b.charged != txOverhead {
		t.Fatalf("heard 2: sent %d charged %d", tx.sent, b.charged)
	}
}

func TestBufferEvictableIsClosedAndNotInReplay(t *testing.T) {
	for _, c := range []struct {
		name       string
		state      txState
		clipQueued bool
		sent, next int
		want       bool
	}{
		{"live", txLive, false, -1, 5, false},
		{"open", txOpen, false, -1, 5, false},
		{"closed, no clip yet", txClosed, false, -1, 5, true},
		{"closed, clip queued, frames pending", txClosed, true, 2, 5, false},
		{"closed, clip queued, last frame queued", txClosed, true, 4, 5, true},
	} {
		tx := &transmission{state: c.state, clipQueued: c.clipQueued, sent: c.sent, next: c.next}
		if got := tx.evictable(); got != c.want {
			t.Errorf("%s: evictable = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestBufferSessionCapIsExactly4MiB(t *testing.T) {
	st := newClipStore()
	b := newTxBuffer(st)
	old := grantTx(b, txClosed)
	holdN(t, b, old, 1000)
	cur := grantTx(b, txLive)
	// Exactly 4 MiB charged fits.
	holdN(t, b, cur, sessionCapBytes-2*txOverhead-1000)
	if b.charged != sessionCapBytes || len(b.txs) != 2 {
		t.Fatalf("charged %d with %d held, want exactly 4 MiB with 2", b.charged, len(b.txs))
	}
	// One byte more evicts the oldest evictable transmission, and counts it.
	holdN(t, b, cur, 1)
	if b.find(old.id) != nil || st.evictions.Load() != 1 {
		t.Fatal("the closed transmission was not evicted")
	}
	if b.charged != sessionCapBytes-txOverhead-1000+1 {
		t.Fatalf("charged %d after eviction", b.charged)
	}
}

func TestBufferSessionCapRefusesWhenNothingIsEvictable(t *testing.T) {
	b := newTxBuffer(newClipStore())
	open := grantTx(b, txOpen)
	holdN(t, b, open, 2_000_000)
	replaying := grantTx(b, txClosed)
	holdN(t, b, replaying, 1000)
	holdN(t, b, replaying, 1000)
	replaying.clipQueued, replaying.sent = true, 0
	live := grantTx(b, txLive)
	holdN(t, b, live, int(sessionCapBytes-b.charged))
	if b.hold(live, []byte{1}) {
		t.Fatal("a frame past 4 MiB was held with nothing evictable")
	}
	if len(b.txs) != 3 || b.charged != sessionCapBytes || len(live.frames) != 1 {
		t.Fatal("a refused hold changed the buffer")
	}
}

func TestBufferEvictsOldestGrantFirst(t *testing.T) {
	b := newTxBuffer(newClipStore())
	a1 := grantTx(b, txClosed)
	holdN(t, b, a1, 1_000_000)
	a2 := grantTx(b, txClosed)
	holdN(t, b, a2, 1_000_000)
	a3 := grantTx(b, txClosed)
	holdN(t, b, a3, 1_000_000)
	a1.clipQueued, a1.next = true, 1 // in replay: sent -1 < last 0
	cur := grantTx(b, txLive)
	holdN(t, b, cur, int(sessionCapBytes-b.charged))
	holdN(t, b, cur, 1)
	if b.find(a1.id) == nil || b.find(a2.id) != nil || b.find(a3.id) == nil {
		t.Fatal("want a2 (the oldest evictable) evicted, a1 (in replay) and a3 kept")
	}
}

func TestBufferAt64HeldTheNextGrantEvicts(t *testing.T) {
	st := newClipStore()
	b := newTxBuffer(st)
	first := grantTx(b, txClosed)
	first.clipQueued, first.next = true, 1 // in replay
	second := grantTx(b, txClosed)
	for len(b.txs) < sessionCapHeld {
		grantTx(b, txClosed)
	}
	if st.evictions.Load() != 0 {
		t.Fatal("evicted at 64 held")
	}
	grantTx(b, txLive)
	if len(b.txs) != sessionCapHeld || b.find(first.id) == nil || b.find(second.id) != nil {
		t.Fatalf("held %d; the 65th grant must evict the oldest evictable one", len(b.txs))
	}
	if st.evictions.Load() != 1 || b.charged != sessionCapHeld*txOverhead {
		t.Fatalf("evictions %d charged %d", st.evictions.Load(), b.charged)
	}
}

func TestBufferProcessCapIsExactly64MiBAndEvictsAcrossSessions(t *testing.T) {
	st := newClipStore()
	var bufs []*txBuffer
	var txs []*transmission
	for range processCapBytes / sessionCapBytes {
		b := newTxBuffer(st)
		tx := grantTx(b, txClosed)
		holdN(t, b, tx, sessionCapBytes-txOverhead)
		bufs, txs = append(bufs, b), append(txs, tx)
	}
	if st.charged != processCapBytes || st.evictions.Load() != 0 {
		t.Fatalf("process charged %d, evictions %d: exactly 64 MiB must fit", st.charged, st.evictions.Load())
	}
	// Make the third session's transmission the oldest grant of all.
	txs[2].order = 0
	b := newTxBuffer(st)
	cur := grantTx(b, txLive)
	holdN(t, b, cur, 1)
	if bufs[2].find(txs[2].id) != nil || bufs[0].find(txs[0].id) == nil {
		t.Fatal("the process cap must evict the oldest grant across sessions")
	}
	if st.evictions.Load() != 1 || st.charged != processCapBytes-sessionCapBytes+txOverhead+1 {
		t.Fatalf("evictions %d charged %d", st.evictions.Load(), st.charged)
	}
}

func TestBufferUnbufferKeepsTheRecordAndChargesOnlyTheOverhead(t *testing.T) {
	b := newTxBuffer(newClipStore())
	tx := grantTx(b, txLive)
	holdN(t, b, tx, 5000)
	b.unbuffer(tx)
	if !tx.unbuffered || tx.frames != nil || tx.next != 1 || b.find(tx.id) != tx {
		t.Fatalf("unbuffered record = %+v", tx)
	}
	if b.charged != txOverhead || tx.charged != txOverhead {
		t.Fatalf("charged %d, want %d", b.charged, txOverhead)
	}
}

func TestBufferClearFreesEverything(t *testing.T) {
	st := newClipStore()
	b := newTxBuffer(st)
	for range 3 {
		tx := grantTx(b, txClosed)
		holdN(t, b, tx, 100)
	}
	other := newTxBuffer(st)
	holdN(t, other, grantTx(other, txLive), 7)
	b.clear()
	if len(b.txs) != 0 || b.charged != 0 {
		t.Fatal("clear left transmissions")
	}
	if _, ok := st.buffers[b]; ok {
		t.Fatal("a cleared buffer is still in the process store")
	}
	if st.charged != txOverhead+7 || st.bytes.Load() != txOverhead+7 {
		t.Fatalf("process charged %d, want only the other session's", st.charged)
	}
	other.clear()
	if st.bytes.Load() != 0 {
		t.Fatalf("buffer bytes gauge %d after every session ended", st.bytes.Load())
	}
}
