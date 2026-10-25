// Package push sends FCM data messages (docs/protocol.md section 9).
//
// Three Sender implementations exist: firebase (the Firebase Admin SDK, for production), fake
// (records messages in memory for CI smoke tests) and disabled (the default when FCM is not
// configured). Nothing in this package logs tokens, message data or credentials.
package push

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Errors a Sender wraps so callers can react with errors.Is. Any other error is transient.
var (
	// ErrUnregistered means FCM no longer knows the token; the caller clears it.
	ErrUnregistered = errors.New("fcm token is unregistered")
	// ErrPermanent means retrying cannot help: an invalid argument, a sender ID mismatch, a
	// payload over the limit, or FCM not configured.
	ErrPermanent = errors.New("permanent fcm failure")
)

// MaxDataBytes is FCM's limit on a data payload. FCM counts the keys and values of the data
// map; Validate measures the sum of len(key)+len(value) in bytes, the usual rule of thumb. It
// ignores the small fixed overhead FCM adds, so the server should keep a margin on messages it
// builds from user input.
const MaxDataBytes = 4096

// TypeKey is the data field that names the message (`page`, `roster`, `test`, ...).
const TypeKey = "type"

// Priority is the FCM Android delivery priority.
type Priority int

// Priorities. High wakes a dozing phone, so every high-priority message must lead to a visible
// notification change (protocol section 9).
const (
	Normal Priority = iota
	High
)

func (p Priority) String() string {
	if p == High {
		return "high"
	}
	return "normal"
}

// Message is one data-only FCM message to one device.
type Message struct {
	Token    string
	Data     map[string]string
	Priority Priority
	// TTL is how long FCM holds the message for an offline device.
	TTL time.Duration
}

// Validate checks the message against protocol section 9.
func (m Message) Validate() error {
	if m.Token == "" {
		return errors.New("push: message has no token")
	}
	if m.Data[TypeKey] == "" {
		return fmt.Errorf("push: data has no %q field", TypeKey)
	}
	size := 0
	for k, v := range m.Data {
		if reservedKey(k) {
			return fmt.Errorf("push: data key %q is reserved by FCM", k)
		}
		size += len(k) + len(v)
	}
	if size > MaxDataBytes {
		return fmt.Errorf("push: data is %d bytes, over the %d byte FCM limit", size, MaxDataBytes)
	}
	if m.TTL < 0 {
		return errors.New("push: negative TTL")
	}
	return nil
}

func reservedKey(k string) bool {
	switch k {
	case "from", "message_type", "collapse_key":
		return true
	}
	return strings.HasPrefix(k, "google.") || strings.HasPrefix(k, "gcm.")
}

// Sender delivers messages to FCM.
type Sender interface {
	// Send delivers m. Errors wrap ErrUnregistered or ErrPermanent where they apply.
	Send(ctx context.Context, m Message) error
	// ValidateOnly performs a dry-run send that proves the credentials and the FCM API work,
	// without delivering anything.
	ValidateOnly(ctx context.Context) error
}

// Mode names the Sender implementation selected by configuration.
type Mode string

// Modes.
const (
	ModeFirebase Mode = "firebase"
	ModeFake     Mode = "fake"
	ModeDisabled Mode = "disabled"
)

func isUnregistered(err error) bool { return errors.Is(err, ErrUnregistered) }
