package config

import (
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func env(m map[string]string) LookupFunc {
	return func(k string) (string, bool) {
		v, ok := m[k]
		return v, ok
	}
}

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(env(nil))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	want := Config{
		ListenAddr:   ":8080",
		InternalAddr: ":9090",
		DBPath:       "/data/pttd.db",
		SnapshotDir:  "/data/snapshots",
		LogLevel:     slog.LevelInfo,
	}
	if cfg != want {
		t.Errorf("Load() = %+v, want %+v", cfg, want)
	}
}

func TestLoadOverrides(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		EnvListenAddr:   "127.0.0.1:18080",
		EnvInternalAddr: "[::1]:19090",
		EnvDBPath:       "/tmp/x.db",
		EnvSnapshotDir:  " /tmp/snaps ",
		EnvLogLevel:     "DEBUG",
	}))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	want := Config{
		ListenAddr:   "127.0.0.1:18080",
		InternalAddr: "[::1]:19090",
		DBPath:       "/tmp/x.db",
		SnapshotDir:  "/tmp/snaps",
		LogLevel:     slog.LevelDebug,
	}
	if cfg != want {
		t.Errorf("Load() = %+v, want %+v", cfg, want)
	}
}

func TestLoadEmptyMeansDefault(t *testing.T) {
	cfg, err := Load(env(map[string]string{EnvListenAddr: "", EnvLogLevel: "  "}))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ListenAddr != DefaultListenAddr || cfg.LogLevel != slog.LevelInfo {
		t.Errorf("empty values did not fall back to defaults: %+v", cfg)
	}
}

func TestLoadLogLevels(t *testing.T) {
	for in, want := range map[string]slog.Level{
		"debug": slog.LevelDebug, "info": slog.LevelInfo, "warn": slog.LevelWarn,
		"warning": slog.LevelWarn, "Error": slog.LevelError,
	} {
		cfg, err := Load(env(map[string]string{EnvLogLevel: in}))
		if err != nil {
			t.Errorf("Load(%q): %v", in, err)
			continue
		}
		if cfg.LogLevel != want {
			t.Errorf("Load(%q).LogLevel = %v, want %v", in, cfg.LogLevel, want)
		}
	}
}

func TestLoadInvalid(t *testing.T) {
	tests := []struct {
		name    string
		env     map[string]string
		wantErr []string
	}{
		{"no port", map[string]string{EnvListenAddr: "localhost"}, []string{EnvListenAddr, "host:port"}},
		{"bad port", map[string]string{EnvInternalAddr: ":http"}, []string{EnvInternalAddr, "invalid port"}},
		{"port zero", map[string]string{EnvListenAddr: ":0"}, []string{EnvListenAddr, "invalid port"}},
		{"port too big", map[string]string{EnvListenAddr: ":70000"}, []string{EnvListenAddr, "invalid port"}},
		{"same port", map[string]string{EnvListenAddr: ":9090"}, []string{"different ports"}},
		{"query in db path", map[string]string{EnvDBPath: "/data/x.db?mode=ro"}, []string{EnvDBPath, "'?'"}},
		{"log level", map[string]string{EnvLogLevel: "loud"}, []string{EnvLogLevel, `"loud"`}},
		{
			"reports every problem",
			map[string]string{EnvListenAddr: "nope", EnvLogLevel: "loud"},
			[]string{EnvListenAddr, EnvLogLevel},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Load(env(tt.env))
			if err == nil {
				t.Fatal("Load succeeded, want error")
			}
			for _, s := range tt.wantErr {
				if !strings.Contains(err.Error(), s) {
					t.Errorf("error %q does not mention %q", err, s)
				}
			}
		})
	}
}

func TestLoopbackURL(t *testing.T) {
	tests := map[string]string{
		":8080":          "http://127.0.0.1:8080",
		"0.0.0.0:9090":   "http://127.0.0.1:9090",
		"[::]:9090":      "http://127.0.0.1:9090",
		"127.0.0.1:1234": "http://127.0.0.1:1234",
		"[::1]:1234":     "http://[::1]:1234",
		"192.0.2.7:90":   "http://192.0.2.7:90",
	}
	for in, want := range tests {
		got, err := LoopbackURL(in)
		if err != nil {
			t.Errorf("LoopbackURL(%q): %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("LoopbackURL(%q) = %q, want %q", in, got, want)
		}
	}
	if _, err := LoopbackURL("nope"); err == nil {
		t.Error("LoopbackURL(nope) succeeded, want error")
	}
}

func writeFile(t *testing.T, content string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestReadSecret(t *testing.T) {
	tests := []struct {
		name    string
		content string
		want    string
	}{
		{"no newline", "s3cret", "s3cret"},
		{"trailing newline", "s3cret\n", "s3cret"},
		{"trailing CRLF", "s3cret\r\n", "s3cret"},
		{"several trailing newlines", "s3cret\n\n", "s3cret"},
		{"keeps inner newlines and spaces", " {\n  \"k\": 1\n} \n", " {\n  \"k\": 1\n} "},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := writeFile(t, tt.content)
			got, ok, err := ReadSecret(env(map[string]string{"PTTD_X_FILE": p}), "PTTD_X")
			if err != nil || !ok {
				t.Fatalf("ReadSecret = %q, %v, %v; want value, true, nil", got, ok, err)
			}
			if got != tt.want {
				t.Errorf("ReadSecret = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestReadSecretUnset(t *testing.T) {
	for _, e := range []map[string]string{nil, {"PTTD_X_FILE": ""}} {
		got, ok, err := ReadSecret(env(e), "PTTD_X")
		if err != nil || ok || got != "" {
			t.Errorf("ReadSecret(%v) = %q, %v, %v; want \"\", false, nil", e, got, ok, err)
		}
	}
}

func TestReadSecretErrors(t *testing.T) {
	dir := t.TempDir()
	big := filepath.Join(dir, "big")
	if err := os.WriteFile(big, make([]byte, maxSecretFileSize+1), 0o600); err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"value in env", map[string]string{"PTTD_X": "leaky"}, "PTTD_X_FILE instead"},
		{"missing file", map[string]string{"PTTD_X_FILE": filepath.Join(dir, "nope")}, "no such file"},
		{"empty file", map[string]string{"PTTD_X_FILE": writeFile(t, "\n")}, "is empty"},
		{"directory", map[string]string{"PTTD_X_FILE": dir}, "not a regular file"},
		{"too large", map[string]string{"PTTD_X_FILE": big}, "larger than"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, ok, err := ReadSecret(env(tt.env), "PTTD_X")
			if err == nil || ok {
				t.Fatalf("ReadSecret ok=%v err=%v, want error", ok, err)
			}
			if !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("error %q does not contain %q", err, tt.wantErr)
			}
			if strings.Contains(err.Error(), "leaky") {
				t.Errorf("error leaks the secret value: %q", err)
			}
		})
	}
}
