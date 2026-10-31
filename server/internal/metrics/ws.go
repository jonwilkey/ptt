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
