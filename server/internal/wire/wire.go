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
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
)

// b64uCodec is strict unpadded base64url. The standard library's Strict() mode rejects padding, the
// standard alphabet and non-zero trailing bits, but still silently skips '\r' and '\n'; a phone's
// decoder does not, so DecodeString rejects them here.
type b64uCodec struct{ enc *base64.Encoding }

// B64u is the strict unpadded base64url of docs/protocol.md section 1: padding, the standard
// alphabet, non-zero trailing bits and embedded line breaks are all rejected. Use it for every
// protocol value; never call base64.Encoding methods (Decode, DecodeString) on the raw encoding.
var B64u = b64uCodec{enc: base64.RawURLEncoding.Strict()}

// DecodeString decodes s strictly.
func (c b64uCodec) DecodeString(s string) ([]byte, error) {
	if strings.ContainsAny(s, "\r\n") {
		return nil, errors.New("base64url: line break in input")
	}
	return c.enc.DecodeString(s)
}

// EncodeToString encodes b without padding.
func (c b64uCodec) EncodeToString(b []byte) string { return c.enc.EncodeToString(b) }

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
	allowed := make(map[string]bool, len(keys))
	for _, k := range keys {
		allowed[k] = true
	}
	out, err := strictMembers(data, func(k string) error {
		if !allowed[k] {
			return fmt.Errorf("unexpected key %q", k)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, k := range keys {
		if _, ok := out[k]; !ok {
			return nil, fmt.Errorf("missing key %q", k)
		}
	}
	return out, nil
}

// StrictMap parses data as one JSON object whose keys are not fixed, such as a map from device
// id to a wrapped key. It applies the same rules as StrictObject (valid UTF-8, no duplicate
// key, no null value, no trailing data) and additionally requires every key to satisfy
// validKey. An empty object is allowed; callers that need entries check the length.
func StrictMap(data []byte, validKey func(string) bool) (map[string]json.RawMessage, error) {
	return strictMembers(data, func(k string) error {
		if !validKey(k) {
			return fmt.Errorf("invalid key %q", k)
		}
		return nil
	})
}

// strictMembers is the shared parser: accept vets each key as it is read.
func strictMembers(data []byte, accept func(key string) error) (map[string]json.RawMessage, error) {
	// encoding/json would silently turn invalid UTF-8 into U+FFFD.
	if !utf8.Valid(data) {
		return nil, errors.New("invalid UTF-8")
	}
	// ...and a lone surrogate escape into U+FFFD, which a strict parser would reject.
	if err := checkSurrogateEscapes(data); err != nil {
		return nil, err
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	if tok, err := dec.Token(); err != nil || tok != json.Delim('{') {
		return nil, errors.New("not a JSON object")
	}
	out := make(map[string]json.RawMessage)
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, ok := tok.(string)
		if !ok {
			return nil, fmt.Errorf("unexpected key %q", key)
		}
		if err := accept(key); err != nil {
			return nil, err
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
	return out, nil
}

// checkSurrogateEscapes rejects a \uD800-\uDBFF escape that is not immediately followed by a
// \uDC00-\uDFFF escape, and a low-surrogate escape that does not directly follow a high one.
// A backslash pair (an escaped backslash) is skipped as a unit, so `\\u` is not an escape.
func checkSurrogateEscapes(data []byte) error {
	hexVal := func(i int) (rune, bool) {
		if i+6 > len(data) || data[i] != '\\' || data[i+1] != 'u' {
			return 0, false
		}
		v, err := strconv.ParseUint(string(data[i+2:i+6]), 16, 16)
		return rune(v), err == nil
	}
	for i := 0; i < len(data); {
		if data[i] != '\\' {
			i++
			continue
		}
		v, ok := hexVal(i)
		switch {
		case !ok:
			i += 2 // some other escape, or malformed (the JSON decoder rejects that)
		case v >= 0xD800 && v <= 0xDBFF:
			low, ok := hexVal(i + 6)
			if !ok || low < 0xDC00 || low > 0xDFFF {
				return errors.New("unpaired surrogate escape")
			}
			i += 12
		case v >= 0xDC00 && v <= 0xDFFF:
			return errors.New("unpaired surrogate escape")
		default:
			i += 6
		}
	}
	return nil
}
