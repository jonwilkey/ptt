package push

import (
	"context"
	"fmt"
)

// Disabled is the Sender used when FCM is not configured: every send fails permanently.
type Disabled struct{}

var errNotConfigured = fmt.Errorf("%w: FCM not configured", ErrPermanent)

// Send implements Sender.
func (Disabled) Send(context.Context, Message) error { return errNotConfigured }

// ValidateOnly implements Sender.
func (Disabled) ValidateOnly(context.Context) error { return errNotConfigured }
