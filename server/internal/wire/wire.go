// Package wire holds the strict encodings of docs/protocol.md section 1, shared by the HTTP
// layer and the signed-object and roster parsers.
package wire

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
)

// B64u is the strict unpadded base64url of docs/protocol.md section 1: padding, the standard
// alphabet and non-zero trailing bits are all rejected.
var B64u = base64.RawURLEncoding.Strict()

// ValidUUID reports whether s is a canonical lowercase UUID (36 characters).
func ValidUUID(s string) bool {
	u, err := uuid.Parse(s)
	return err == nil && len(s) == 36 && u.String() == s
}

// DecodeB64u strictly decodes s and requires exactly n bytes.
func DecodeB64u(s string, n int) ([]byte, bool) {
	raw, err := B64u.DecodeString(s)
	if err != nil || len(raw) != n {
		return nil, false
	}
	return raw, true
}

// ValidText reports whether s is 1 to maxRunes characters of valid UTF-8 with no control
// characters and some non-space content.
func ValidText(s string, maxRunes int) bool {
	if !utf8.ValidString(s) || utf8.RuneCountInString(s) > maxRunes || strings.TrimSpace(s) == "" {
		return false
	}
	return !strings.ContainsFunc(s, unicode.IsControl)
}

// StrictObject parses data as one JSON object and returns its raw values by key. Unlike
// encoding/json it is exact: invalid UTF-8 is an error; keys are matched case-sensitively, so a key outside keys (for
// example "Admin" for "admin") is an error; a duplicate key is an error; every key in keys
// must be present; and no value may be null. Signed payloads must go through it (or a
// caller of it) before being decoded into a struct, because phones parse them case-sensitively
// and a lenient server parse would disagree about what was signed.
func StrictObject(data []byte, keys ...string) (map[string]json.RawMessage, error) {
	// encoding/json would silently turn invalid UTF-8 into U+FFFD.
	if !utf8.Valid(data) {
		return nil, errors.New("invalid UTF-8")
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	if tok, err := dec.Token(); err != nil || tok != json.Delim('{') {
		return nil, errors.New("not a JSON object")
	}
	allowed := make(map[string]bool, len(keys))
	for _, k := range keys {
		allowed[k] = true
	}
	out := make(map[string]json.RawMessage, len(keys))
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, ok := tok.(string)
		if !ok || !allowed[key] {
			return nil, fmt.Errorf("unexpected key %q", key)
		}
		if _, dup := out[key]; dup {
			return nil, fmt.Errorf("duplicate key %q", key)
		}
		var raw json.RawMessage
		if err := dec.Decode(&raw); err != nil {
			return nil, err
		}
		if bytes.Equal(raw, []byte("null")) {
			return nil, fmt.Errorf("null value for %q", key)
		}
		out[key] = raw
	}
	if _, err := dec.Token(); err != nil { // the closing brace
		return nil, err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, errors.New("trailing data")
	}
	for _, k := range keys {
		if _, ok := out[k]; !ok {
			return nil, fmt.Errorf("missing key %q", k)
		}
	}
	return out, nil
}
