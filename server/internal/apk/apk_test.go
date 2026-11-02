package apk

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var body = []byte("not really an apk")

func meta0() map[string]any {
	sum := sha256.Sum256(body)
	return map[string]any{
		"version_name": "0.5.0", "version_code": 5000, "sha256": hex.EncodeToString(sum[:]),
		"size": len(body), "min_supported_version_code": 4000, "file": "ptt-0.5.0.apk",
	}
}

func publish(t *testing.T, dir string, m map[string]any) {
	t.Helper()
	raw, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, MetaFile), raw, 0o600); err != nil {
		t.Fatal(err)
	}
}

func fixture(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "ptt-0.5.0.apk"), body, 0o600); err != nil {
		t.Fatal(err)
	}
	publish(t, dir, meta0())
	return dir
}

func TestCode(t *testing.T) {
	for v, want := range map[string]int64{"0.0.0": 1, "0.5.0": 5000, "1.2.3": 1_002_003, "999.999.999": 999_999_999} {
		if got, ok := Code(v); !ok || got != want {
			t.Errorf("Code(%q) = %d, %v; want %d", v, got, ok, want)
		}
	}
	for _, v := range []string{"", "1.2", "01.2.3", "1.2.3.4", "1000.0.0", "../1.2.3", "1.2.3\n", "a.b.c", "-1.0.0"} {
		if ValidVersion(v) {
			t.Errorf("ValidVersion(%q) = true", v)
		}
	}
}

func TestLatestValid(t *testing.T) {
	got, err := NewStore(fixture(t), nil).Latest()
	if err != nil {
		t.Fatal(err)
	}
	if got.VersionName != "0.5.0" || got.VersionCode != 5000 || got.Size != int64(len(body)) ||
		got.MinSupportedVersionCode != 4000 || got.DownloadPath != "/v1/app/apk/0.5.0" {
		t.Errorf("latest = %+v", got)
	}
}

func TestLatestRejects(t *testing.T) {
	cases := map[string]func(t *testing.T, dir string, m map[string]any){
		"wrong file name":   func(_ *testing.T, _ string, m map[string]any) { m["file"] = "ptt-0.4.0.apk" },
		"path in file":      func(_ *testing.T, _ string, m map[string]any) { m["file"] = "../ptt-0.5.0.apk" },
		"size mismatch":     func(_ *testing.T, _ string, m map[string]any) { m["size"] = len(body) + 1 },
		"formula mismatch":  func(_ *testing.T, _ string, m map[string]any) { m["version_code"] = 5001 },
		"min above version": func(_ *testing.T, _ string, m map[string]any) { m["min_supported_version_code"] = 5001 },
		"negative min":      func(_ *testing.T, _ string, m map[string]any) { m["min_supported_version_code"] = -1 },
		"uppercase sha":     func(_ *testing.T, _ string, m map[string]any) { m["sha256"] = strings.ToUpper(fmt.Sprint(m["sha256"])) },
		"short sha":         func(_ *testing.T, _ string, m map[string]any) { m["sha256"] = "abc" },
		"bad version name":  func(_ *testing.T, _ string, m map[string]any) { m["version_name"] = "0.05.0" },
		"missing apk":       func(t *testing.T, dir string, _ map[string]any) { remove(t, filepath.Join(dir, "ptt-0.5.0.apk")) },
		"unparsable":        func(t *testing.T, dir string, _ map[string]any) { writeRaw(t, dir, "{nope") },
		"trailing data":     func(t *testing.T, dir string, m map[string]any) { writeRaw(t, dir, string(mustJSON(t, m))+"{}") },
		"apk is a directory": func(t *testing.T, dir string, _ map[string]any) {
			replaceWithDir(t, filepath.Join(dir, "ptt-0.5.0.apk"))
		},
		"metadata missing": func(t *testing.T, dir string, _ map[string]any) { remove(t, filepath.Join(dir, MetaFile)) },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, "ptt-0.5.0.apk"), body, 0o600); err != nil {
				t.Fatal(err)
			}
			m := meta0()
			publish(t, dir, m)
			mutate(t, dir, m)
			if _, err := os.Stat(filepath.Join(dir, MetaFile)); err == nil && !strings.Contains(name, "unparsable") && !strings.Contains(name, "trailing") {
				publish(t, dir, m)
			}
			if _, err := NewStore(dir, nil).Latest(); !errors.Is(err, ErrNotPublished) {
				t.Errorf("Latest() err = %v, want ErrNotPublished", err)
			}
		})
	}
}

func TestUnsetDir(t *testing.T) {
	s := NewStore("", nil)
	if _, err := s.Latest(); !errors.Is(err, ErrNotPublished) {
		t.Errorf("Latest() err = %v", err)
	}
	if _, _, err := s.Open("0.5.0"); !errors.Is(err, ErrNotPublished) {
		t.Errorf("Open() err = %v", err)
	}
	var nilStore *Store
	if _, err := nilStore.Latest(); !errors.Is(err, ErrNotPublished) {
		t.Errorf("nil Latest() err = %v", err)
	}
}

func TestOpen(t *testing.T) {
	dir := fixture(t)
	if err := os.WriteFile(filepath.Join(dir, "ptt-0.4.0.apk"), []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	s := NewStore(dir, nil)
	f, info, err := s.Open("0.4.0")
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	if info.Size() != 3 {
		t.Errorf("size = %d", info.Size())
	}
	for _, v := range []string{"0.6.0", "../0.5.0", "0.5.0/../0.4.0", "latest.json", "", "0.5", "00.5.0"} {
		if _, _, err := s.Open(v); !errors.Is(err, ErrNotPublished) {
			t.Errorf("Open(%q) err = %v", v, err)
		}
	}
}

func TestBadFileLoggedOncePerChange(t *testing.T) {
	dir := fixture(t)
	var buf bytes.Buffer
	s := NewStore(dir, slog.New(slog.NewJSONHandler(&buf, nil)))
	count := func() int { return strings.Count(buf.String(), "invalid") }

	m := meta0()
	m["size"] = 1
	publish(t, dir, m)
	for range 5 {
		_, _ = s.Latest()
	}
	if got := count(); got != 1 {
		t.Fatalf("logged %d times for an unchanged bad file, want 1", got)
	}

	later := time.Now().Add(time.Hour)
	if err := os.Chtimes(filepath.Join(dir, MetaFile), later, later); err != nil {
		t.Fatal(err)
	}
	for range 3 {
		_, _ = s.Latest()
	}
	if got := count(); got != 2 {
		t.Fatalf("logged %d times after the file changed, want 2", got)
	}

	publish(t, dir, meta0())
	if _, err := s.Latest(); err != nil {
		t.Fatal(err)
	}
	if got := count(); got != 2 {
		t.Errorf("a good file logged: %s", buf.String())
	}
}

func TestMissingMetadataIsQuiet(t *testing.T) {
	var buf bytes.Buffer
	s := NewStore(t.TempDir(), slog.New(slog.NewJSONHandler(&buf, nil)))
	_, _ = s.Latest()
	if buf.Len() != 0 {
		t.Errorf("logged for an empty directory: %s", buf.String())
	}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func writeRaw(t *testing.T, dir, s string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, MetaFile), []byte(s), 0o600); err != nil {
		t.Fatal(err)
	}
}

func remove(t *testing.T, path string) {
	t.Helper()
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
}

func replaceWithDir(t *testing.T, path string) {
	t.Helper()
	remove(t, path)
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}
}
