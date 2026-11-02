package monitor

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

var (
	now       = time.Date(2026, 11, 2, 18, 0, 0, 0, time.UTC)
	procStart = now.Add(-10 * time.Minute)
)

func dev(id string, mut func(*Device)) Device {
	d := Device{
		ID: id, Person: "Alex", Name: "Phone " + id, AppVersion: "0.5.0",
		Created: now.Add(-90 * 24 * time.Hour), LastSeen: now.Add(-time.Hour),
		Checklist: `{"battery":true,"full_screen":true}`, HasToken: true,
	}
	if mut != nil {
		mut(&d)
	}
	return d
}

func TestEvaluate(t *testing.T) {
	tests := []struct {
		name    string
		devs    []Device
		restart bool
		pairs   []Pair
		want    []string
	}{
		{name: "all healthy", devs: []Device{dev("a", nil)}},
		{name: "no devices"},
		{
			name:  "silent just under 48 h",
			devs:  []Device{dev("a", func(d *Device) { d.LastSeen = now.Add(-SilentAfter) })},
			pairs: nil,
		},
		{
			name:  "silent just over 48 h",
			devs:  []Device{dev("a", func(d *Device) { d.LastSeen = now.Add(-SilentAfter - time.Minute) })},
			pairs: []Pair{{"a", "silent"}},
			want:  []string{"1 phone needs attention (pttd 0.6.0, 2026-11-02 18:00 UTC):", "Alex / Phone a (a): silent since 2026-10-31 17:59 UTC (48 h); app 0.5.0"},
		},
		{
			name: "never seen falls back to creation",
			devs: []Device{dev("a", func(d *Device) {
				d.LastSeen = time.Time{}
				d.Created = now.Add(-72 * time.Hour)
			})},
			pairs: []Pair{{"a", "silent"}},
			want:  []string{"silent since 2026-10-30 18:00 UTC (72 h)"},
		},
		{
			name:  "red checklist lists wire names sorted",
			devs:  []Device{dev("a", func(d *Device) { d.Checklist = `{"full_screen":false,"battery":false,"notifications":true}` })},
			pairs: []Pair{{"a", "red checklist"}},
			want:  []string{"red checklist: battery, full_screen; app 0.5.0"},
		},
		{name: "unparseable checklist is not red", devs: []Device{dev("a", func(d *Device) { d.Checklist = `nope` })}},
		{
			name:  "no token",
			devs:  []Device{dev("a", func(d *Device) { d.HasToken = false; d.AppVersion = "" })},
			pairs: []Pair{{"a", "no push token"}},
			want:  []string{"no push token; app unknown"},
		},
		{
			name: "several reasons on one line",
			devs: []Device{dev("a", func(d *Device) {
				d.HasToken = false
				d.Checklist = `{"battery":false}`
			})},
			pairs: []Pair{{"a", "red checklist"}, {"a", "no push token"}},
			want:  []string{"red checklist: battery; no push token; app 0.5.0"},
		},
		{
			name: "test and revoked are excluded",
			devs: []Device{
				dev("t", func(d *Device) { d.Test = true; d.HasToken = false }),
				dev("r", func(d *Device) { d.Revoked = true; d.HasToken = false }),
			},
		},
		{
			name:    "restart rule: silence counts from process start",
			devs:    []Device{dev("a", func(d *Device) { d.LastSeen = now.Add(-100 * time.Hour) })},
			restart: true,
		},
		{
			name:    "restart rule: a device seen since start counts from its own last seen",
			devs:    []Device{dev("a", func(d *Device) { d.LastSeen = procStart.Add(time.Minute) })},
			restart: true,
		},
		{
			name:  "no restart rule: same device is silent",
			devs:  []Device{dev("a", func(d *Device) { d.LastSeen = now.Add(-100 * time.Hour) })},
			pairs: []Pair{{"a", "silent"}},
			want:  []string{"(100 h)"},
		},
		{
			name: "sorted by person then device name",
			devs: []Device{
				dev("1", func(d *Device) { d.Person = "Sam"; d.Name = "b"; d.HasToken = false }),
				dev("2", func(d *Device) { d.Person = "Alex"; d.Name = "z"; d.HasToken = false }),
				dev("3", func(d *Device) { d.Person = "Sam"; d.Name = "a"; d.HasToken = false }),
			},
			pairs: []Pair{{"1", "no push token"}, {"2", "no push token"}, {"3", "no push token"}},
			want:  []string{"3 phones need attention", "Alex / z (2)", "Sam / a (3)", "Sam / b (1)"},
		},
		{
			name:  "names with newlines stay on one line",
			devs:  []Device{dev("a", func(d *Device) { d.Name = "x\ny"; d.HasToken = false })},
			pairs: []Pair{{"a", "no push token"}},
			want:  []string{"Alex / x y (a)"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := Evaluate(tt.devs, now, procStart, tt.restart, "0.6.0")
			if r.OK() != (len(tt.pairs) == 0) {
				t.Fatalf("OK() = %v, body:\n%s", r.OK(), r.Body)
			}
			if len(r.Pairs) != len(tt.pairs) {
				t.Errorf("pairs = %v, want %v", r.Pairs, tt.pairs)
			}
			for _, p := range tt.pairs {
				if _, ok := r.Pairs[p]; !ok {
					t.Errorf("missing pair %v in %v", p, r.Pairs)
				}
			}
			if r.OK() && r.Body != "" {
				t.Errorf("OK result has body %q", r.Body)
			}
			prev := -1
			for _, w := range tt.want {
				i := strings.Index(r.Body, w)
				if i < 0 {
					t.Errorf("body lacks %q:\n%s", w, r.Body)
				}
				if i < prev {
					t.Errorf("%q out of order in:\n%s", w, r.Body)
				}
				prev = i
			}
			if !r.OK() && !strings.HasSuffix(r.Body, footer) {
				t.Errorf("body lacks the footer:\n%s", r.Body)
			}
		})
	}
}

