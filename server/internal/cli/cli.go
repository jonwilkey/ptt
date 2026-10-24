// Package cli implements pttd's command line: serve, healthcheck, migrate, admin, version.
package cli

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"text/tabwriter"
	"time"

	"github.com/jonwilkey/ptt/server/internal/admin"
	"github.com/jonwilkey/ptt/server/internal/app"
	"github.com/jonwilkey/ptt/server/internal/config"
	"github.com/jonwilkey/ptt/server/internal/db"
)

const usage = `Usage: pttd <command> [arguments]

Commands:
  serve                    Run the server (default when no command is given)
  healthcheck              Exit 0 if the local server's /healthz returns 200 (container HEALTHCHECK)
  migrate up|down|status   Manage the schema of PTTD_DB_PATH (down rolls back one migration)
  admin status [--json]    Summarize the running server via its internal listener
  version                  Print the version

Configuration is read from PTTD_* environment variables; see server/README.md.
`

// Env bundles the process environment so tests can run commands in isolation.
type Env struct {
	Version string
	Lookup  config.LookupFunc
	Stdout  io.Writer
	Stderr  io.Writer
	// HTTPClient is used by healthcheck and admin; defaults to a client with a short timeout.
	HTTPClient *http.Client
	// Now defaults to time.Now; it names snapshots.
	Now func() time.Time
}

const healthcheckTimeout = 4 * time.Second

// errUsage marks errors caused by bad arguments; they exit 2 rather than 1.
var errUsage = errors.New("usage")

// loggedError is an error already written to the JSON log, so Run does not print it again.
type loggedError struct{ error }

func (e loggedError) Unwrap() error { return e.error }

// Run executes the command in args (without the program name) and returns the exit code.
func Run(ctx context.Context, args []string, env Env) int {
	if env.HTTPClient == nil {
		env.HTTPClient = &http.Client{Timeout: 5 * time.Second}
	}
	if env.Now == nil {
		env.Now = time.Now
	}

	err := dispatch(ctx, args, env)
	switch {
	case err == nil:
		return 0
	case errors.Is(err, flag.ErrHelp):
		return 0
	case errors.Is(err, errUsage):
		_, _ = fmt.Fprintf(env.Stderr, "pttd: %v\n\n%s", err, usage)
		return 2
	case errors.As(err, new(loggedError)):
		return 1
	default:
		_, _ = fmt.Fprintf(env.Stderr, "pttd: %v\n", err)
		return 1
	}
}

func usageErr(format string, a ...any) error {
	return fmt.Errorf("%w: %s", errUsage, fmt.Sprintf(format, a...))
}

func dispatch(ctx context.Context, args []string, env Env) error {
	if len(args) == 0 {
		return serve(ctx, nil, env)
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "serve":
		return serve(ctx, rest, env)
	case "healthcheck":
		return healthcheck(ctx, rest, env)
	case "migrate":
		return migrate(ctx, rest, env)
	case "admin":
		return adminCmd(ctx, rest, env)
	case "version", "--version":
		return noArgs("version", rest, func() error {
			_, err := fmt.Fprintln(env.Stdout, env.Version)
			return err
		})
	case "help", "-h", "-help", "--help":
		_, _ = io.WriteString(env.Stdout, usage)
		return nil
	default:
		return usageErr("unknown command %q", cmd)
	}
}

func noArgs(cmd string, args []string, f func() error) error {
	if len(args) > 0 {
		return usageErr("%s takes no arguments", cmd)
	}
	return f()
}

func newLogger(w io.Writer, level slog.Level) *slog.Logger {
	return slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: level}))
}

func serve(ctx context.Context, args []string, env Env) error {
	return noArgs("serve", args, func() error {
		cfg, err := config.Load(env.Lookup)
		if err != nil {
			return err
		}
		logger := newLogger(env.Stderr, cfg.LogLevel)
		if err := app.Serve(ctx, cfg, logger, env.Version); err != nil {
			logger.Error("pttd exited", slog.String("error", err.Error()))
			return loggedError{err}
		}
		return nil
	})
}

