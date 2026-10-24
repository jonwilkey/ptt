// Command pttd is the family push-to-talk server. Run `pttd help` for its subcommands.
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"github.com/jonwilkey/ptt/server/internal/cli"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	code := cli.Run(ctx, os.Args[1:], cli.Env{
		Version: version,
		Lookup:  os.LookupEnv,
		Stdout:  os.Stdout,
		Stderr:  os.Stderr,
	})
	stop()
	os.Exit(code)
}
