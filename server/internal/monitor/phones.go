package monitor

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"
	"unicode"
)

const (
	// SilentAfter is how long a phone may go without reaching the server.
	SilentAfter = 48 * time.Hour
	// RestartWindow is how long no phone may have reached the server before a startup counts as
	// the server's own outage; silence is then measured from the process start.
	RestartWindow = 24 * time.Hour
	// MaxBody is the size cap of the ptt-phones ping body.
	MaxBody = 2048

	reasonSilent    = "silent"
	reasonChecklist = "red checklist"
	reasonNoToken   = "no push token"

	footer = "Run `pttd admin devices` over SSH for details; the app's setup checklist shows how to fix each item."
)

// Device is the slice of a devices row the heartbeat looks at.
type Device struct {
	ID         string
	Person     string
	Name       string
	AppVersion string
	Created    time.Time
	// LastSeen is the zero time when the device was never seen.
	LastSeen time.Time
	// Checklist is the stored checklist JSON, empty when none.
	Checklist string
	HasToken  bool
	Revoked   bool
	Test      bool
}

// Pair is one (device, reason) the heartbeat reported.
type Pair struct {
	Device string
	Reason string
}

// Result is one evaluation.
type Result struct {
	// Body is empty when OK.
	Body  string
	Pairs map[Pair]struct{}
}

// OK reports whether no phone needs attention.
func (r Result) OK() bool { return len(r.Pairs) == 0 }

// RestartMode reports whether the server itself was down: the newest last_seen_at of all
// non-test devices is more than RestartWindow older than procStart.
func RestartMode(devs []Device, procStart time.Time) bool {
	var newest time.Time
	for _, d := range devs {
		if !d.Test && d.LastSeen.After(newest) {
			newest = d.LastSeen
		}
	}
	return !newest.IsZero() && procStart.Sub(newest) > RestartWindow
}

type finding struct {
	dev     Device
	reasons []string
}

// Evaluate finds the live, non-test devices that need attention. restart is the result of
// RestartMode taken once at startup; then silence counts from procStart until the device is seen.
func Evaluate(devs []Device, now, procStart time.Time, restart bool, version string) Result {
	var found []finding
	pairs := map[Pair]struct{}{}
	for _, d := range devs {
		if d.Revoked || d.Test {
			continue
		}
		f := finding{dev: d}
		since := d.LastSeen
		if since.IsZero() {
			since = d.Created
		}
		if restart && since.Before(procStart) {
			since = procStart
		}
		if now.Sub(since) > SilentAfter {
			f.reasons = append(f.reasons, fmt.Sprintf("silent since %s (%d h)", stamp(since), int(now.Sub(since)/time.Hour)))
			pairs[Pair{d.ID, reasonSilent}] = struct{}{}
		}
		if keys := redItems(d.Checklist); len(keys) > 0 {
			f.reasons = append(f.reasons, reasonChecklist+": "+strings.Join(keys, ", "))
			pairs[Pair{d.ID, reasonChecklist}] = struct{}{}
		}
		if !d.HasToken {
			f.reasons = append(f.reasons, reasonNoToken)
			pairs[Pair{d.ID, reasonNoToken}] = struct{}{}
		}
		if len(f.reasons) > 0 {
			found = append(found, f)
		}
	}
	if len(found) == 0 {
		return Result{Pairs: pairs}
	}
	sort.Slice(found, func(i, j int) bool {
		a, b := found[i].dev, found[j].dev
		if a.Person != b.Person {
			return a.Person < b.Person
		}
		if a.Name != b.Name {
			return a.Name < b.Name
		}
		return a.ID < b.ID
	})
	return Result{Body: render(found, now, version), Pairs: pairs}
}

func render(found []finding, now time.Time, version string) string {
	noun := "phones need"
	if len(found) == 1 {
		noun = "phone needs"
	}
	header := fmt.Sprintf("%d %s attention (pttd %s, %s):\n", len(found), noun, clean(version), stamp(now))
	tail := footer
	lines := make([]string, len(found))
	for i, f := range found {
		app := "app unknown"
		if f.dev.AppVersion != "" {
			app = "app " + clean(f.dev.AppVersion)
		}
		lines[i] = fmt.Sprintf("%s / %s (%s): %s; %s\n",
			clean(f.dev.Person), clean(f.dev.Name), f.dev.ID, strings.Join(f.reasons, "; "), app)
	}

	size := len(header) + len(tail)
	shown := 0
	for shown < len(lines) {
		more := len(lines) - shown - 1
		reserve := 0
		if more > 0 {
			reserve = len(moreLine(more))
		}
		if size+len(lines[shown])+reserve > MaxBody {
			break
		}
		size += len(lines[shown])
		shown++
	}
	var b strings.Builder
	b.WriteString(header)
	for _, l := range lines[:shown] {
		b.WriteString(l)
	}
	if shown < len(lines) {
		b.WriteString(moreLine(len(lines) - shown))
	}
	b.WriteString(tail)
	return b.String()
}

func moreLine(n int) string { return fmt.Sprintf("... and %d more\n", n) }

func stamp(t time.Time) string { return t.UTC().Format("2006-01-02 15:04 UTC") }

// clean keeps one value on one line: names are user input.
func clean(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, s)
}

// redItems returns the wire names of the false checklist items, sorted. Unparseable data counts
// as no warning, like the contacts list.
func redItems(raw string) []string {
	if raw == "" {
		return nil
	}
	var items map[string]bool
	if json.Unmarshal([]byte(raw), &items) != nil {
		return nil
	}
	var keys []string
	for k, ok := range items {
		if !ok {
			keys = append(keys, clean(k))
		}
	}
	sort.Strings(keys)
	return keys
}

// Action is one ping the tracker asks for.
type Action struct {
	Fail bool
	Body string
}

// Tracker turns successive Results into pings (docs/operations.md, "Re-alerting"). Its state is
// in memory only: after a restart the first non-empty result is just a /fail.
type Tracker struct {
	last map[Pair]struct{}
}

// Next returns the pings for r, in order.
func (t *Tracker) Next(r Result) []Action {
	prev := t.last
	t.last = r.Pairs
	if r.OK() {
		return []Action{{}}
	}
	fail := Action{Fail: true, Body: r.Body}
	if len(prev) == 0 {
		return []Action{fail}
	}
	for p := range r.Pairs {
		if _, ok := prev[p]; !ok {
			return []Action{{Body: "re-alert follows; not resolved"}, fail}
		}
	}
	return []Action{fail}
}
