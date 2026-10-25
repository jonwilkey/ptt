package pages

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/push"
)

func TestCreateRingsEveryLiveRecipient(t *testing.T) {
	f := newFixture(t)
	f.revoke("S2")
	req, caller := f.request(pageSpec{level: LevelUrgent})
	res, err := f.svc.Create(t.Context(), caller, req)
	if err != nil || res != (CreateResult{State: StateRinging, Created: true}) {
		t.Fatalf("Create = %+v, %v", res, err)
	}
	// The revoked device's entry is ignored; the live one gets a high-priority page with a 24 h TTL.
	f.svc.Wait()
	msgs := f.sender.Messages()
	if len(msgs) != 1 || msgs[0].Token != "tok-S1" || msgs[0].Priority != "high" || msgs[0].TTLSecs != 24*3600 {
		t.Fatalf("pushes = %+v", msgs)
	}
	d := msgs[0].Data
	if d["type"] != "page" || d["page_id"] != req.ID || d["level"] != "urgent" || d["from_user"] != f.alexUser || len(d) != 5 {
		t.Errorf("data = %v", d)
	}
	if !strings.Contains(d["envelope"], `"payload":`) || !strings.Contains(d["envelope"], `"sig":`) {
		t.Errorf("envelope = %s", d["envelope"])
	}
	if got := f.count(`SELECT push_attempts FROM active_pages WHERE id = ?`, req.ID); got != 1 {
		t.Errorf("push_attempts = %d", got)
	}
}

func TestCreateNormalTTL(t *testing.T) {
	f := newFixture(t)
	f.create(pageSpec{})
	// create() clears the recorder, so create again by hand.
	req, caller := f.request(pageSpec{})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page -> tok-S1 [high 900s]", "page -> tok-S2 [high 900s]")
}

func TestCreateIdempotency(t *testing.T) {
	f := newFixture(t)
	req, caller := f.request(pageSpec{})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	f.pushes()

	// Same body: 200 with the current state, no new push, whatever the state is by now.
	res, err := f.svc.Create(t.Context(), caller, req)
	if err != nil || res != (CreateResult{State: StateRinging}) {
		t.Fatalf("retry = %+v, %v", res, err)
	}
	if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], req.ID); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	if res, err = f.svc.Create(t.Context(), caller, req); err != nil || res != (CreateResult{State: StateAcknowledged}) {
		t.Fatalf("retry after ack = %+v, %v", res, err)
	}
	f.wantPushes()

	// A different body under the same id is a conflict, however it differs.
	sig := req
	sig.Envelope.Sig = strings.Repeat("A", 86)
	diffs := map[string]CreateRequest{
		"to":      must(f.request(pageSpec{id: req.ID, session: req.SessionID, to: f.alexUser, keys: []string{f.ids["A"]}})),
		"level":   must(f.request(pageSpec{id: req.ID, session: req.SessionID, level: LevelUrgent})),
		"session": must(f.request(pageSpec{id: req.ID})),
		"payload": req2(f, req),
		"sig":     sig,
	}
	for name, other := range diffs {
		t.Run(name, func(t *testing.T) {
			if _, err := f.svc.Create(t.Context(), caller, other); !errors.Is(err, ErrConflict) {
				t.Fatalf("err = %v, want ErrConflict", err)
			}
		})
	}

	// After the page ended, a retry (even with a different body) is "ended" and never re-pushes.
	if err := f.svc.Cancel(t.Context(), f.ids["A"], req.ID); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	for _, r := range []CreateRequest{req, req2(f, req)} {
		res, err = f.svc.Create(t.Context(), caller, r)
		if err != nil || res != (CreateResult{State: StateEnded}) {
			t.Fatalf("retry after end = %+v, %v", res, err)
		}
	}
	f.wantPushes()
	if f.state(req.ID) != StateEnded {
		t.Error("page row came back")
	}
}

func must(r CreateRequest, _ Caller) CreateRequest { return r }

// req2 is a request for the same page id with a different (but valid) envelope.
func req2(f *fixture, like CreateRequest) CreateRequest {
	r, _ := f.request(pageSpec{id: like.ID, session: like.SessionID, level: like.Level, mutate: func(p map[string]any) {
		p["created_at"] = f.clk.Now().Add(time.Hour).UnixMilli()
	}})
	return r
}

