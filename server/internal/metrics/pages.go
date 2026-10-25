package metrics

import "github.com/prometheus/client_golang/prometheus"

// Reasons a page ends (the pttd_pages_ended_total label). A fixed set: no label identifies a
// page or a person.
const (
	EndCancelled = "cancelled" // the sender cancelled
	EndEnded     = "ended"     // the answering device ended the conversation
	EndMissed    = "missed"    // a normal page went unanswered for 15 minutes
	EndFailed    = "failed"    // no recipient could be pushed to
	EndExpired   = "expired"   // an answered page reached its 15 minute limit
)

// Kinds of FCM send error (the pttd_fcm_send_errors_total label).
const (
	FCMUnregistered = "unregistered" // FCM no longer knows the token
	FCMPermanent    = "permanent"    // invalid argument, no token, FCM not configured, ...
	FCMTransient    = "transient"    // anything else; may succeed on retry
)

// Pages holds the page-lifecycle collectors. All are aggregates.
type Pages struct {
	Delivery *prometheus.HistogramVec
	Answer   *prometheus.HistogramVec
	Ended    *prometheus.CounterVec
	FCMError *prometheus.CounterVec
}

// NewPages returns the collectors, with every label value present at zero so a series exists
// before the first event.
func NewPages() *Pages {
	p := &Pages{
		Delivery: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "pttd_page_delivery_seconds",
			Help:    "Time from page creation to the first delivered report.",
			Buckets: []float64{0.5, 1, 2, 5, 10, 30, 60, 120, 300, 900},
		}, nil),
		Answer: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "pttd_page_answer_seconds",
			Help:    "Time from page creation to the answer.",
			Buckets: []float64{1, 2, 5, 10, 30, 60, 120, 300, 600, 900},
		}, nil),
		Ended: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "pttd_pages_ended_total",
			Help: "Pages that ended, by reason.",
		}, []string{"reason"}),
		FCMError: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "pttd_fcm_send_errors_total",
			Help: "FCM sends that failed, by kind.",
		}, []string{"kind"}),
	}
	p.Delivery.WithLabelValues()
	p.Answer.WithLabelValues()
	for _, r := range []string{EndCancelled, EndEnded, EndMissed, EndFailed, EndExpired} {
		p.Ended.WithLabelValues(r)
	}
	for _, k := range []string{FCMUnregistered, FCMPermanent, FCMTransient} {
		p.FCMError.WithLabelValues(k)
	}
	return p
}

// Register adds the collectors to reg.
func (p *Pages) Register(reg prometheus.Registerer) {
	reg.MustRegister(p.Delivery, p.Answer, p.Ended, p.FCMError)
}
