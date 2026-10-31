package pages

// Notifier is the page lifecycle's door to live (WebSocket) participants, protocol section 8,
// "Pushes per transition". It keeps this package free of the WebSocket package: internal/ws
// implements it and is handed to the Service with SetNotifier.
//
// Every method is called outside any database transaction (after the transition committed;
// Held, before the scheduler starts one), and must not block: it only queues messages for the sockets. A participant's progress
// reaches it over exactly one channel, chosen at the instant of the transition: if the
// Notifier reports it delivered (a live socket), the FCM push is skipped; otherwise it is sent.
// (The one exception, page_answered, is always sent by FCM; see Answer.)
type Notifier interface {
	// Progress offers a non-terminal page_state message (ringing, delivered, acknowledged,
	// not_delivered or answered) to the device's socket for the session. It reports true when
	// the device's socket is live (joined to the session and heard from in the last 15 s) and
	// the message was queued on it.
	Progress(sessionID, deviceID, pageID, state string) bool
	// Answered tells the Notifier which device answered the page, so a session whose sender is
	// already waiting knows its answerer.
	Answered(sessionID, pageID, answererID string)
	// Ended announces that a page ended and its row is already deleted: every joined socket
	// gets session_end and is closed. It returns which of the two participants were live at
	// that instant, which decides whether the FCM fallback is still needed.
	Ended(e Ending) Liveness
	// Held reports whether the page's live session has had both participants joined, so the
	// WebSocket idle timer, not the 15-minute answered-page cap, bounds it (protocol section
	// 11.5), or the server only just started and the participants have not had the chance to
	// rejoin yet (a restart grace of one idle timeout). The scheduler then leaves the page alone.
	Held(pageID string) bool
}

// Reasons a page ends by itself, as the session_end reason sent to the participants when no
// device ended it (protocol section 11.5).
const (
	ReasonIdle   = "idle"
	ReasonMissed = "missed"
	ReasonFailed = "failed"
)

// Ending describes a page that has just ended.
type Ending struct {
	PageID    string
	SessionID string
	Sender    string
	// Answerer is the answering device, empty if the page was never answered.
	Answerer string
	// Initiator is the device that ended it (cancel, answerer End, session_end, or a revoked
	// participant); it is empty when the server did, and then Reason says why.
	Initiator string
	Reason    string
}

// Liveness says which participants had a live socket when the page ended.
type Liveness struct {
	Sender   bool
	Answerer bool
}

type nopNotifier struct{}

func (nopNotifier) Progress(string, string, string, string) bool { return false }
func (nopNotifier) Answered(string, string, string)              {}
func (nopNotifier) Ended(Ending) Liveness                        { return Liveness{} }
func (nopNotifier) Held(string) bool                             { return false }