func TestCreateNoLiveRecipient(t *testing.T) {
	f := newFixture(t)
	f.revoke("S1")
	f.revoke("S2")
	req, caller := f.request(pageSpec{})
	res, err := f.svc.Create(t.Context(), caller, req)
	if err != nil || res != (CreateResult{State: StateEnded, Created: true}) {
		t.Fatalf("Create = %+v, %v", res, err)
	}
	// Tombstoned at once, no row, and no page_state push to the sender.
	f.wantPushes()
	if f.state(req.ID) != StateEnded || f.count(`SELECT COUNT(*) FROM active_pages`) != 0 {
		t.Error("expected only a tombstone")
	}
	if res, err = f.svc.Create(t.Context(), caller, req); err != nil || res != (CreateResult{State: StateEnded}) {
		t.Fatalf("retry = %+v, %v", res, err)
	}
}

func TestCreateValidation(t *testing.T) {
	f := newFixture(t)
	tests := map[string]struct {
		spec   pageSpec
		mutate func(*CreateRequest)
		caller string
	}{
		"signer is not the caller":      {mutate: func(r *CreateRequest) { r.Envelope.Signer = f.ids["S1"] }},
		"from_device is not the caller": {spec: pageSpec{mutate: func(p map[string]any) { p["from_device"] = f.ids["S1"] }}},
		"caller is not the sender":      {caller: "S1"},
		"page_id mismatch":              {spec: pageSpec{mutate: func(p map[string]any) { p["page_id"] = uuid.NewString() }}},
		"session_id mismatch":           {spec: pageSpec{mutate: func(p map[string]any) { p["session_id"] = uuid.NewString() }}},
		"to_user mismatch":              {spec: pageSpec{mutate: func(p map[string]any) { p["to_user"] = f.alexUser }}},
		"level mismatch":                {spec: pageSpec{mutate: func(p map[string]any) { p["level"] = LevelUrgent }}},
		"key for another user's device": {spec: pageSpec{keys: []string{f.ids["S1"], f.ids["A"]}}},
		"key for an unknown device":     {spec: pageSpec{keys: []string{f.ids["S1"], uuid.NewString()}}},
		"keys is a list":                {spec: pageSpec{mutate: func(p map[string]any) { p["keys"] = []string{} }}},
		"short wrapped key":             {spec: pageSpec{mutate: func(p map[string]any) { p["keys"] = map[string]string{f.ids["S1"]: "AAAA"} }}},
		"unknown envelope field":        {spec: pageSpec{mutate: func(p map[string]any) { p["extra"] = 1 }}},
		"bad request id":                {mutate: func(r *CreateRequest) { r.ID = strings.ToUpper(r.ID) }},
		"bad request level":             {mutate: func(r *CreateRequest) { r.Level = "" }},
		"bad request to":                {mutate: func(r *CreateRequest) { r.To = "x" }},
		"bad request session":           {mutate: func(r *CreateRequest) { r.SessionID = "x" }},
		"envelope payload not b64u":     {mutate: func(r *CreateRequest) { r.Envelope.Payload = "!!" }},
		"envelope sig wrong length":     {mutate: func(r *CreateRequest) { r.Envelope.Sig = "AAAA" }},
		"envelope signer not a uuid":    {mutate: func(r *CreateRequest) { r.Envelope.Signer = "x" }},
	}
	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			req, caller := f.request(tt.spec)
			if tt.caller != "" {
				caller = f.caller(tt.caller)
			}
			if tt.mutate != nil {
				tt.mutate(&req)
			}
			_, err := f.svc.Create(t.Context(), caller, req)
			if !errors.Is(err, ErrInvalid) {
				t.Fatalf("err = %v, want ErrInvalid", err)
			}
			f.wantPushes()
			if n := f.count(`SELECT COUNT(*) FROM active_pages`) + f.count(`SELECT COUNT(*) FROM page_tombstones`); n != 0 {
				t.Errorf("rejected request left %d rows", n)
			}
		})
	}
}

func TestCreateOversizePush(t *testing.T) {
	f := newFixture(t)
	var keys []string
	for i := range 40 {
		keys = append(keys, f.device("X"+string(rune('a'+i%26))+string(rune('a'+i/26)), f.samUser))
	}
	req, caller := f.request(pageSpec{keys: keys})
	if _, err := f.svc.Create(t.Context(), caller, req); !errors.Is(err, ErrInvalid) {
		t.Fatalf("err = %v, want ErrInvalid for a push over 4096 bytes", err)
	}
	// Two devices (about 1 KB) are fine.
	f.create(pageSpec{})
}

