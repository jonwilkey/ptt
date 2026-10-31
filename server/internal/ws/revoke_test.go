package ws

import "testing"

func TestRevokedDeviceFailsHelloJoinAndFloorRequest(t *testing.T) {
	f := newFixture(t)
	p := f.page()

	// Revoked after hello: join re-checks.
	c := f.connect("A")
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["A"])
	c.send(map[string]any{"type": "join", "session_id": p.session})
	c.wantClose(4403, "revoked")

	// Revoked after join: the next floor_request re-checks.
	f2 := newFixture(t)
	p2 := f2.page()
	f2.answer("S1", p2)
	a, _ := f2.joined("A", p2)
	s, _ := f2.joined("S1", p2)
	a.want("peer_joined")
	s.want("peer_joined")
	f2.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f2.ids["S1"])
	s.send(map[string]any{"type": "floor_request", "transmission_id": "6f1d6a4e-9c1b-4f5e-8a7d-2b3c4d5e6f70"})
	s.wantClose(4403, "revoked")
	// ...which ends the session for the peer.
	a.want("peer_left", "device_id", f2.ids["S1"])
	a.want("session_end", "reason", "ended_by_peer")
	if !f2.pageGone(p2) {
		t.Error("the page of a revoked participant's session is not ended")
	}
}

func TestRESTRevokeClosesTheSocketAtOnce(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["S1"])
	f.hub.CloseDevice(f.ids["S1"]) // what POST /v1/devices/{id}/revoke does
	s.wantClose(4403, "revoked")   // not a session_end
	a.want("peer_left", "device_id", f.ids["S1"])
	a.want("session_end", "reason", "ended_by_peer")
	a.wantClose(1000, "normal")
	if !f.pageGone(p) {
		t.Error("page not ended")
	}
	// The sender was live; the answerer (revoked) is told nothing further.
	f.wantFCM()
}

func TestSweepClosesRevokedDevices(t *testing.T) {
	f := newFixture(t)
	p, a, s := f.both()
	other := f.connect("S2")
	// `pttd admin revoke` only writes revoked_at.
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["A"])
	f.hub.Sweep(t.Context())
	a.wantClose(4403, "revoked")
	s.want("peer_left", "device_id", f.ids["A"])
	s.want("session_end", "reason", "ended_by_peer")
	s.wantClose(1000, "normal")
	other.ping() // an unrelated device is untouched
	if !f.pageGone(p) {
		t.Error("page not ended")
	}
	// The revoked sender cancelled its page: the answerer was live, so no FCM page_cancel.
	f.wantFCM()
}

func TestRevokedSenderOfARingingPageKeepsThePage(t *testing.T) {
	f := newFixture(t)
	p := f.page()
	a, _ := f.joined("A", p)
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["A"])
	f.hub.Sweep(t.Context())
	a.wantClose(4403, "revoked")
	if f.count(`SELECT COUNT(*) FROM active_pages WHERE id = ?`, p.id) != 1 {
		t.Error("revoking a sender ended its ringing page; the WebSocket must not change that")
	}
}

func TestSweepStartsWithTheHubAndRunsEvery5Seconds(t *testing.T) {
	f := newFixture(t)
	f.hub.Start()
	c := f.connect("A")
	f.exec(`UPDATE devices SET revoked_at = 1 WHERE id = ?`, f.ids["A"])
	f.clk.BlockUntil(2) // the sweep and the socket's watcher
	f.clk.Advance(sweepInterval)
	c.wantClose(4403, "revoked")
}
