package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/apk"
)

var apkBytes = []byte(strings.Repeat("0123456789", 100))

func publishAPK(t *testing.T, e *testEnv, version string, code int64, minSupported int64) string {
	t.Helper()
	dir := t.TempDir()
	publishDir(t, dir, version, code, minSupported)
	e.useAPK(dir)
	return dir
}

func publishDir(t *testing.T, dir, version string, code, minSupported int64) {
	t.Helper()
	sum := sha256.Sum256(apkBytes)
	if err := os.WriteFile(filepath.Join(dir, apk.FileName(version)), apkBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(map[string]any{
		"version_name": version, "version_code": code, "sha256": hex.EncodeToString(sum[:]),
		"size": len(apkBytes), "min_supported_version_code": minSupported, "file": apk.FileName(version),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, apk.MetaFile), raw, 0o600); err != nil {
		t.Fatal(err)
	}
}

func (e *testEnv) useAPK(dir string) {
	e.h = NewPublic(PublicOptions{DB: e.db, Now: func() time.Time { return e.now }, APK: apk.NewStore(dir, nil)})
}

func TestAppLatest(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	publishAPK(t, e, "0.5.0", 5000, 4000)

	rec := e.call(http.MethodGet, "/v1/app/latest", admin.Token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body)
	}
	if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("Cache-Control = %q", cc)
	}
	got := decode[apk.Latest](t, rec)
	sum := sha256.Sum256(apkBytes)
	want := apk.Latest{
		VersionName: "0.5.0", VersionCode: 5000, SHA256: hex.EncodeToString(sum[:]), Size: int64(len(apkBytes)),
		MinSupportedVersionCode: 4000, DownloadPath: "/v1/app/apk/0.5.0",
	}
	if got != want {
		t.Errorf("latest = %+v, want %+v", got, want)
	}
	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"version_name", "version_code", "sha256", "size", "min_supported_version_code", "download_path"} {
		if _, ok := raw[k]; !ok {
			t.Errorf("missing %q in %s", k, rec.Body)
		}
	}
}

func TestAppLatestNotFound(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()

	// Unset directory (the default test handler has no store).
	if rec := e.call(http.MethodGet, "/v1/app/latest", admin.Token, nil); rec.Code != http.StatusNotFound {
		t.Errorf("unset: status = %d", rec.Code)
	}
	e.useAPK(t.TempDir())
	rec := e.call(http.MethodGet, "/v1/app/latest", admin.Token, nil)
	if rec.Code != http.StatusNotFound || decode[ErrorResponse](t, rec).Error != "not_found" {
		t.Errorf("missing metadata: %d %s", rec.Code, rec.Body)
	}

	// Validation failures are covered in package apk; here one is enough to see the mapping.
	dir := publishAPK(t, e, "0.5.0", 5001, 0)
	if rec := e.call(http.MethodGet, "/v1/app/latest", admin.Token, nil); rec.Code != http.StatusNotFound {
		t.Errorf("formula mismatch: status = %d", rec.Code)
	}
	if err := os.Remove(filepath.Join(dir, "ptt-0.5.0.apk")); err != nil {
		t.Fatal(err)
	}
	if rec := e.call(http.MethodGet, "/v1/app/latest", admin.Token, nil); rec.Code != http.StatusNotFound {
		t.Errorf("missing apk: status = %d", rec.Code)
	}
}

func TestAppEndpointsNeedAuth(t *testing.T) {
	e := newEnv(t)
	e.bootstrapAdmin()
	publishAPK(t, e, "0.5.0", 5000, 0)
	for _, path := range []string{"/v1/app/latest", "/v1/app/apk/0.5.0"} {
		if rec := e.call(http.MethodGet, path, "", nil); rec.Code != http.StatusUnauthorized {
			t.Errorf("%s without token: %d", path, rec.Code)
		}
		if rec := e.call(http.MethodGet, path, "garbage", nil); rec.Code != http.StatusUnauthorized {
			t.Errorf("%s with bad token: %d", path, rec.Code)
		}
	}
}