func TestCreateRateLimit(t *testing.T) {
	f := newFixture(t)
	var first CreateRequest
	for i := range createLimit {
		req, caller := f.request(pageSpec{})
		if i == 0 {
			first = req
		}
		if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
			t.Fatalf("create %d: %v", i, err)
		}
	}
	req, caller := f.request(pageSpec{})
	_, err := f.svc.Create(t.Context(), caller, req)
	var rl *RateLimitedError
	if !errors.As(err, &rl) || rl.RetryAfter <= 0 || rl.RetryAfter > time.Minute {
		t.Fatalf("err = %v, want RateLimitedError", err)
	}
	if f.state(req.ID) != "gone" {
		t.Error("a rate-limited page was stored")
	}
	// Idempotent retries and ended-page retries do not count (and are not refused).
	for range 3 * createLimit {
		if res, err := f.svc.Create(t.Context(), caller, first); err != nil || res.Created {
			t.Fatalf("retry = %+v, %v", res, err)
		}
	}
	if err := f.svc.Cancel(t.Context(), f.ids["A"], first.ID); err != nil {
		t.Fatal(err)
	}
	if res, err := f.svc.Create(t.Context(), caller, first); err != nil || res.State != StateEnded {
		t.Fatalf("ended retry = %+v, %v", res, err)
	}
	// The budget returns with the window; another device has its own.
	f.clk.Advance(time.Minute)
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatalf("after the window: %v", err)
	}
}

