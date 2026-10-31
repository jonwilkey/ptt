package metrics

import "github.com/prometheus/client_golang/prometheus"

// RegisterWS adds the WebSocket gauge: open sockets, authenticated or not. Like every metric
// here it is an aggregate; pttd_active_sessions (New) is the other WebSocket gauge.
func RegisterWS(reg prometheus.Registerer, sockets func() int) {
	reg.MustRegister(prometheus.NewGaugeFunc(prometheus.GaugeOpts{
		Name: "pttd_ws_active_sockets",
		Help: "Open live-talk WebSockets.",
	}, func() float64 { return float64(sockets()) }))
}

// RegisterFrames adds the relay counters: audio frames passed to the listening peer, and frames
// dropped (from a joined socket that is not the talker, a stale transmission, or past the
// peer's 25-frame queue; also the talker's frames while the peer is away). Frames on an unjoined
// socket never reach the relay and are not counted.
// Aggregates only, no labels.
func RegisterFrames(reg prometheus.Registerer, counts func() (relayed, dropped uint64)) {
	reg.MustRegister(
		prometheus.NewCounterFunc(prometheus.CounterOpts{
			Name: "pttd_ws_frames_relayed_total",
			Help: "Audio frames relayed to the listening participant.",
		}, func() float64 { r, _ := counts(); return float64(r) }),
		prometheus.NewCounterFunc(prometheus.CounterOpts{
			Name: "pttd_ws_frames_dropped_total",
			Help: "Audio frames from joined sockets dropped instead of relayed (not the talker, stale, peer away or queue full).",
		}, func() float64 { _, d := counts(); return float64(d) }),
	)
}
