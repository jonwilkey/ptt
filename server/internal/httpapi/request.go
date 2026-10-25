package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"reflect"
	"strings"

	"github.com/jonwilkey/ptt/server/internal/wire"
)

// b64u is the strict unpadded base64url of docs/protocol.md section 1.
var b64u = wire.B64u

// decodeBody reads the request's JSON object into v. Unknown fields, trailing data, an empty
// body and a JSON null are 400 invalid_request. Top-level field names must match v's json tags
// exactly (case-sensitively, unlike encoding/json) and may not repeat, as docs/protocol.md
// section 3 requires of every parser; optional fields may be absent. Nested objects are decoded
// by encoding/json (leniently), since only the top level is unsigned input the server alone
// reads. Signed payloads (the roster, the page envelope) are checked separately with
// wire.StrictObject because phones parse them. It writes the error response and returns false
// on failure.
func decodeBody(w http.ResponseWriter, r *http.Request, v any) bool {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
		} else {
			writeError(w, http.StatusBadRequest, "invalid_request")
		}
		return false
	}
	if !exactTopLevelKeys(body, v) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	if bytes.Equal(bytes.TrimSpace(body), []byte("null")) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	// Anything after the object (a second value, a stray brace) is an error; only EOF is fine.
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	return true
}

// exactTopLevelKeys reports whether body's top-level object (if it is one; other documents are
// left to the decoder to reject) uses only field names from v's json tags, exactly, and each
// at most once.
func exactTopLevelKeys(body []byte, v any) bool {
	t := reflect.TypeOf(v)
	for t != nil && t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	if t == nil || t.Kind() != reflect.Struct {
		return true
	}
	allowed := map[string]bool{}
	for f := range t.Fields() {
		name, _, _ := strings.Cut(f.Tag.Get("json"), ",")
		if name == "-" || !f.IsExported() {
			continue
		}
		if name == "" {
			name = f.Name
		}
		allowed[name] = true
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	if tok, err := dec.Token(); err != nil || tok != json.Delim('{') {
		return true
	}
	seen := map[string]bool{}
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return true // malformed: the decoder reports it
		}
		key, _ := tok.(string)
		if !allowed[key] || seen[key] {
			return false
		}
		seen[key] = true
		var skip json.RawMessage
		if err := dec.Decode(&skip); err != nil {
			return true
		}
	}
	return true
}

// validUUID reports whether s is a canonical lowercase UUID (36 characters).
func validUUID(s string) bool { return wire.ValidUUID(s) }

// decodeB64u strictly decodes s and requires exactly n bytes.
func decodeB64u(s string, n int) ([]byte, bool) { return wire.DecodeB64u(s, n) }

// validText reports whether s is 1 to maxRunes characters of valid UTF-8 with no control
// characters and some non-space content.
func validText(s string, maxRunes int) bool { return wire.ValidText(s, maxRunes) }

// clientIP returns the address to rate-limit a request by: the first X-Forwarded-For entry,
// which Caddy sets (every peer is Caddy), else the connection's peer. IPv6 addresses are
// reduced to their /64, since one subscriber holds the whole prefix. The result is only ever
// used as a map key; it is never logged.
//
// Safety: trusting the first X-Forwarded-For entry is sound only because Caddy does not trust
// upstream proxies (no trusted_proxies), so it replaces any client-supplied header with the real
// peer address. Revisit this if that changes (see deploy/Caddyfile).
func clientIP(r *http.Request) string {
	if first, _, _ := strings.Cut(r.Header.Get("X-Forwarded-For"), ","); first != "" {
		if addr, err := netip.ParseAddr(strings.TrimSpace(first)); err == nil {
			return maskIP(addr)
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return maskIP(addr)
	}
	return host
}

func maskIP(addr netip.Addr) string {
	addr = addr.Unmap().WithZone("")
	if addr.Is6() {
		return netip.PrefixFrom(addr, 64).Masked().String()
	}
	return addr.String()
}