func TestEvaluateTruncates(t *testing.T) {
	var devs []Device
	for i := range 100 {
		devs = append(devs, dev(fmt.Sprintf("%08d-0000-4000-8000-000000000000", i), func(d *Device) {
			d.Name = "Pixel"
			d.HasToken = false
		}))
	}
	r := Evaluate(devs, now, procStart, false, "0.6.0")
	if len(r.Body) > MaxBody {
		t.Fatalf("body is %d bytes, over %d", len(r.Body), MaxBody)
	}
	if len(r.Pairs) != 100 {
		t.Errorf("pairs = %d, want all 100 (truncation is only in the body)", len(r.Pairs))
	}
	var shown int
	for _, l := range strings.Split(r.Body, "\n") {
		if strings.HasPrefix(l, "Alex / ") {
			shown++
		}
	}
	want := fmt.Sprintf("... and %d more\n", 100-shown)
	if shown == 0 || shown == 100 || !strings.Contains(r.Body, want) {
		t.Errorf("shown %d lines, want %q before the footer:\n%s", shown, want, r.Body)
	}
	if !strings.HasPrefix(r.Body, "100 phones need attention") || !strings.HasSuffix(r.Body, footer) {
		t.Errorf("header or footer lost:\n%s", r.Body)
	}
}

func TestEvaluateFitsExactly(t *testing.T) {
	devs := []Device{dev("a", func(d *Device) { d.HasToken = false })}
	if r := Evaluate(devs, now, procStart, false, "0.6.0"); strings.Contains(r.Body, "more") {
		t.Errorf("a short body was truncated:\n%s", r.Body)
	}
}

func TestRestartMode(t *testing.T) {
	seen := func(ago time.Duration, mut func(*Device)) Device {
		return dev("x", func(d *Device) {
			d.LastSeen = procStart.Add(-ago)
			if mut != nil {
				mut(d)
			}
		})
	}
	tests := []struct {
		name string
		devs []Device
		want bool
	}{
		{"no devices", nil, false},
		{"newest seen 25 h before start", []Device{seen(25*time.Hour, nil), seen(80*time.Hour, nil)}, true},
		{"newest seen 24 h before start", []Device{seen(RestartWindow, nil)}, false},
		{"one recent phone", []Device{seen(time.Hour, nil), seen(90*time.Hour, nil)}, false},
		{"test device does not count", []Device{seen(time.Hour, func(d *Device) { d.Test = true }), seen(50*time.Hour, nil)}, true},
		{"never seen", []Device{dev("x", func(d *Device) { d.LastSeen = time.Time{} })}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := RestartMode(tt.devs, procStart); got != tt.want {
				t.Errorf("RestartMode = %v, want %v", got, tt.want)
			}
		})
	}
}

func res(pairs ...Pair) Result {
	r := Result{Pairs: map[Pair]struct{}{}}
	for _, p := range pairs {
		r.Pairs[p] = struct{}{}
	}
	if len(pairs) > 0 {
		r.Body = fmt.Sprint(pairs)
	}
	return r
}

func TestTracker(t *testing.T) {
	a, b := Pair{"a", "silent"}, Pair{"b", "no push token"}
	reAlert := Action{Body: "re-alert follows; not resolved"}
	steps := []struct {
		name string
		in   Result
		want func(Result) []Action
	}{
		{"first evaluation after start, already failing: just /fail", res(a), func(r Result) []Action { return []Action{{Fail: true, Body: r.Body}} }},
		{"unchanged: /fail again", res(a), func(r Result) []Action { return []Action{{Fail: true, Body: r.Body}} }},
		{"new pair while down: success then /fail", res(a, b), func(r Result) []Action { return []Action{reAlert, {Fail: true, Body: r.Body}} }},
		{"pair removed: /fail with the new body", res(b), func(r Result) []Action { return []Action{{Fail: true, Body: r.Body}} }},
		{"pair swapped for a new one: re-alert", res(a), func(r Result) []Action { return []Action{reAlert, {Fail: true, Body: r.Body}} }},
		{"empty: one success", res(), func(Result) []Action { return []Action{{}} }},
		{"empty again: success ping", res(), func(Result) []Action { return []Action{{}} }},
		{"set becomes non-empty: /fail only", res(b), func(r Result) []Action { return []Action{{Fail: true, Body: r.Body}} }},
	}
	var tr Tracker
	for _, s := range steps {
		got, want := tr.Next(s.in), s.want(s.in)
		if fmt.Sprint(got) != fmt.Sprint(want) {
			t.Errorf("%s: got %v, want %v", s.name, got, want)
		}
	}
}
