package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadHealthchecks(t *testing.T) {
	keyFile := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(keyFile, []byte("abc123\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		name    string
		env     map[string]string
		want    Healthchecks
		wantErr string
	}{
		{"unset", nil, Healthchecks{BaseURL: "https://hc-ping.com"}, ""},
		{"empty path counts as unset", map[string]string{"PTTD_HEALTHCHECKS_PING_KEY_FILE": ""}, Healthchecks{BaseURL: "https://hc-ping.com"}, ""},
		{"key file", map[string]string{"PTTD_HEALTHCHECKS_PING_KEY_FILE": keyFile}, Healthchecks{PingKey: "abc123", BaseURL: "https://hc-ping.com"}, ""},
		{"custom url, slash trimmed", map[string]string{"PTTD_HEALTHCHECKS_URL": "http://127.0.0.1:9/"}, Healthchecks{BaseURL: "http://127.0.0.1:9"}, ""},
		{"direct key is an error", map[string]string{"PTTD_HEALTHCHECKS_PING_KEY": "abc"}, Healthchecks{}, "PTTD_HEALTHCHECKS_PING_KEY_FILE"},
		{"missing key file", map[string]string{"PTTD_HEALTHCHECKS_PING_KEY_FILE": "/nonexistent/key"}, Healthchecks{}, "PTTD_HEALTHCHECKS_PING_KEY_FILE"},
		{"bad url", map[string]string{"PTTD_HEALTHCHECKS_URL": "hc-ping.com"}, Healthchecks{}, "PTTD_HEALTHCHECKS_URL"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := LoadHealthchecks(env(tt.env))
			if tt.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
					t.Fatalf("err = %v, want mention of %s", err, tt.wantErr)
				}
				return
			}
			if err != nil || got != tt.want {
				t.Errorf("got %+v, %v; want %+v", got, err, tt.want)
			}
		})
	}
}
