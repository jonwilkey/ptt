//go:build drill

package app

import (
	"net/http"
	"testing"
)

func TestDrillHealthzUnavailableOnceMigrated(t *testing.T) {
	a, _ := newApp(t, true)
	code, body := get(t, a.PublicHandler(), "/healthz")
	if code != http.StatusServiceUnavailable || body["status"] != "unavailable" || body["reason"] != "drill" {
		t.Fatalf("drill build: %d %v", code, body)
	}
}
