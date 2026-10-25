package auth

import (
	"bytes"
	"strings"
	"testing"
)

func TestNewTokenRoundTrip(t *testing.T) {
	tests := []struct {
		name    string
		gen     func() (string, []byte, error)
		hash    func(string) ([]byte, error)
		wantLen int
	}{
		{"token", NewToken, HashToken, 43},
		{"enrollment code", NewEnrollmentCode, HashEnrollmentCode, 22},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			s1, h1, err := tt.gen()
			if err != nil {
				t.Fatal(err)
			}
			s2, _, err := tt.gen()
			if err != nil {
				t.Fatal(err)
			}
			if len(s1) != tt.wantLen || len(h1) != 32 {
				t.Errorf("len(secret)=%d len(hash)=%d, want %d and 32", len(s1), len(h1), tt.wantLen)
			}
			if s1 == s2 {
				t.Error("two secrets were identical")
			}
			got, err := tt.hash(s1)
			if err != nil || !bytes.Equal(got, h1) {
				t.Errorf("hash(secret) = %x, %v; want %x", got, err, h1)
			}
		})
	}
}

func TestHashRejectsMalformed(t *testing.T) {
	tok, _, _ := NewToken()
	code, _, _ := NewEnrollmentCode()
	tests := []struct {
		name string
		f    func(string) ([]byte, error)
		in   string
	}{
		{"empty token", HashToken, ""},
		{"short token", HashToken, tok[:42]},
		{"long token", HashToken, tok + "A"},
		{"padded token", HashToken, tok + "="},
		{"standard alphabet", HashToken, "+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+"},
		{"code given as token", HashToken, code},
		{"token given as code", HashEnrollmentCode, tok},
		{"empty code", HashEnrollmentCode, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := tt.f(tt.in); err == nil {
				t.Errorf("accepted %q", tt.in)
			}
		})
	}
}

func TestCodeID(t *testing.T) {
	_, h, err := NewEnrollmentCode()
	if err != nil {
		t.Fatal(err)
	}
	if id := CodeID(h); len(id) != 43 {
		t.Errorf("CodeID length = %d, want 43", len(id))
	}
}

func TestEqual(t *testing.T) {
	if !Equal([]byte{1, 2}, []byte{1, 2}) || Equal([]byte{1, 2}, []byte{1, 3}) || Equal([]byte{1}, []byte{1, 2}) {
		t.Error("Equal gave a wrong answer")
	}
}

func TestDecodingIsStrict(t *testing.T) {
	// 32 bytes encode to 43 characters whose last one carries 4 spare bits; a non-zero value
	// there is a second spelling of the same bytes and must be rejected.
	if _, err := HashToken(strings.Repeat("A", 42) + "B"); err == nil {
		t.Error("non-canonical trailing bits accepted")
	}
	if _, err := HashToken(strings.Repeat("A", 43)); err != nil {
		t.Errorf("canonical token rejected: %v", err)
	}
}
