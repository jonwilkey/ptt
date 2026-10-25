package push

import (
	"context"
	"fmt"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

// validateTopic is the target of the dry-run send. validate_only never delivers, and a topic
// target needs no real device token.
const validateTopic = "ptt-validate"

type fcmClient interface {
	Send(ctx context.Context, m *messaging.Message) (string, error)
	SendDryRun(ctx context.Context, m *messaging.Message) (string, error)
}

// Firebase sends through the FCM HTTP v1 API using the Firebase Admin SDK.
type Firebase struct {
	client fcmClient
}

// NewFirebase builds a Firebase sender from service-account JSON. It makes no network call.
func NewFirebase(ctx context.Context, raw []byte) (*Firebase, error) {
	app, err := firebase.NewApp(ctx, nil, option.WithAuthCredentialsJSON(option.ServiceAccount, raw))
	if err != nil {
		return nil, fmt.Errorf("initialize firebase: %w", err)
	}
	c, err := app.Messaging(ctx)
	if err != nil {
		return nil, fmt.Errorf("initialize fcm client: %w", err)
	}
	return &Firebase{client: c}, nil
}

// Send implements Sender.
func (f *Firebase) Send(ctx context.Context, m Message) error {
	if err := m.Validate(); err != nil {
		return fmt.Errorf("%w: %w", ErrPermanent, err)
	}
	_, err := f.client.Send(ctx, toSDK(m))
	return classify(err)
}

// ValidateOnly implements Sender.
func (f *Firebase) ValidateOnly(ctx context.Context) error {
	_, err := f.client.SendDryRun(ctx, &messaging.Message{
		Topic: validateTopic,
		Data:  map[string]string{TypeKey: "validate"},
	})
	return classify(err)
}

func toSDK(m Message) *messaging.Message {
	ttl := m.TTL
	prio := "normal"
	if m.Priority == High {
		prio = "high"
	}
	return &messaging.Message{
		// The SDK deprecates Token in favor of installation IDs (Fid); the app registers FCM
		// registration tokens, which Token still carries.
		Token:   m.Token, //nolint:staticcheck // SA1019: see above
		Data:    m.Data,
		Android: &messaging.AndroidConfig{Priority: prio, TTL: &ttl},
	}
}

// classify maps an SDK error onto this package's sentinels. The SDK error is kept in the chain
// for its message; it never contains the token or the payload.
func classify(err error) error {
	return classifyWith(err, messaging.IsUnregistered, func(err error) bool {
		return messaging.IsInvalidArgument(err) || messaging.IsSenderIDMismatch(err) ||
			messaging.IsThirdPartyAuthError(err)
	})
}

func classifyWith(err error, unregistered, permanent func(error) bool) error {
	switch {
	case err == nil:
		return nil
	case unregistered(err):
		return fmt.Errorf("%w: %w", ErrUnregistered, err)
	case permanent(err):
		return fmt.Errorf("%w: %w", ErrPermanent, err)
	default:
		return err
	}
}
