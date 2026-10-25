package push

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"
	"strings"

	"github.com/jonwilkey/ptt/server/internal/config"
)

// FromEnv selects the Sender from configuration: PTTD_FCM_CREDENTIALS_FILE selects firebase,
// PTTD_FCM_FAKE=true selects fake (setting both is an error), neither selects disabled. The
// returned *Fake is non-nil only in fake mode.
func FromEnv(ctx context.Context, lookup config.LookupFunc, logger *slog.Logger) (Sender, *Fake, Mode, error) {
	fake := false
	if v, ok := lookup(config.EnvFCMFake); ok && strings.TrimSpace(v) != "" {
		b, err := strconv.ParseBool(strings.TrimSpace(v))
		if err != nil {
			return nil, nil, "", fmt.Errorf("%s: %q is not a boolean", config.EnvFCMFake, v)
		}
		fake = b
	}
	creds, haveCreds, err := config.ReadSecret(lookup, config.EnvFCMCredentials)
	if err != nil {
		return nil, nil, "", err
	}

	switch {
	case fake && haveCreds:
		return nil, nil, "", fmt.Errorf("%s and %s_FILE are both set; choose the fake sender or real credentials, not both",
			config.EnvFCMFake, config.EnvFCMCredentials)
	case fake:
		logger.Info("FCM fake sender enabled; no push leaves this process")
		f := NewFake()
		return f, f, ModeFake, nil
	case haveCreds:
		s, err := NewFirebase(ctx, []byte(creds))
		if err != nil {
			return nil, nil, "", fmt.Errorf("%s_FILE: %w", config.EnvFCMCredentials, err)
		}
		return s, nil, ModeFirebase, nil
	default:
		logger.Warn("FCM is not configured; pushes will fail until " + config.EnvFCMCredentials + "_FILE is set")
		return Disabled{}, nil, ModeDisabled, nil
	}
}