func TestTransitionsAndPushes(t *testing.T) {
	f := newFixture(t)
	s1, s2 := f.ids["S1"], f.ids["S2"]
	ctx := t.Context()
	id := f.create(pageSpec{})

	// delivered: the flag is set and the sender hears, once; the state comes back.
	if st, err := f.svc.Delivered(ctx, s1, id); err != nil || st != StateRinging {
		t.Fatalf("delivered = %q, %v", st, err)
	}
	f.wantPushes("page_state:delivered -> tok-A [normal 900s]")
	if _, err := f.svc.Delivered(ctx, s2, id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes()
	if f.count(`SELECT COUNT(*) FROM active_pages WHERE delivered_at IS NOT NULL`) != 1 {
		t.Error("delivered_at not set")
	}

	// acknowledge: the other recipients stop ringing, the sender hears; a repeat is silent.
	if err := f.svc.Acknowledge(ctx, s1, id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_ack -> tok-S2 [high 900s]", "page_state:acknowledged -> tok-A [normal 900s]")
	if f.state(id) != StateAcknowledged {
		t.Errorf("state = %s", f.state(id))
	}
	for _, d := range []string{s1, s2} {
		if err := f.svc.Acknowledge(ctx, d, id); err != nil {
			t.Fatal(err)
		}
	}
	f.wantPushes()

	// answer: first wins; the sender is told, the others see "answered elsewhere".
	if err := f.svc.Answer(ctx, s2, id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_answered -> tok-A [high 900s]", "page_cancel:answered_elsewhere -> tok-S1 [high 900s]")
	if err := f.svc.Answer(ctx, s2, id); err != nil {
		t.Errorf("same device repeating answer: %v", err)
	}
	if err := f.svc.Answer(ctx, s1, id); !errors.Is(err, ErrAlreadyAnswered) {
		t.Errorf("second answerer: %v", err)
	}
	f.wantPushes()

	// A late acknowledge is a silent success. A late delivered is "answered" for the device
	// that answered and "ended" for the other one; neither pushes.
	if err := f.svc.Acknowledge(ctx, s1, id); err != nil {
		t.Fatal(err)
	}
	if st, err := f.svc.Delivered(ctx, s2, id); err != nil || st != StateAnswered {
		t.Fatalf("answerer delivered = %q, %v", st, err)
	}
	if _, err := f.svc.Delivered(ctx, s1, id); !errors.Is(err, ErrEnded) {
		t.Fatalf("other device delivered: %v, want ErrEnded", err)
	}
	f.wantPushes()

	// The answering device ends it: the sender is told page_state ended, and nothing remains.
	if err := f.svc.Cancel(ctx, s2, id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_state:ended -> tok-A [normal 900s]")
	if f.state(id) != StateEnded {
		t.Errorf("state = %s", f.state(id))
	}
}

func TestSenderCancel(t *testing.T) {
	f := newFixture(t)
	ctx := t.Context()

	// Before an answer every recipient hears "cancelled".
	id := f.create(pageSpec{})
	if err := f.svc.Cancel(ctx, f.ids["A"], id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_cancel:cancelled -> tok-S1 [high 900s]", "page_cancel:cancelled -> tok-S2 [high 900s]")

	// After an answer only the answering device still shows the page.
	id = f.create(pageSpec{})
	if err := f.svc.Answer(ctx, f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	if err := f.svc.Cancel(ctx, f.ids["A"], id); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_cancel:cancelled -> tok-S1 [high 900s]")
}

func TestActionStatusCodes(t *testing.T) {
	f := newFixture(t)
	ctx := t.Context()
	a, s1, s2 := f.ids["A"], f.ids["S1"], f.ids["S2"]
	id := f.create(pageSpec{})
	unknown := uuid.NewString()

	actions := map[string]func(dev, id string) error{
		"delivered":   func(d, i string) error { _, err := f.svc.Delivered(ctx, d, i); return err },
		"acknowledge": func(d, i string) error { return f.svc.Acknowledge(ctx, d, i) },
		"answer":      func(d, i string) error { return f.svc.Answer(ctx, d, i) },
		"cancel":      func(d, i string) error { return f.svc.Cancel(ctx, d, i) },
	}
	for name, do := range actions {
		t.Run(name+" unknown", func(t *testing.T) {
			if err := do(s1, unknown); !errors.Is(err, ErrNotFound) {
				t.Fatalf("err = %v, want ErrNotFound", err)
			}
		})
	}
	// Only recipients may deliver, acknowledge or answer; the sender is not one.
	for _, name := range []string{"delivered", "acknowledge", "answer"} {
		t.Run(name+" by non-recipient", func(t *testing.T) {
			if err := actions[name](a, id); !errors.Is(err, ErrForbidden) {
				t.Fatalf("sender: %v, want ErrForbidden", err)
			}
		})
	}
	// Cancel: the sender only (a recipient is refused before an answer, and so is the one who did not answer).
	if err := f.svc.Cancel(ctx, s1, id); !errors.Is(err, ErrForbidden) {
		t.Fatalf("recipient cancel before answer: %v", err)
	}
	if err := f.svc.Answer(ctx, s1, id); err != nil {
		t.Fatal(err)
	}
	if err := f.svc.Cancel(ctx, s2, id); !errors.Is(err, ErrForbidden) {
		t.Fatalf("non-answering recipient cancel: %v", err)
	}
	stranger := f.device("Z", f.alexUser)
	if err := f.svc.Cancel(ctx, stranger, id); !errors.Is(err, ErrForbidden) {
		t.Fatalf("stranger cancel: %v", err)
	}
	if f.state(id) != StateAnswered {
		t.Fatalf("a refused cancel changed the page: %s", f.state(id))
	}

	// After the page ended: 410 for everything but cancel, which is final and succeeds.
	if err := f.svc.Cancel(ctx, a, id); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"delivered", "acknowledge", "answer"} {
		if err := actions[name](s1, id); !errors.Is(err, ErrEnded) {
			t.Errorf("%s after end: %v, want ErrEnded", name, err)
		}
	}
	for _, d := range []string{a, s1, stranger} {
		if err := f.svc.Cancel(ctx, d, id); err != nil {
			t.Errorf("repeat cancel by %s: %v", d, err)
		}
	}
}

func TestAnswerRaceHasOneWinner(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	results := make(chan error, 2)
	for _, d := range []string{"S1", "S2"} {
		go func() { results <- f.svc.Answer(t.Context(), f.ids[d], id) }()
	}
	var wins, lost int
	for range 2 {
		switch err := <-results; {
		case err == nil:
			wins++
		case errors.Is(err, ErrAlreadyAnswered):
			lost++
		default:
			t.Fatal(err)
		}
	}
	if wins != 1 || lost != 1 {
		t.Fatalf("wins=%d lost=%d", wins, lost)
	}
	if n := f.count(`SELECT COUNT(*) FROM active_pages WHERE answered_by IS NOT NULL`); n != 1 {
		t.Errorf("answered rows = %d", n)
	}
}

func TestEndingLeavesOnlyATombstone(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	if err := f.svc.Cancel(t.Context(), f.ids["A"], id); err != nil {
		t.Fatal(err)
	}
	if n := f.count(`SELECT COUNT(*) FROM active_pages`); n != 0 {
		t.Errorf("%d page rows remain", n)
	}
	var exp int64
	if err := f.db.QueryRowContext(t.Context(), `SELECT expires_at FROM page_tombstones WHERE id = ?`, id).Scan(&exp); err != nil {
		t.Fatal(err)
	}
	if want := t0.Add(24 * time.Hour).UnixMilli(); exp != want {
		t.Errorf("tombstone expires_at = %d, want %d", exp, want)
	}
	// Nothing about the page is left anywhere else: no audit row, no tombstone column but id and expiry.
	if n := f.count(`SELECT COUNT(*) FROM audit_log`); n != 0 {
		t.Errorf("%d audit rows", n)
	}
}

func TestAllInitialPushesFailEndsAsFailed(t *testing.T) {
	f := newFixture(t)
	f.sender.failToken("tok-S1", push.ErrPermanent)
	f.sender.failToken("tok-S2", push.ErrUnregistered)
	req, caller := f.request(pageSpec{})
	if res, err := f.svc.Create(t.Context(), caller, req); err != nil || res.State != StateRinging {
		t.Fatalf("Create = %+v, %v", res, err)
	}
	f.wantPushes("page_state:failed -> tok-A [normal 900s]")
	if f.state(req.ID) != StateEnded {
		t.Errorf("state = %s", f.state(req.ID))
	}
	// An unregistered token is cleared; a permanent failure keeps it.
	if f.count(`SELECT COUNT(*) FROM devices WHERE id = ? AND fcm_token IS NULL`, f.ids["S2"]) != 1 {
		t.Error("unregistered token not cleared")
	}
	if f.count(`SELECT COUNT(*) FROM devices WHERE id = ? AND fcm_token IS NOT NULL`, f.ids["S1"]) != 1 {
		t.Error("permanently failing token was cleared")
	}
	if res, err := f.svc.Create(t.Context(), caller, req); err != nil || res.State != StateEnded {
		t.Fatalf("retry = %+v, %v", res, err)
	}
}

func TestNoTokensEndsAsFailed(t *testing.T) {
	f := newFixture(t)
	f.exec(`UPDATE devices SET fcm_token = NULL WHERE user_id = ?`, f.samUser)
	req, caller := f.request(pageSpec{})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	f.wantPushes("page_state:failed -> tok-A [normal 900s]")
	if f.state(req.ID) != StateEnded {
		t.Errorf("state = %s", f.state(req.ID))
	}
}

func TestPartialOrTransientFailureKeepsRinging(t *testing.T) {
	f := newFixture(t)
	f.sender.failToken("tok-S1", push.ErrPermanent)
	if id := f.create(pageSpec{}); f.state(id) != StateRinging {
		t.Errorf("one device reachable: state = %s", f.state(id))
	}
	f.sender.failToken("tok-S2", errors.New("fcm unavailable"))
	if id := f.create(pageSpec{}); f.state(id) != StateRinging {
		t.Errorf("transient failure: state = %s", f.state(id))
	}
}

func TestFailPageDoesNothingOnceDelivered(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	f.svc.failPage(t.Context(), id, f.ids["A"])
	if f.state(id) != StateRinging {
		t.Errorf("state = %s", f.state(id))
	}
	f.wantPushes()
}

func TestLogsNeverNameAPageOrAPerson(t *testing.T) {
	f := newFixture(t)
	f.sender.failToken("tok-S1", push.ErrPermanent)
	f.sender.failToken("tok-S2", errors.New("fcm unavailable"))
	req, caller := f.request(pageSpec{})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	f.svc.Wait()
	f.clk.Advance(time.Minute)
	if _, _, err := f.svc.RunDue(t.Context()); err != nil {
		t.Fatal(err)
	}
	f.svc.Wait()
	logs := f.logs.String()
	if logs == "" {
		t.Fatal("expected failures to be logged")
	}
	secrets := []string{req.ID, req.SessionID, f.alexUser, f.samUser, "tok-"}
	for _, n := range []string{"A", "S1", "S2"} {
		secrets = append(secrets, f.ids[n])
	}
	for _, s := range secrets {
		if strings.Contains(logs, s) {
			t.Errorf("log contains %q:\n%s", s, logs)
		}
	}
}

func TestCloseDropsLaterPushes(t *testing.T) {
	f := newFixture(t)
	f.svc.Close(t.Context())
	req, caller := f.request(pageSpec{})
	if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
		t.Fatal(err)
	}
	f.svc.Wait()
	if n := len(f.sender.Messages()); n != 0 {
		t.Errorf("%d pushes after Close", n)
	}
}

func TestDeliveredAfterAcknowledgeIsSilent(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	if err := f.svc.Acknowledge(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	for range 2 { // a repeat returns the same state
		if st, err := f.svc.Delivered(t.Context(), f.ids["S2"], id); err != nil || st != StateAcknowledged {
			t.Fatalf("delivered = %q, %v", st, err)
		}
	}
	// The flag is set, but the sender is not told "delivered" for a page already acknowledged.
	f.wantPushes()
	if f.count(`SELECT COUNT(*) FROM active_pages WHERE delivered_at IS NOT NULL`) != 1 {
		t.Error("delivered_at not set")
	}
}

func TestTestAndRealDevicesNeverPageEachOther(t *testing.T) {
	f := newFixture(t)
	f.exec(`UPDATE devices SET is_test = 1 WHERE id = ?`, f.ids["S2"])
	req, caller := f.request(pageSpec{}) // real sender, one test recipient
	if _, err := f.svc.Create(t.Context(), caller, req); !errors.Is(err, ErrInvalid) {
		t.Fatalf("real -> test: %v, want ErrInvalid", err)
	}
	// A test sender may page test devices only.
	f.exec(`UPDATE devices SET is_test = 1 WHERE id IN (?, ?)`, f.ids["A"], f.ids["S1"])
	req, caller = f.request(pageSpec{})
	if res, err := f.svc.Create(t.Context(), caller, req); err != nil || res.State != StateRinging {
		t.Fatalf("test -> test: %+v, %v", res, err)
	}
	f.exec(`UPDATE devices SET is_test = 0 WHERE id = ?`, f.ids["S2"])
	req, caller = f.request(pageSpec{keys: []string{f.ids["S2"]}})
	if _, err := f.svc.Create(t.Context(), caller, req); !errors.Is(err, ErrInvalid) {
		t.Fatalf("test -> real: %v, want ErrInvalid", err)
	}
}

func TestRejectedRequestsDoNotUseRateLimitBudget(t *testing.T) {
	f := newFixture(t)
	bad, caller := f.request(pageSpec{keys: []string{f.ids["S1"], f.ids["A"]}})
	for range 2 * createLimit {
		if _, err := f.svc.Create(t.Context(), caller, bad); !errors.Is(err, ErrInvalid) {
			t.Fatalf("err = %v", err)
		}
	}
	for i := range createLimit {
		req, caller := f.request(pageSpec{})
		if _, err := f.svc.Create(t.Context(), caller, req); err != nil {
			t.Fatalf("create %d after rejections: %v", i, err)
		}
	}
}

func TestRepushWhereNobodyIsReachedFailsThePage(t *testing.T) {
	f := newFixture(t)
	// The initial push fails transiently, so the page keeps ringing ...
	f.sender.failToken("tok-S1", errors.New("fcm unavailable"))
	f.sender.failToken("tok-S2", errors.New("fcm unavailable"))
	id := f.create(pageSpec{})
	if f.state(id) != StateRinging {
		t.Fatalf("state = %s", f.state(id))
	}
	// ... and the re-push fails permanently: the page fails, with no not_delivered.
	f.sender.failToken("tok-S1", push.ErrPermanent)
	f.sender.failToken("tok-S2", push.ErrUnregistered)
	f.runDue(FirstRepush)
	f.wantPushes("page_state:failed -> tok-A [normal 900s]")
	if f.state(id) != StateEnded {
		t.Errorf("state = %s", f.state(id))
	}
}

func TestRepushSkipsAPageThatMovedOn(t *testing.T) {
	f := newFixture(t)
	id := f.create(pageSpec{})
	p, err := f.svc.q.GetActivePage(t.Context(), id)
	if err != nil {
		t.Fatal(err)
	}
	// The page is delivered after the scheduler's transaction but before the pushes go out.
	if _, err := f.svc.Delivered(t.Context(), f.ids["S1"], id); err != nil {
		t.Fatal(err)
	}
	f.pushes()
	f.svc.repush(t.Context(), p.ID)
	f.wantPushes()
}
