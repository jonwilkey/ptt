// Package metrics defines pttd's Prometheus registry.
//
// Metrics are aggregate only: no label may identify a person, device, page, or session.
package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
)

// New returns a registry with the Go runtime and process collectors, pttd_build_info, and
// pttd_active_sessions, which calls activeSessions on every scrape. The deploy workflow waits
// for pttd_active_sessions to reach 0 before restarting the container.
func New(version string, activeSessions func() int) *prometheus.Registry {
	reg := prometheus.NewRegistry()
	reg.MustRegister(
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
		prometheus.NewGaugeFunc(prometheus.GaugeOpts{
			Name:        "pttd_build_info",
			Help:        "Always 1; the version label identifies the running build.",
			ConstLabels: prometheus.Labels{"version": version},
		}, func() float64 { return 1 }),
		prometheus.NewGaugeFunc(prometheus.GaugeOpts{
			Name: "pttd_active_sessions",
			Help: "Talk sessions currently open.",
		}, func() float64 { return float64(activeSessions()) }),
	)
	return reg
}
