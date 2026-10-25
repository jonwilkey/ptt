package cli

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"text/tabwriter"
	"time"

	"github.com/jonwilkey/ptt/server/internal/admin"
	"github.com/jonwilkey/ptt/server/internal/adminops"
	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/push"
)

// newFlagSet returns a flag set whose errors are routed through Run's usage handling.
func newFlagSet(name string, env Env) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(env.Stderr)
	return fs
}

// parseFlags parses args and requires exactly nargs positional arguments.
func parseFlags(fs *flag.FlagSet, args []string, nargs int, argUsage string) error {
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return err
		}
		return usageErr("%v", err)
	}
	if fs.NArg() != nargs {
		return usageErr("%s needs %s", fs.Name(), argUsage)
	}
	return nil
}

// openAdminDB opens the database the running server has already created and migrated.
func openAdminDB(ctx context.Context, cfg config.Config) (*sql.DB, error) {
	return db.OpenExisting(ctx, cfg.DBPath)
}

func adminBootstrap(ctx context.Context, args []string, env Env) error {
	fs := newFlagSet("admin bootstrap", env)
	reset := fs.Bool("reset", false, "revoke every device and delete the roster before minting the code")
	if err := parseFlags(fs, args, 1, "exactly one <name> argument"); err != nil {
		return err
	}
	name := fs.Arg(0)

	cfg, err := config.Load(env.Lookup)
	if err != nil {
		return err
	}
	if cfg.PublicURL == "" {
		return fmt.Errorf("admin bootstrap: %s must be set; it is the address the QR sends phones to", config.EnvPublicURL)
	}
	d, err := openAdminDB(ctx, cfg)
	if err != nil {
		return err
	}
	defer func() { _ = d.Close() }()

	code, err := adminops.Bootstrap(ctx, d, name, *reset, env.Now())
	if err != nil {
		return fmt.Errorf("admin bootstrap: %w", err)
	}

	qrText, err := admin.BootstrapQRText(cfg.PublicURL, code)
	if err != nil {
		return err
	}

	if *reset {
		_, _ = fmt.Fprintln(env.Stderr, "WARNING: --reset revoked every device and deleted the roster; the whole family must enroll again.")
	}
	_, _ = fmt.Fprintf(env.Stdout, "Enrollment code for %s (valid %s, single use):\n\n%s\n\n", name, adminops.CodeTTL, qrText)
	return admin.PrintQR(env.Stdout, qrText)
}

func adminDevices(ctx context.Context, args []string, env Env) error {
	fs := newFlagSet("admin devices", env)
	asJSON := fs.Bool("json", false, "print the devices as JSON")
	if err := parseFlags(fs, args, 0, "no positional arguments"); err != nil {
		return err
	}

	cfg, err := config.Load(env.Lookup)
	if err != nil {
		return err
	}
	d, err := openAdminDB(ctx, cfg)
	if err != nil {
		return err
	}
	defer func() { _ = d.Close() }()
	devices, err := adminops.ListDevices(ctx, d)
	if err != nil {
		return err
	}

	if *asJSON {
		enc := json.NewEncoder(env.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(devices)
	}
	tw := tabwriter.NewWriter(env.Stdout, 0, 4, 2, ' ', 0)
	_, _ = fmt.Fprintln(tw, "ID\tPERSON\tADMIN\tNAME\tVERSION\tLAST SEEN\tREVOKED")
	for _, v := range devices {
		name := v.Name
		if v.IsTest {
			name += " (test)"
		}
		_, _ = fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
			v.ID, v.Person, yesNo(v.Admin), name, dash(v.Version), when(v.LastSeenAt), when(v.RevokedAt))
	}
	return tw.Flush()
}

func adminRevoke(ctx context.Context, args []string, env Env) error {
	fs := newFlagSet("admin revoke", env)
	if err := parseFlags(fs, args, 1, "exactly one <device-id> argument"); err != nil {
		return err
	}
	id := fs.Arg(0)

	cfg, err := config.Load(env.Lookup)
	if err != nil {
		return err
	}
	d, err := openAdminDB(ctx, cfg)
	if err != nil {
		return err
	}
	defer func() { _ = d.Close() }()

	changed, err := adminops.Revoke(ctx, d, id, env.Now())
	if err != nil {
		return fmt.Errorf("admin revoke: %w", err)
	}
	if changed {
		_, err = fmt.Fprintf(env.Stdout, "Revoked device %s.\n", id)
	} else {
		_, err = fmt.Fprintf(env.Stdout, "Device %s was already revoked.\n", id)
	}
	return err
}

func yesNo(b bool) string {
	if b {
		return "yes"
	}
	return "no"
}

func dash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func when(t *time.Time) string {
	if t == nil {
		return "-"
	}
	return t.UTC().Format(time.RFC3339)
}

// testPushTTL is the FCM time-to-live of the `test` message (protocol section 9).
const testPushTTL = 15 * time.Minute

func adminTestPush(ctx context.Context, args []string, env Env) error {
	fs := newFlagSet("admin test-push", env)
	if err := parseFlags(fs, args, 1, "exactly one <device-id> argument"); err != nil {
		return err
	}
	id := fs.Arg(0)

	cfg, err := config.Load(env.Lookup)
	if err != nil {
		return err
	}
	d, err := openAdminDB(ctx, cfg)
	if err != nil {
		return err
	}
	defer func() { _ = d.Close() }()

	token, err := adminops.PushToken(ctx, d, id)
	if err != nil {
		return fmt.Errorf("admin test-push: %w", err)
	}
	sender, fake, mode, err := push.FromEnv(ctx, env.Lookup, slog.New(slog.DiscardHandler))
	if err != nil {
		return err
	}
	if mode == push.ModeDisabled {
		return errors.New("admin test-push: FCM is not configured; set PTTD_FCM_CREDENTIALS_FILE (see docs/runbooks/firebase-setup.md)")
	}

	msg := push.Message{
		Token:    token,
		Data:     map[string]string{push.TypeKey: "test"},
		Priority: push.High,
		TTL:      testPushTTL,
	}
	if err := sender.Send(ctx, msg); err != nil {
		// The error text never contains the token.
		return fmt.Errorf("admin test-push: %w", err)
	}
	if fake != nil {
		// Nothing was sent; show what would have been, without the token.
		_, _ = fmt.Fprintf(env.Stdout, "FCM fake mode: would send to device %s: type=test priority=%s ttl=%s\n",
			id, msg.Priority, msg.TTL)
		return nil
	}
	_, err = fmt.Fprintf(env.Stdout, "Sent a test push to device %s.\n", id)
	return err
}
