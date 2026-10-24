// Package admin implements the `pttd admin` subcommands, which talk to a running pttd over its
// internal listener.
package admin

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/jonwilkey/ptt/server/internal/httpapi"
)

// FetchStatus GETs /internal/status from the pttd internal listener at baseURL.
func FetchStatus(ctx context.Context, client *http.Client, baseURL string) (*httpapi.Status, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimSuffix(baseURL, "/")+"/internal/status", nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("query pttd status: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("read pttd status: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("pttd status: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var s httpapi.Status
	if err := json.Unmarshal(body, &s); err != nil {
		return nil, fmt.Errorf("decode pttd status: %w", err)
	}
	return &s, nil
}

// PrintStatus writes a one-screen, human-readable summary of s.
func PrintStatus(w io.Writer, s *httpapi.Status) error {
	db := "ok"
	if !s.DBOK {
		db = "ERROR (see pttd logs)"
	}
	rows := [][2]string{
		{"version", s.Version},
		{"uptime", formatUptime(time.Duration(s.UptimeSeconds) * time.Second)},
		{"database", db},
		{"schema version", schemaLine(s)},
		{"active sessions", fmt.Sprint(s.ActiveSessions)},
		{"active pages", fmt.Sprint(s.ActivePages)},
	}
	for _, r := range rows {
		if _, err := fmt.Fprintf(w, "%-16s %s\n", r[0]+":", r[1]); err != nil {
			return err
		}
	}
	return nil
}

// PrintStatusJSON writes s as indented JSON.
func PrintStatusJSON(w io.Writer, s *httpapi.Status) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(s)
}

func formatUptime(d time.Duration) string {
	days := d / (24 * time.Hour)
	d -= days * 24 * time.Hour
	if days > 0 {
		return fmt.Sprintf("%dd %s", days, d.Truncate(time.Minute))
	}
	return d.String()
}

func schemaLine(s *httpapi.Status) string {
	switch {
	case s.BinarySchemaVersion == 0 || s.SchemaVersion == s.BinarySchemaVersion:
		return fmt.Sprint(s.SchemaVersion)
	case s.SchemaVersion < s.BinarySchemaVersion:
		return fmt.Sprintf("%d (BEHIND binary's %d)", s.SchemaVersion, s.BinarySchemaVersion)
	default:
		return fmt.Sprintf("%d (newer than binary's %d; rollback mode)", s.SchemaVersion, s.BinarySchemaVersion)
	}
}
