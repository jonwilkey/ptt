package push

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"firebase.google.com/go/v4/messaging"

	"github.com/jonwilkey/ptt/server/internal/config"
)

var quiet = slog.New(slog.DiscardHandler)

func TestValidate(t *testing.T) {
	ok := Message{Token: "t", Data: map[string]string{"type": "test"}}
	if err := ok.Validate(); err != nil {
		t.Fatalf("valid message: %v", err)
	}
	big := map[string]string{"type": "page", "envelope": strings.Repeat("x", MaxDataBytes)}
	atLimit := map[string]string{"type": "page", "envelope": strings.Repeat("x", MaxDataBytes-len("type")-len("page")-len("envelope"))}
	if err := (Message{Token: "t", Data: atLimit}).Validate(); err != nil {
		t.Errorf("exactly at the limit: %v", err)
	}
	for name, m := range map[string]Message{
		"no token":     {Data: ok.Data},
		"no type":      {Token: "t", Data: map[string]string{"a": "b"}},
		"empty type":   {Token: "t", Data: map[string]string{"type": ""}},
		"nil data":     {Token: "t"},
		"from":         {Token: "t", Data: map[string]string{"type": "x", "from": "y"}},
		"message_type": {Token: "t", Data: map[string]string{"type": "x", "message_type": "y"}},
		"collapse_key": {Token: "t", Data: map[string]string{"type": "x", "collapse_key": "y"}},
		"google.":      {Token: "t", Data: map[string]string{"type": "x", "google.a": "y"}},
		"gcm.":         {Token: "t", Data: map[string]string{"type": "x", "gcm.a": "y"}},
		"too big":      {Token: "t", Data: big},
		"negative ttl": {Token: "t", Data: ok.Data, TTL: -1},
	} {
		if err := m.Validate(); err == nil {
			t.Errorf("%s: want error", name)
		}
	}
	// Keys that merely resemble reserved ones are fine.
	if err := (Message{Token: "t", Data: map[string]string{"type": "x", "from_user": "a", "googles": "b"}}).Validate(); err != nil {
		t.Errorf("look-alike keys: %v", err)
	}
}

func TestFakeRecordsAndServes(t *testing.T) {
	f := NewFake()
	if err := f.Send(t.Context(), Message{Token: "tok", Data: map[string]string{"type": "roster", "version": "3"}, Priority: Normal, TTL: RosterTTL}); err != nil {
		t.Fatal(err)
	}
	if err := f.Send(t.Context(), Message{Token: "tok"}); !errors.Is(err, ErrPermanent) {
		t.Errorf("invalid message: %v, want ErrPermanent", err)
	}

	srv := httptest.NewServer(f.Handler())
	defer srv.Close()
	do := func(method string) (int, string) {
		req, _ := http.NewRequestWithContext(t.Context(), method, srv.URL, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = resp.Body.Close() }()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}
	code, body := do(http.MethodGet)
	var got []Recorded
	if err := json.Unmarshal([]byte(body), &got); err != nil || code != 200 {
		t.Fatalf("GET = %d %q (%v)", code, body, err)
	}
	if len(got) != 1 || got[0].Token != "tok" || got[0].Data["version"] != "3" || got[0].Priority != "normal" || got[0].TTLSecs != 86400 {
		t.Errorf("recorded = %+v", got)
	}
	if code, _ := do(http.MethodDelete); code != http.StatusNoContent {
		t.Errorf("DELETE = %d", code)
	}
	if _, body := do(http.MethodGet); strings.TrimSpace(body) != "[]" {
		t.Errorf("after clear = %q", body)
	}
	if code, _ := do(http.MethodPost); code != http.StatusMethodNotAllowed {
		t.Errorf("POST = %d", code)
	}
}

func TestFakeRingIsBounded(t *testing.T) {
	f := NewFake()
	for i := range fakeCapacity + 50 {
		_ = f.Send(t.Context(), Message{Token: string(rune('a' + i%26)), Data: map[string]string{"type": "t", "n": string(rune('0' + i%10))}})
	}
	if n := len(f.Messages()); n != fakeCapacity {
		t.Errorf("kept %d messages, want %d", n, fakeCapacity)
	}
}

func TestDisabled(t *testing.T) {
	var s Sender = Disabled{}
	if err := s.Send(t.Context(), Message{}); !errors.Is(err, ErrPermanent) || !strings.Contains(err.Error(), "FCM not configured") {
		t.Errorf("Send = %v", err)
	}
	if err := s.ValidateOnly(t.Context()); !errors.Is(err, ErrPermanent) {
		t.Errorf("ValidateOnly = %v", err)
	}
}