func healthcheck(ctx context.Context, args []string, env Env) error {
	return noArgs("healthcheck", args, func() error {
		cfg, err := config.Load(env.Lookup)
		if err != nil {
			return err
		}
		base, err := config.LoopbackURL(cfg.ListenAddr)
		if err != nil {
			return err
		}
		// Under the image's 5 s HEALTHCHECK timeout, so a hung server reports failure itself.
		ctx, cancel := context.WithTimeout(ctx, healthcheckTimeout)
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/healthz", nil)
		if err != nil {
			return err
		}
		resp, err := env.HTTPClient.Do(req)
		if err != nil {
			return fmt.Errorf("healthcheck: %w", err)
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("healthcheck: /healthz returned HTTP %d", resp.StatusCode)
		}
		return nil
	})
}

func migrate(ctx context.Context, args []string, env Env) error {
	if len(args) != 1 {
		return usageErr("migrate needs exactly one of: up, down, status")
	}
	action := args[0]
	if action != "up" && action != "down" && action != "status" {
		return usageErr("unknown migrate action %q", action)
	}

	cfg, err := config.Load(env.Lookup)
	if err != nil {
		return err
	}
	logger := newLogger(env.Stderr, cfg.LogLevel)
	openDB := db.Open
	if action != "up" {
		// Inspecting or rolling back must not conjure an empty database at a mistyped path.
		openDB = db.OpenExisting
	}
	sqlDB, err := openDB(ctx, cfg.DBPath)
	if err != nil {
		return err
	}
	defer func() { _ = sqlDB.Close() }()
	m, err := db.NewMigrator(sqlDB, logger)
	if err != nil {
		return err
	}

	switch action {
	case "up":
		_, err := m.MigrateUp(ctx, cfg.SnapshotDir, env.Now())
		return err
	case "down":
		// Down migrations drop data; keep a copy, same as before an upgrade.
		current, _, err := m.Versions(ctx)
		if err != nil {
			return err
		}
		if current == 0 {
			return errors.New("migrate down: no migrations are applied")
		}
		if _, err := m.SnapshotFresh(ctx, cfg.SnapshotDir, env.Now()); err != nil {
			return err
		}
		return m.Down(ctx)
	default:
		return printMigrationStatus(ctx, env.Stdout, m)
	}
}

func printMigrationStatus(ctx context.Context, w io.Writer, m *db.Migrator) error {
	statuses, err := m.Status(ctx)
	if err != nil {
		return err
	}
	tw := tabwriter.NewWriter(w, 0, 4, 2, ' ', 0)
	_, _ = fmt.Fprintln(tw, "VERSION\tSTATE\tAPPLIED AT (UTC)\tFILE")
	for _, s := range statuses {
		applied := "-"
		if !s.AppliedAt.IsZero() {
			applied = s.AppliedAt.UTC().Format(time.RFC3339)
		}
		_, _ = fmt.Fprintf(tw, "%d\t%s\t%s\t%s\n", s.Source.Version, s.State, applied, s.Source.Path)
	}
	return tw.Flush()
}

func adminCmd(ctx context.Context, args []string, env Env) error {
	if len(args) == 0 {
		return usageErr("admin needs a subcommand: status")
	}
	switch args[0] {
	case "status":
		return adminStatus(ctx, args[1:], env)
	default:
		return usageErr("unknown admin subcommand %q", args[0])
	}
}

func adminStatus(ctx context.Context, args []string, env Env) error {
	fs := flag.NewFlagSet("admin status", flag.ContinueOnError)
	fs.SetOutput(env.Stderr)
	asJSON := fs.Bool("json", false, "print the raw status as JSON")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return err
		}
		return usageErr("%v", err)
	}
	if fs.NArg() > 0 {
		return usageErr("admin status takes no positional arguments")
	}

	cfg, err := config.Load(env.Lookup)
	if err != nil {
		return err
	}
	base, err := config.LoopbackURL(cfg.InternalAddr)
	if err != nil {
		return err
	}
	s, err := admin.FetchStatus(ctx, env.HTTPClient, base)
	if err != nil {
		return err
	}
	if *asJSON {
		return admin.PrintStatusJSON(env.Stdout, s)
	}
	return admin.PrintStatus(env.Stdout, s)
}
