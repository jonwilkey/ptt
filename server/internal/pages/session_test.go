package pages

import (
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

// TestSessionIDIsUniqueAmongActivePages is protocol section 11.4: a different page may not reuse
// the session id of an active one (409), the same page's retry is not a conflict, and an ended
// page does not hold its session id.
func TestSessionIDIsUniqueAmongActivePages(t *testing.T) {
	f := newFixture(t)
	session := uuid.NewString()
	first, caller := f.request(pageSpec{session: session})
	if _, err := f.svc.Create(t.Context(), caller, first); err != nil {
		t.Fatal(err)
	}

	second, _ := f.request(pageSpec{session: session}) // another page id, the same session id
	if _, err := f.svc.Create(t.Context(), caller, second); !errors.Is(err, ErrConflict) {
		t.Fatalf("a different page on an active session id: err = %v, want ErrConflict", err)
	}
	if f.count(`SELECT COUNT(*) FROM active_pages`) != 1 || f.count(`SELECT COUNT(*) FROM page_tombstones`) != 0 {
		t.Error("the conflicting request left a trace")
	}

	// The idempotent retry of the first page is the 200 case, not a conflict.
	res, err := f.svc.Create(t.Context(), caller, first)
	if err != nil || res.Created || res.State != StateRinging {
		t.Fatalf("retry = %+v, %v", res, err)
	}

	// Once the first page has ended its session id is free again.
	if err := f.svc.Cancel(t.Context(), caller.DeviceID, first.ID); err != nil {
		t.Fatal(err)
	}
	if res, err := f.svc.Create(t.Context(), caller, second); err != nil || !res.Created {
		t.Fatalf("after the first page ended: %+v, %v", res, err)
	}
	// ...and the retry of the ended first page still answers `ended`, never re-creating it.
	if res, err := f.svc.Create(t.Context(), caller, first); err != nil || res.State != StateEnded {
		t.Fatalf("retry of the ended page = %+v, %v", res, err)
	}
}

func TestSessionIDUniqueIndexIsTheBackstop(t *testing.T) {
	f := newFixture(t)
	req, caller := f.request(pageSpec{})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	_, err := f.db.ExecContext(t.Context(), `
		INSERT INTO active_pages (id, session_id, from_device, to_user, level, envelope, state, created_at)
		VALUES (?, ?, ?, ?, 'normal', x'00', 'ringing', 0)`,
		uuid.NewString(), req.SessionID, caller.DeviceID, f.samUser)
	if err == nil {
		t.Fatal("the database accepted two active pages with one session id")
	}
}

// recordingNotifier is a Notifier with a scripted answer, to test the routing rules alone.
type recordingNotifier struct {
	mu       sync.Mutex
	live     map[string]bool // device id -> live
	progress []string        // "device:state"
	ended    []Ending
	held     bool
}

func (n *recordingNotifier) Progress(_, deviceID, _, state string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.progress = append(n.progress, deviceID+":"+state)
	return n.live[deviceID]
}
func (n *recordingNotifier) Answered(string, string, string) {}
func (n *recordingNotifier) Ended(e Ending) Liveness {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.ended = append(n.ended, e)
	return Liveness{Sender: n.live[e.Sender], Answerer: n.live[e.Answerer]}
}
func (n *recordingNotifier) Held(string) bool { return n.held }

func TestPageAnsweredIsAlwaysByFCMAndOtherProgressOnlyWhenNotLive(t *testing.T) {
	f := newFixture(t)
	n := &recordingNotifier{live: map[string]bool{f.ids["A"]: true}}
	f.svc.SetNotifier(n)

	id := f.create(pageSpec{})
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	if err := f.svc.Answer(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	// A is live: delivered and acknowledged were offered to the socket and not pushed; the
	// answer was offered too, yet page_answered is sent regardless.
	f.wantPushes("page_ack -> tok-S2 [high 900s]", "page_answered -> tok-A [high 900s]",
		"page_cancel:answered_elsewhere -> tok-S2 [high 900s]")
	if got := n.progress; len(got) != 3 || got[0] != f.ids["A"]+":delivered" || got[1] != f.ids["A"]+":acknowledged" || got[2] != f.ids["A"]+":answered" {
		t.Errorf("progress offered = %v", got)
	}

	// Not live: the same transitions go by FCM.
	n.live = map[string]bool{}
	id2 := f.create(pageSpec{})
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id2); err != nil {
		t.Fatal(err)
	}
	if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], id2); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_ack -> tok-S2 [high 900s]", "page_state:acknowledged -> tok-A [normal 900s]",
		"page_state:delivered -> tok-A [normal 900s]")
}

