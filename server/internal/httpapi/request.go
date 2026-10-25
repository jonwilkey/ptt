package httpapi

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
)

// b64u is the strict unpadded base64url of docs/protocol.md section 1.
var b64u = base64.RawURLEncoding.Strict()

// decodeBody reads the request's JSON object into v. Unknown fields, trailing data, an empty
// body and a JSON null are 400 invalid_request. encoding/json matches field names
// case-insensitively and lets a duplicate key overwrite an earlier one; both are accepted on
// purpose, since this is the only parser of these bodies (nothing signs or re-reads them). It writes the error response and returns false on failure.
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

// validUUID reports whether s is a canonical lowercase UUID (36 characters).
func validUUID(s string) bool {
	u, err := uuid.Parse(s)
	return err == nil && len(s) == 36 && u.String() == s
}

// decodeB64u strictly decodes s and requires exactly n bytes.
func decodeB64u(s string, n int) ([]byte, bool) {
	raw, err := b64u.DecodeString(s)
	if err != nil || len(raw) != n {
		return nil, false
	}
	return raw, true
}

// validText reports whether s is 1 to maxRunes characters of valid UTF-8 with no control
// characters and some non-space content.
func validText(s string, maxRunes int) bool {
	if !utf8.ValidString(s) || utf8.RuneCountInString(s) > maxRunes || strings.TrimSpace(s) == "" {
		return false
	}
	return !strings.ContainsFunc(s, unicode.IsControl)
}

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