func TestAppAPKDownload(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	dir := publishAPK(t, e, "0.5.0", 5000, 0)
	if err := os.WriteFile(filepath.Join(dir, "ptt-0.4.0.apk"), []byte("older"), 0o600); err != nil {
		t.Fatal(err)
	}

	rec := e.call(http.MethodGet, "/v1/app/apk/0.5.0", admin.Token, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if got := rec.Body.Bytes(); string(got) != string(apkBytes) {
		t.Errorf("body differs (%d bytes)", len(got))
	}
	h := rec.Header()
	if h.Get("Content-Type") != "application/vnd.android.package-archive" ||
		h.Get("Content-Length") != "1000" || h.Get("Cache-Control") != "no-store" || h.Get("Last-Modified") == "" {
		t.Errorf("headers = %v", h)
	}

	// A kept earlier version is served although latest names a newer one.
	if rec := e.call(http.MethodGet, "/v1/app/apk/0.4.0", admin.Token, nil); rec.Code != http.StatusOK || rec.Body.String() != "older" {
		t.Errorf("earlier version: %d %q", rec.Code, rec.Body)
	}

	rec = e.call(http.MethodGet, "/v1/app/apk/0.5.0", admin.Token, nil, "Range", "bytes=10-19")
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "0123456789" ||
		rec.Header().Get("Content-Range") != "bytes 10-19/1000" {
		t.Errorf("range: %d %q %v", rec.Code, rec.Body, rec.Header())
	}
	if rec := e.call(http.MethodGet, "/v1/app/apk/0.5.0", admin.Token, nil, "Range", "bytes=5000-"); rec.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Errorf("unsatisfiable range: %d", rec.Code)
	}
}

func TestAppAPKNotFound(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	dir := publishAPK(t, e, "0.5.0", 5000, 0)
	if err := os.WriteFile(filepath.Join(filepath.Dir(dir), "secret.apk"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, v := range []string{
		"0.6.0", "0.5", "00.5.0", "0.5.0.0", "1000.0.0", "latest.json", "%2e%2e%2fsecret", "..%2fptt-0.5.0", "0.5.0%00", "0.5.0%0a", "%2e%2e",
	} {
		rec := e.call(http.MethodGet, "/v1/app/apk/"+v, admin.Token, nil)
		if rec.Code != http.StatusNotFound || decode[ErrorResponse](t, rec).Error != "not_found" {
			t.Errorf("version %q: %d %s", v, rec.Code, rec.Body)
		}
	}
	// Unset directory.
	e.useAPK("")
	if rec := e.call(http.MethodGet, "/v1/app/apk/0.5.0", admin.Token, nil); rec.Code != http.StatusNotFound {
		t.Errorf("unset dir: %d", rec.Code)
	}
}

type deadlineRecorder struct {
	*httptest.ResponseRecorder
	deadline time.Time
}

func (d *deadlineRecorder) SetWriteDeadline(t time.Time) error { d.deadline = t; return nil }

func TestAppAPKExtendsWriteDeadline(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	publishAPK(t, e, "0.5.0", 5000, 0)

	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/v1/app/apk/0.5.0", nil)
	req.Header.Set("Authorization", "Bearer "+admin.Token)
	rec := &deadlineRecorder{ResponseRecorder: httptest.NewRecorder()}
	before := time.Now()
	e.h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if d := rec.deadline.Sub(before); d < 9*time.Minute+50*time.Second || d > 10*time.Minute+10*time.Second {
		t.Errorf("write deadline is %v from now, want about 10 minutes", d)
	}
}

func TestAppAPKOverRealServer(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrapAdmin()
	publishAPK(t, e, "0.5.0", 5000, 0)

	srv := httptest.NewServer(e.h)
	t.Cleanup(srv.Close)
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+"/v1/app/apk/0.5.0", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+admin.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	got, err := io.ReadAll(resp.Body)
	if err != nil || string(got) != string(apkBytes) {
		t.Errorf("body err=%v len=%d", err, len(got))
	}
}