func TestEndFCMDependsOnLiveness(t *testing.T) {
	f := newFixture(t)
	n := &recordingNotifier{live: map[string]bool{}}
	f.svc.SetNotifier(n)

	// Answerer End, sender live: no page_state ended.
	id := f.create(pageSpec{})
	if err := f.svc.Answer(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	n.live[f.ids["A"]] = true
	if err := f.svc.Cancel(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes()
	if e := n.ended[len(n.ended)-1]; e.Initiator != f.ids["S1"] || e.Sender != f.ids["A"] || e.Answerer != f.ids["S1"] {
		t.Errorf("Ending = %+v", e)
	}

	// Idle, sender not live: page_state ended.
	n.live = map[string]bool{}
	id2 := f.create(pageSpec{})
	if err := f.svc.Answer(t.Context(), f.ids["S1"], id2); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	if err := f.svc.EndIdle(t.Context(), id2); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_state:ended -> tok-A [normal 900s]")
	if e := n.ended[len(n.ended)-1]; e.Initiator != "" || e.Reason != ReasonIdle {
		t.Errorf("Ending = %+v", e)
	}
	// EndIdle on a page that is gone or still ringing does nothing.
	if err := f.svc.EndIdle(t.Context(), id2); err != nil {
		t.Fatal(err)
	}
	ringing := f.create(pageSpec{})
	if err := f.svc.EndIdle(t.Context(), ringing); err != nil || f.state(ringing) != StateRinging {
		t.Fatalf("EndIdle on a ringing page: %v, state %s", err, f.state(ringing))
	}
}

func TestEndRevokedOnlyEndsAnAnsweredPageOfAParticipant(t *testing.T) {
	f := newFixture(t)
	n := &recordingNotifier{live: map[string]bool{}}
	f.svc.SetNotifier(n)
	id := f.create(pageSpec{})
	// Ringing: unchanged by revocation.
	if err := f.svc.EndRevoked(t.Context(), f.ids["A"], id); err != nil || f.state(id) != StateRinging {
		t.Fatalf("ringing: %v %s", err, f.state(id))
	}
	if err := f.svc.Answer(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	// Not a participant (S2 never answered).
	if err := f.svc.EndRevoked(t.Context(), f.ids["S2"], id); err != nil || f.state(id) != StateAnswered {
		t.Fatalf("non-participant: %v %s", err, f.state(id))
	}
	if err := f.svc.EndRevoked(t.Context(), f.ids["S1"], id); err != nil || f.state(id) != StateEnded {
		t.Fatalf("participant: %v %s", err, f.state(id))
	}
	f.wantPushes("page_state:ended -> tok-A [normal 900s]")
}

func TestSchedulerLeavesAHeldSessionToTheIdleTimer(t *testing.T) {
	f := newFixture(t)
	n := &recordingNotifier{live: map[string]bool{}, held: true}
	f.svc.SetNotifier(n)
	id := f.create(pageSpec{})
	if err := f.svc.Answer(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	f.runDue(AnsweredTTL + 1)
	if f.state(id) != StateAnswered {
		t.Fatalf("a held session was ended by the 15-minute cap: %s", f.state(id))
	}
	n.held = false
	f.runDue(time.Second)
	if f.state(id) != StateEnded {
		t.Fatalf("released session not ended: %s", f.state(id))
	}
}
