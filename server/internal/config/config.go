// Package config loads pttd's configuration from environment variables.
//
// Plain settings come from PTTD_* variables. Secrets never travel in the environment (it shows
// up in `docker inspect` and crash dumps); they are read from files named by <NAME>_FILE, which
// compose mounts read-only.
package config

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Environment variable names.
const (
	EnvListenAddr   = "PTTD_LISTEN_ADDR"
	EnvInternalAddr = "PTTD_INTERNAL_ADDR"
	EnvDBPath       = "PTTD_DB_PATH"
	EnvSnapshotDir  = "PTTD_SNAPSHOT_DIR"
	EnvLogLevel     = "PTTD_LOG_LEVEL"
)

// Defaults match the container layout: Caddy proxies to :8080, :9090 is published only on the
// host's loopback/tailnet, and /data is the only writable path.
const (
	DefaultListenAddr   = ":8080"
	DefaultInternalAddr = ":9090"
	DefaultDBPath       = "/data/pttd.db"
	DefaultSnapshotDir  = "/data/snapshots"
	DefaultLogLevel     = "info"
)

// maxSecretFileSize bounds secret reads so a mis-pointed _FILE variable (say, at a disk image)
// fails fast instead of being slurped into memory.
const maxSecretFileSize = 64 << 10

// LookupFunc has the signature of os.LookupEnv; tests pass a map-backed fake.
type LookupFunc func(key string) (string, bool)

// Config is pttd's runtime configuration.
type Config struct {
	// ListenAddr is the public listener (behind Caddy).
	ListenAddr string
	// InternalAddr serves /metrics and /internal/status; never exposed through Caddy.
	InternalAddr string
	DBPath       string
	SnapshotDir  string
	LogLevel     slog.Level
}

// Load reads and validates the configuration. Unset or empty variables take their defaults.
// All problems are reported together so a misconfigured deploy fails with one clear message.
func Load(lookup LookupFunc) (Config, error) {
	get := func(key, def string) string {
		if v, ok := lookup(key); ok && strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
		return def
	}

	cfg := Config{
		ListenAddr:   get(EnvListenAddr, DefaultListenAddr),
		InternalAddr: get(EnvInternalAddr, DefaultInternalAddr),
		DBPath:       get(EnvDBPath, DefaultDBPath),
		SnapshotDir:  get(EnvSnapshotDir, DefaultSnapshotDir),
	}

	var errs []error
	if err := validateAddr(cfg.ListenAddr); err != nil {
		errs = append(errs, fmt.Errorf("%s: %w", EnvListenAddr, err))
	}
	if err := validateAddr(cfg.InternalAddr); err != nil {
		errs = append(errs, fmt.Errorf("%s: %w", EnvInternalAddr, err))
	}
	if len(errs) == 0 && samePort(cfg.ListenAddr, cfg.InternalAddr) {
		errs = append(errs, fmt.Errorf("%s and %s must use different ports (both %q)",
			EnvListenAddr, EnvInternalAddr, cfg.ListenAddr))
	}
	// modernc.org/sqlite treats everything after '?' as driver options.
	if strings.Contains(cfg.DBPath, "?") {
		errs = append(errs, fmt.Errorf("%s: path %q must not contain '?'", EnvDBPath, cfg.DBPath))
	}

	level, err := parseLogLevel(get(EnvLogLevel, DefaultLogLevel))
	if err != nil {
		errs = append(errs, fmt.Errorf("%s: %w", EnvLogLevel, err))
	}
	cfg.LogLevel = level

	if len(errs) > 0 {
		return Config{}, fmt.Errorf("invalid configuration: %w", errors.Join(errs...))
	}
	return cfg, nil
}

func validateAddr(addr string) error {
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("%q is not host:port: %w", addr, err)
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return fmt.Errorf("%q has invalid port %q (want 1-65535)", addr, port)
	}
	return nil
}

func samePort(a, b string) bool {
	_, pa, _ := net.SplitHostPort(a)
	_, pb, _ := net.SplitHostPort(b)
	return pa == pb
}

func parseLogLevel(s string) (slog.Level, error) {
	switch strings.ToLower(s) {
	case "debug":
		return slog.LevelDebug, nil
	case "info":
		return slog.LevelInfo, nil
	case "warn", "warning":
		return slog.LevelWarn, nil
	case "error":
		return slog.LevelError, nil
	default:
		return 0, fmt.Errorf("unknown log level %q (want debug, info, warn, or error)", s)
	}
}

// LoopbackURL turns a listen address such as ":8080" or "0.0.0.0:9090" into a base URL a
// process on the same host (or in the same container) can dial, e.g. "http://127.0.0.1:8080".
func LoopbackURL(listenAddr string) (string, error) {
	host, port, err := net.SplitHostPort(listenAddr)
	if err != nil {
		return "", fmt.Errorf("parse listen address %q: %w", listenAddr, err)
	}
	if ip := net.ParseIP(host); host == "" || (ip != nil && ip.IsUnspecified()) {
		host = "127.0.0.1"
	}
	return "http://" + net.JoinHostPort(host, port), nil
}

// ReadSecret returns the secret named by <name>_FILE, read from that file with trailing newlines
// removed. ok is false when <name>_FILE is unset, so callers decide whether the secret is
// required. Setting <name> directly is rejected: secrets must come from files.
func ReadSecret(lookup LookupFunc, name string) (value string, ok bool, err error) {
	if _, set := lookup(name); set {
		return "", false, fmt.Errorf("%s is set in the environment; put the secret in a file and set %s_FILE instead", name, name)
	}
	path, set := lookup(name + "_FILE")
	if !set || path == "" {
		return "", false, nil
	}

	b, err := readLimited(path, maxSecretFileSize)
	if err != nil {
		return "", false, fmt.Errorf("%s_FILE: %w", name, err)
	}
	value = strings.TrimRight(string(b), "\r\n")
	if value == "" {
		return "", false, fmt.Errorf("%s_FILE: %s is empty", name, path)
	}
	return value, true, nil
}

func readLimited(path string, limit int64) ([]byte, error) {
	// The path is operator configuration, not request input.
	f, err := os.Open(filepath.Clean(path))
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()

	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("%s is not a regular file", path)
	}
	b, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	if int64(len(b)) > limit {
		return nil, fmt.Errorf("%s is larger than %d bytes", path, limit)
	}
	return b, nil
}
