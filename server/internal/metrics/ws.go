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

// RegisterFrames adds the relay counters: audio frames relayed live to the listening peer, and
// frames dropped (from a joined socket that is not the talker, a stale transmission or seq, or
// past the peer's 25-frame queue; also a protocol-1 talker's frames while the peer is away).
// Frames on an unjoined socket never reach the relay and are not counted; a frame buffered
// instead of relayed live counts in neither, and neither does a clip replay (protocol 13.11).
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

// ClipCounts are the clip-fallback figures of protocol section 13.11.
type ClipCounts struct {
	Transmissions, ClipTransmissions, ListenerGaps, Resumes, Evictions uint64
	BufferBytes                                                        int64
}

// RegisterClips adds the clip-fallback metrics of protocol section 13.11. Aggregates only, no
// labels, no ids.
func RegisterClips(reg prometheus.Registerer, counts func() ClipCounts) {
	counter := func(name, help string, v func(ClipCounts) uint64) prometheus.Collector {
		return prometheus.NewCounterFunc(prometheus.CounterOpts{Name: name, Help: help},
			func() float64 { return float64(v(counts())) })
	}
	reg.MustRegister(
		counter("pttd_ws_transmissions_total", "Transmissions granted, every protocol.",
			func(c ClipCounts) uint64 { return c.Transmissions }),
		counter("pttd_ws_clip_transmissions_total", "Transmissions for which at least one clip was sent.",
			func(c ClipCounts) uint64 { return c.ClipTransmissions }),
		counter("pttd_ws_listener_gaps_total", "listener_gap messages sent.",
			func(c ClipCounts) uint64 { return c.ListenerGaps }),
		counter("pttd_ws_resumes_total", "resume_from replies sent.",
			func(c ClipCounts) uint64 { return c.Resumes }),
		counter("pttd_ws_clip_evictions_total", "Buffered transmissions removed by a cap.",
			func(c ClipCounts) uint64 { return c.Evictions }),
		prometheus.NewGaugeFunc(prometheus.GaugeOpts{
			Name: "pttd_ws_clip_buffer_bytes",
			Help: "Bytes charged to transmission buffers over all sessions (frames plus per-transmission overhead).",
		}, func() float64 { return float64(counts().BufferBytes) }),
	)
}