func TestClassify(t *testing.T) {
	boom := errors.New("boom")
	isBoom := func(err error) bool { return errors.Is(err, boom) }
	never := func(error) bool { return false }

	if classifyWith(nil, isBoom, isBoom) != nil {
		t.Error("nil should stay nil")
	}
	if err := classifyWith(boom, isBoom, never); !errors.Is(err, ErrUnregistered) || !errors.Is(err, boom) || errors.Is(err, ErrPermanent) {
		t.Errorf("unregistered = %v", err)
	}
	if err := classifyWith(boom, never, isBoom); !errors.Is(err, ErrPermanent) || errors.Is(err, ErrUnregistered) {
		t.Errorf("permanent = %v", err)
	}
	if err := classifyWith(boom, never, never); errors.Is(err, ErrPermanent) || errors.Is(err, ErrUnregistered) || !errors.Is(err, boom) {
		t.Errorf("transient = %v", err)
	}
	// The real predicates must not misclassify a plain error.
	if err := classify(boom); errors.Is(err, ErrPermanent) || errors.Is(err, ErrUnregistered) {
		t.Errorf("classify(plain) = %v", err)
	}
}

type stubClient struct {
	sent   []string
	dryRun int
	err    error
}

func (s *stubClient) Send(_ context.Context, m *messaging.Message) (string, error) {
	s.sent = append(s.sent, m.Token) //nolint:staticcheck // SA1019: registration tokens
	return "", s.err
}

func (s *stubClient) SendDryRun(context.Context, *messaging.Message) (string, error) {
	s.dryRun++
	return "", s.err
}

func TestFirebaseSenderUsesClient(t *testing.T) {
	c := &stubClient{}
	f := &Firebase{client: c}
	if err := f.Send(t.Context(), Message{Token: "t", Data: map[string]string{"type": "x"}, Priority: High, TTL: time.Minute}); err != nil || len(c.sent) != 1 {
		t.Fatalf("Send = %v, sent %v", err, c.sent)
	}
	if err := f.Send(t.Context(), Message{Token: "t"}); !errors.Is(err, ErrPermanent) || len(c.sent) != 1 {
		t.Errorf("invalid message: %v, sent %v", err, c.sent)
	}
	if err := f.ValidateOnly(t.Context()); err != nil || c.dryRun != 1 {
		t.Errorf("ValidateOnly = %v, dry runs %d", err, c.dryRun)
	}
	m := toSDK(Message{Token: "t", Data: map[string]string{"type": "x"}, Priority: High, TTL: time.Minute})
	if m.Android.Priority != "high" || *m.Android.TTL != time.Minute || m.Notification != nil {
		t.Errorf("sdk message = %+v", m)
	}
	if toSDK(Message{}).Android.Priority != "normal" {
		t.Error("default priority should be normal")
	}
}

func lookupOf(vars map[string]string) config.LookupFunc {
	return func(k string) (string, bool) { v, ok := vars[k]; return v, ok }
}

func serviceAccountFile(t *testing.T) string {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(map[string]string{
		"type":           "service_account",
		"project_id":     "example-ptt",
		"private_key_id": "0123456789abcdef",
		"private_key":    string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})),
		"client_email":   "sender@example-ptt.iam.gserviceaccount.com",
		"client_id":      "1",
		"token_uri":      "https://oauth2.example.invalid/token",
	})
	p := filepath.Join(t.TempDir(), "sa.json")
	if err := os.WriteFile(p, b, 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestFromEnv(t *testing.T) {
	cred := serviceAccountFile(t)
	credVar := config.EnvFCMCredentials + "_FILE"
	cases := []struct {
		name    string
		vars    map[string]string
		mode    Mode
		wantErr string
	}{
		{"neither", nil, ModeDisabled, ""},
		{"empty credentials is unset", map[string]string{credVar: ""}, ModeDisabled, ""},
		{"fake false", map[string]string{config.EnvFCMFake: "false"}, ModeDisabled, ""},
		{"fake", map[string]string{config.EnvFCMFake: "true"}, ModeFake, ""},
		{"firebase", map[string]string{credVar: cred}, ModeFirebase, ""},
		{"both", map[string]string{credVar: cred, config.EnvFCMFake: "true"}, "", "both set"},
		{"fake false with credentials", map[string]string{credVar: cred, config.EnvFCMFake: "false"}, ModeFirebase, ""},
		{"bad bool", map[string]string{config.EnvFCMFake: "maybe"}, "", "not a boolean"},
		{"missing file", map[string]string{credVar: "/nonexistent/sa.json"}, "", credVar},
		{"inline secret", map[string]string{config.EnvFCMCredentials: "{}"}, "", "_FILE"},
		{"garbage credentials", map[string]string{credVar: writeTemp(t, "not json")}, "", credVar},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s, fake, mode, err := FromEnv(t.Context(), lookupOf(c.vars), quiet)
			if c.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), c.wantErr) {
					t.Fatalf("err = %v, want %q", err, c.wantErr)
				}
				return
			}
			if err != nil || mode != c.mode || s == nil {
				t.Fatalf("FromEnv = %v, %v, %v", s, mode, err)
			}
			if (fake != nil) != (mode == ModeFake) {
				t.Errorf("fake = %v in mode %s", fake, mode)
			}
		})
	}
}

func writeTemp(t *testing.T, content string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "f")
	if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}
