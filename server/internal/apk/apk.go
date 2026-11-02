// Package apk reads the published Android APKs from a directory the operator fills (docs/operations.md,
// "Publishing an APK"). pttd only reads it.
package apk

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"
	"time"
)

const (
	// MetaFile names the metadata of the newest APK inside the directory.
	MetaFile = "latest.json"

	maxMetaBytes = 64 << 10
)

// ErrNotPublished means there is nothing valid to offer; callers answer 404.
var ErrNotPublished = errors.New("no apk published")

var (
	versionRE = regexp.MustCompile(`^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$`)
	sha256RE  = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// Latest is the body of GET /v1/app/latest (docs/protocol.md section 14.2).
type Latest struct {
	VersionName             string `json:"version_name"`
	VersionCode             int64  `json:"version_code"`
	SHA256                  string `json:"sha256"`
	Size                    int64  `json:"size"`
	MinSupportedVersionCode int64  `json:"min_supported_version_code"`
	DownloadPath            string `json:"download_path"`
}

type meta struct {
	VersionName             string `json:"version_name"`
	VersionCode             int64  `json:"version_code"`
	SHA256                  string `json:"sha256"`
	Size                    int64  `json:"size"`
	MinSupportedVersionCode int64  `json:"min_supported_version_code"`
	File                    string `json:"file"`
}

// ValidVersion reports whether s is a version name (docs/protocol.md section 14.1).
func ValidVersion(s string) bool { return versionRE.MatchString(s) }

// FileName is the APK file name for a version name.
func FileName(version string) string { return "ptt-" + version + ".apk" }

// Code is the version code of a valid version name: X*1_000_000 + Y*1_000 + Z.
func Code(version string) (int64, bool) {
	m := versionRE.FindStringSubmatch(version)
	if m == nil {
		return 0, false
	}
	var code int64
	for _, part := range m[1:] {
		n, _ := strconv.ParseInt(part, 10, 64)
		code = code*1000 + n
	}
	return code, true
}

// Store serves the APK directory. The zero directory means nothing is published.
type Store struct {
	dir    string
	logger *slog.Logger

	mu        sync.Mutex
	loggedFor string
}

// NewStore returns a store over dir; an empty dir publishes nothing.
func NewStore(dir string, logger *slog.Logger) *Store {
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	return &Store{dir: dir, logger: logger}
}

// Latest reads and validates latest.json on every call. A missing file is the normal "nothing
// published" state; any other problem is logged once per change of the file's modification time
// (or of the APK's) and also yields ErrNotPublished.
func (s *Store) Latest() (Latest, error) {
	if s == nil || s.dir == "" {
		return Latest{}, ErrNotPublished
	}
	path := filepath.Join(s.dir, MetaFile)
	info, err := os.Stat(path)
	if err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			s.logBadOnce("stat", fmt.Errorf("stat %s: %w", MetaFile, err))
		}
		return Latest{}, ErrNotPublished
	}
	key := info.ModTime().UTC().Format(time.RFC3339Nano)

	m, err := readMeta(path)
	if err == nil {
		key += "/" + m.File
		var apkInfo fs.FileInfo
		if apkInfo, err = os.Stat(filepath.Join(s.dir, FileName(m.VersionName))); err == nil {
			key += "/" + apkInfo.ModTime().UTC().Format(time.RFC3339Nano)
			err = m.check(apkInfo)
		} else {
			err = fmt.Errorf("apk file: %w", err)
		}
	}
	if err != nil {
		s.logBadOnce(key, err)
		return Latest{}, ErrNotPublished
	}
	return Latest{
		VersionName:             m.VersionName,
		VersionCode:             m.VersionCode,
		SHA256:                  m.SHA256,
		Size:                    m.Size,
		MinSupportedVersionCode: m.MinSupportedVersionCode,
		DownloadPath:            "/v1/app/apk/" + m.VersionName,
	}, nil
}

func readMeta(path string) (meta, error) {
	f, err := os.Open(filepath.Clean(path))
	if err != nil {
		return meta{}, err
	}
	defer func() { _ = f.Close() }()
	raw, err := io.ReadAll(io.LimitReader(f, maxMetaBytes+1))
	if err != nil {
		return meta{}, err
	}
	if len(raw) > maxMetaBytes {
		return meta{}, fmt.Errorf("%s is larger than %d bytes", MetaFile, maxMetaBytes)
	}
	var m meta
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&m); err != nil {
		return meta{}, fmt.Errorf("parse %s: %w", MetaFile, err)
	}
	if dec.More() {
		return meta{}, fmt.Errorf("parse %s: trailing data", MetaFile)
	}
	return m, nil
}

func (m meta) check(apk fs.FileInfo) error {
	code, ok := Code(m.VersionName)
	switch {
	case !ok:
		return errors.New("version_name is not X.Y.Z")
	case m.VersionCode != code:
		return fmt.Errorf("version_code %d does not match version_name %s (want %d)", m.VersionCode, m.VersionName, code)
	case !sha256RE.MatchString(m.SHA256):
		return errors.New("sha256 is not 64 lowercase hex digits")
	case m.File != FileName(m.VersionName):
		return fmt.Errorf("file %q is not %q", m.File, FileName(m.VersionName))
	case m.MinSupportedVersionCode < 0 || m.MinSupportedVersionCode > m.VersionCode:
		return fmt.Errorf("min_supported_version_code %d is outside 0..%d", m.MinSupportedVersionCode, m.VersionCode)
	case !apk.Mode().IsRegular():
		return errors.New("apk file is not a regular file")
	case m.Size != apk.Size():
		return fmt.Errorf("size %d does not match the apk file's %d bytes", m.Size, apk.Size())
	}
	return nil
}

// Open opens the APK of version for download; the caller closes it. A version that fails the
// regex, is missing or is not a regular file yields ErrNotPublished. The path is built only from a
// validated version name, never from the request.
func (s *Store) Open(version string) (*os.File, fs.FileInfo, error) {
	if s == nil || s.dir == "" || !ValidVersion(version) {
		return nil, nil, ErrNotPublished
	}
	f, err := os.Open(filepath.Join(s.dir, FileName(version)))
	if err != nil {
		return nil, nil, ErrNotPublished
	}
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		_ = f.Close()
		return nil, nil, ErrNotPublished
	}
	return f, info, nil
}

func (s *Store) logBadOnce(key string, err error) {
	s.mu.Lock()
	seen := s.loggedFor == key
	s.loggedFor = key
	s.mu.Unlock()
	if !seen {
		s.logger.Warn("published apk metadata is invalid; offering no update", slog.String("error", err.Error()))
	}
}
