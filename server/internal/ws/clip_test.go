package ws

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/wire"
)

// clipVector is the contiguous transmission of the clip_fallback vectors: its id and its frames
// (seq 0 to 5), sealed once by the talker.
func clipVector(t *testing.T) (string, [][]byte) {
	t.Helper()
	raw, err := os.ReadFile("../../../testdata/protocol-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		ClipFallback struct {
			Transmissions []struct {
				TransmissionID string `json:"transmission_id"`
				Frames         []struct {
					Frame string `json:"frame"`
				} `json:"frames"`
			} `json:"transmissions"`
		} `json:"clip_fallback"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatal(err)
	}
	v := file.ClipFallback.Transmissions[0]
	var out [][]byte
	for _, f := range v.Frames {
		b, err := wire.B64u.DecodeString(f.Frame)
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, b)
	}
	return v.TransmissionID, out
}

// bothP2 is both with both participants on protocol 2.
func (f *fixture) bothP2() (page, *client, *client) {
	f.t.Helper()
	p := f.page()
	a, _ := f.joinedProto("A", p, 2)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	s, _ := f.joinedProto("S1", p, 2)
	s.want("peer_joined", "device_id", f.ids["A"])
	a.want("peer_joined", "device_id", f.ids["S1"])
	return p, a, s
}

func (c *client) requestClip(tx string, clip bool) {
	c.send(map[string]any{"type": "floor_request", "transmission_id": tx, "clip": clip})
}

func (c *client) wantFrame(want []byte) {
	c.f.t.Helper()
	if got := c.recvFrame(); !bytes.Equal(got, want) {
		c.f.t.Fatalf("%s: frame %x, want %x", c.name, got, want)
	}
}

// bufferBytes waits for the buffer gauge to read want: a socket's close is handled
// asynchronously.
func (f *fixture) bufferBytes(want int64) {
	f.t.Helper()
	waitFor(f.t, func() bool { return f.hub.ClipCounts().BufferBytes == want })
}

func TestListenerGapThenTheRestAsAClipOnRejoin(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	tx, fr := clipVector(t)
	a.requestClip(tx, false)
	a.want("floor_granted", "transmission_id", tx)
	s.want("floor_granted", "transmission_id", tx)
	a.sendBinary(fr[0])
	a.sendBinary(fr[1])
	s.wantFrame(fr[0])
	s.wantFrame(fr[1])

	_ = s.ws.CloseNow()
	a.want("peer_left", "device_id", f.ids["S1"])
	for _, b := range fr[2:] {
		a.sendBinary(b)
	}
	a.want("listener_gap", "transmission_id", tx)
	a.release(tx)
	a.want("floor_released", "reason", "released")
	a.quiet() // one listener_gap only

	s, _ = f.joinedProto("S1", p, 2)
	s.want("peer_joined", "device_id", f.ids["A"])
	// No heard came from the old socket: the whole transmission is replayed, as received.
	s.want("clip", "talker", f.ids["A"], "transmission_id", tx, "last_seq", "5")
	for _, b := range fr {
		s.wantFrame(b)
	}
	s.quiet()
	s.send(map[string]any{"type": "heard", "transmission_id": tx, "last_seq": 5})
	s.ping()
	f.bufferBytes(0)
	a.want("peer_joined", "device_id", f.ids["S1"])
	a.quiet() // heard is not forwarded
	if c := f.hub.ClipCounts(); c.ListenerGaps != 1 || c.ClipTransmissions != 1 || c.Transmissions != 1 {
		t.Errorf("counts = %+v", c)
	}
}

func TestPeerAwayGrantAndClipsAfterThePeerJoins(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	// Before the answer a protocol-2 request is still no_peer.
	a, _ := f.joinedProto("A", p, 2)
	a.requestClip(uuid.NewString(), false)
	a.want("error", "code", "no_peer")
	_ = a.ws.CloseNow()
	f.answer("S1", p)
	waitFor(t, func() bool { f.hub.mu.Lock(); defer f.hub.mu.Unlock(); return f.hub.rooms[p.id] == nil })

	s, _ := f.joinedProto("S1", p, 2)
	var txs []string
	for i := range 2 {
		tx := uuid.NewString()
		txs = append(txs, tx)
		s.requestClip(tx, false)
		s.want("floor_granted", "talker", f.ids["S1"], "transmission_id", tx)
		s.want("listener_gap", "transmission_id", tx)
		for seq := range 3 {
			s.sendBinary(frameFor(tx, uint32(seq), byte(10*i+seq)))
		}
		s.release(tx)
		s.want("floor_released", "reason", "released")
	}

	a, snap := f.joinedProto("A", p, 2)
	if snap["state"] != "answered" {
		t.Fatalf("snapshot %v", snap)
	}
	a.want("peer_joined", "device_id", f.ids["S1"])
	for i, tx := range txs {
		a.want("clip", "talker", f.ids["S1"], "transmission_id", tx, "last_seq", "2")
		for seq := range 3 {
			a.wantFrame(frameFor(tx, uint32(seq), byte(10*i+seq)))
		}
	}
	s.want("peer_joined", "device_id", f.ids["A"])
}

func TestProtocol1ListenerGetsMilestone4LiveRelay(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joinedProto("A", p, 2)
	f.answer("S1", p)
	a.want("page_state", "state", "answered")
	s, _ := f.joined("S1", p)
	s.want("peer_joined")
	a.want("peer_joined")
	tx := uuid.NewString()
	a.requestClip(tx, true) // ignored toward a protocol-1 listener
	a.want("floor_granted")
	s.want("floor_granted")
	for seq := range 3 {
		a.sendBinary(frameFor(tx, uint32(seq), byte(seq)))
		s.wantFrame(frameFor(tx, uint32(seq), byte(seq)))
	}
	a.release(tx)
	a.want("floor_released", "reason", "released")
	s.want("floor_released", "reason", "released")
	a.quiet() // no listener_gap
	f.bufferBytes(0)
}

func TestQueuingAClipResetsTheIdleTimer(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	_ = s.ws.CloseNow()
	a.want("peer_left")
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	a.want("listener_gap")
	a.sendBinary(frameFor(tx, 0, 0))
	a.release(tx)
	a.want("floor_released", "reason", "released") // resets the timer at +0
	f.advance(110*time.Second, a)
	s, _ = f.joinedProto("S1", p, 2) // the clip is queued: resets the timer at +110 s
	s.want("peer_joined")
	s.want("clip", "transmission_id", tx)
	s.wantFrame(frameFor(tx, 0, 0))
	a.want("peer_joined")
	f.advance(2*time.Minute-time.Second, a, s)
	if f.pageGone(p) {
		t.Fatal("ended early: queuing the clip did not reset the idle timer")
	}
	f.clk.Advance(time.Second)
	a.want("session_end", "reason", "idle")
}

func TestClipsOutliveTheAnswererClosingEverySocket(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joinedProto("A", p, 2)
	_ = a.ws.CloseNow()
	f.answer("S1", p)
	waitFor(t, func() bool { f.hub.mu.Lock(); defer f.hub.mu.Unlock(); return f.hub.rooms[p.id] == nil })

	s, _ := f.joinedProto("S1", p, 2)
	tx, fr := clipVector(t)
	s.requestClip(tx, false)
	s.want("floor_granted", "transmission_id", tx)
	s.want("listener_gap", "transmission_id", tx)
	for _, b := range fr {
		s.sendBinary(b)
	}
	s.release(tx)
	s.want("floor_released", "reason", "released")
	_ = s.ws.CloseNow()
	waitFor(t, func() bool {
		f.hub.mu.Lock()
		defer f.hub.mu.Unlock()
		r := f.hub.rooms[p.id]
		return r != nil && len(r.members) == 0
	})

	a, _ = f.joinedProto("A", p, 2)
	a.want("clip", "talker", f.ids["S1"], "transmission_id", tx, "last_seq", "5")
	for _, b := range fr {
		a.wantFrame(b)
	}
}

func TestProtocol1RejoinMidTransmissionKeepsRelayingUnbuffered(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	s.want("floor_granted")
	_ = s.ws.CloseNow()
	a.want("peer_left")
	a.sendBinary(frameFor(tx, 0, 0))
	a.sendBinary(frameFor(tx, 1, 1))
	a.want("listener_gap")

	s, _ = f.joined("S1", p)
	s.want("peer_joined")
	s.want("floor_granted", "transmission_id", tx)
	a.want("peer_joined")
	f.bufferBytes(txOverhead) // the held frames are freed at the protocol-1 join
	a.sendBinary(frameFor(tx, 2, 2))
	s.wantFrame(frameFor(tx, 2, 2))
	a.sendBinary(frameFor(tx, 1, 9)) // already counted: dropped
	a.sendBinary(frameFor(tx, 3, 3))
	s.wantFrame(frameFor(tx, 3, 3))
	a.release(tx)
	s.want("floor_released", "reason", "released")
	a.want("floor_released", "reason", "released")
	s.quiet() // no clip
	f.bufferBytes(0)
}

func TestClipModeIsNeverRelayedLiveAndGets5sOfSilence(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.bothP2()
	tx := uuid.NewString()
	a.requestClip(tx, true)
	a.want("floor_granted")
	s.want("floor_granted")
	f.clk.Advance(4 * time.Second) // within the 5 s allowance before the first frame
	a.sendBinary(frameFor(tx, 0, 0))
	a.sendBinary(frameFor(tx, 1, 1))
	a.ping()
	f.clk.Advance(4 * time.Second) // within the 5 s allowance after a frame
	a.ping()
	s.quiet() // nothing relayed live, no release yet
	f.clk.Advance(time.Second)
	a.want("floor_released", "reason", "silence")
	s.want("floor_released", "reason", "silence")
	a.sendBinary(frameFor(tx, 2, 2)) // open: still accepted
	a.release(tx)
	a.want("floor_released", "reason", "released")
	s.want("clip", "last_seq", "2")
	for seq := range 3 {
		s.wantFrame(frameFor(tx, uint32(seq), byte(seq)))
	}
	a.quiet() // a clip transmission gets no listener_gap
}

func TestNextFloorRequestClosesTheOpenTransmission(t *testing.T) {
	f := newFixture(t)
	_, a, s := f.bothP2()
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	s.want("floor_granted")
	a.sendBinary(frameFor(tx, 0, 0))
	s.wantFrame(frameFor(tx, 0, 0))
	f.clk.Advance(time.Second)
	a.want("floor_released", "reason", "silence")
	s.want("floor_released", "reason", "silence")
	a.requestClip("not-an-id", false) // whatever the answer
	a.want("floor_released", "transmission_id", tx, "reason", "released")
	a.want("error", "code", "bad_transmission_id")
	s.quiet() // heard live in full: no clip
	f.bufferBytes(txOverhead + int64(len(frameFor(tx, 0, 0))))
	s.send(map[string]any{"type": "heard", "transmission_id": tx, "last_seq": 0})
	s.ping()
	f.bufferBytes(0)
}

func TestSessionEndFreesTheBuffers(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	_ = s.ws.CloseNow()
	a.want("peer_left")
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	a.want("listener_gap")
	a.sendBinary(frameFor(tx, 0, 0))
	a.release(tx)
	a.want("floor_released")
	if f.hub.ClipCounts().BufferBytes == 0 {
		t.Fatal("nothing buffered")
	}
	a.send(map[string]any{"type": "session_end"})
	a.want("session_end", "reason", "ended_by_you")
	f.bufferBytes(0)
	if !f.pageGone(p) {
		t.Fatal("page not ended")
	}
}

func TestRestartDropsTheBuffers(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.bothP2()
	_ = s.ws.CloseNow()
	a.want("peer_left")
	tx := uuid.NewString()
	a.requestClip(tx, false)
	a.want("floor_granted")
	a.want("listener_gap")
	a.sendBinary(frameFor(tx, 0, 0))
	a.release(tx)
	a.want("floor_released")

	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	f.hub.Shutdown(ctx)
	a.wantClose(4503, "server_restarting")
	if f.hub.ClipCounts().BufferBytes != 0 {
		t.Fatal("buffers survive the shutdown")
	}
	f.srv.Close()

	// A new process: nothing of the transmission is known.
	f.hub = New(Options{DB: f.db, Ender: f.svc, Clock: f.clk})
	f.hub.started = t0.Add(-time.Hour)
	f.svc.SetNotifier(f.hub)
	f.srv = httptest.NewServer(f.hub)
	s, _ = f.joinedProto("S1", p, 2)
	s.quiet() // no clip
	if f.hub.ClipCounts().BufferBytes != 0 {
		t.Fatal("a new hub holds buffers")
	}
}
