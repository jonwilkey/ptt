package ws

import (
	"encoding/json"
	"errors"
	"math"
	"strconv"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// maxAppVersionRunes is the bound on app_version, as in PUT /v1/devices/me.
const maxAppVersionRunes = 32

// Client message types (protocol section 11.2).
const (
	typeHello        = "hello"
	typeJoin         = "join"
	typeFloorRequest = "floor_request"
	typeFloorRelease = "floor_release"
	typeSessionEnd   = "session_end"
	typePing         = "ping"

	// Protocol 2 (section 13.2): unknown types on a protocol-1 socket.
	typeHeard     = "heard"
	typeResume    = "resume"
	typeSenderKey = "sender_key"
)

// maxHeardSeq is the largest last_seq of a heard message (section 13.2).
const maxHeardSeq = 4095

// errMalformed is any violation of the strict client-message rules; it closes with 4400.
var errMalformed = errors.New("malformed client message")

// clientMsg is a parsed, shape-checked client message. Which fields are set depends on typ.
type clientMsg struct {
	typ string

	protocol   int64  // hello
	token      string // hello
	appVersion string // hello

	sessionID string // join

	transmissionID string // floor_request, floor_release, heard, resume
	clip           bool   // floor_request, protocol 2
	lastSeq        int64  // heard

	senderKey signed.Object // sender_key

	t int64 // ping
}

// parseClient applies the strictness of protocol section 11.1 to one text frame: UTF-8, one
// object, no duplicate or null members, case-sensitive keys, a string type, and for each type
// exactly its keys. IDs are checked here only for being canonical lowercase UUIDs where the
// message cannot be answered with an error message instead (floor_request's own id is
// checked by the floor logic, which answers bad_transmission_id). protocol is the socket's
// negotiated protocol (1 until hello completes): the protocol-2 types are unknown on a
// protocol-1 socket, and floor_request carries clip exactly when the protocol is 2 (section 13.2).
func parseClient(data []byte, protocol int64) (clientMsg, error) {
	members, err := wire.StrictMap(data, func(string) bool { return true })
	if err != nil {
		return clientMsg{}, errMalformed
	}
	var m clientMsg
	if m.typ, err = stringOf(members["type"]); err != nil {
		return clientMsg{}, errMalformed
	}
	var keys []string
	switch m.typ {
	case typeHello:
		keys = []string{"protocol", "token", "app_version"}
	case typeJoin:
		keys = []string{"session_id"}
	case typeFloorRequest:
		keys = []string{"transmission_id"}
		if protocol >= 2 {
			keys = append(keys, "clip")
		}
	case typeFloorRelease:
		keys = []string{"transmission_id"}
	case typeHeard:
		if protocol < 2 {
			return clientMsg{}, errMalformed
		}
		keys = []string{"transmission_id", "last_seq"}
	case typeResume:
		if protocol < 2 {
			return clientMsg{}, errMalformed
		}
		keys = []string{"transmission_id"}
	case typeSenderKey:
		if protocol < 2 {
			return clientMsg{}, errMalformed
		}
		keys = []string{"key"}
	case typeSessionEnd:
	case typePing:
		keys = []string{"t"}
	default:
		return clientMsg{}, errMalformed // unknown type
	}
	if len(members) != len(keys)+1 {
		return clientMsg{}, errMalformed
	}
	for _, k := range keys {
		if _, ok := members[k]; !ok {
			return clientMsg{}, errMalformed
		}
	}

	switch m.typ {
	case typeHello:
		if m.protocol, err = intOf(members["protocol"], math.MaxInt64); err != nil || m.protocol < 1 {
			return clientMsg{}, errMalformed
		}
		if m.token, err = stringOf(members["token"]); err != nil {
			return clientMsg{}, errMalformed
		}
		if m.appVersion, err = stringOf(members["app_version"]); err != nil ||
			!wire.ValidText(m.appVersion, maxAppVersionRunes) {
			return clientMsg{}, errMalformed
		}
	case typeJoin:
		if m.sessionID, err = stringOf(members["session_id"]); err != nil || !wire.ValidUUID(m.sessionID) {
			return clientMsg{}, errMalformed
		}
	case typeFloorRequest:
		// Any string: a bad id is an error message, not a protocol violation (section 11.6).
		if m.transmissionID, err = stringOf(members["transmission_id"]); err != nil {
			return clientMsg{}, errMalformed
		}
		if protocol >= 2 {
			if m.clip, err = boolOf(members["clip"]); err != nil {
				return clientMsg{}, errMalformed
			}
		}
	case typeFloorRelease, typeResume:
		if m.transmissionID, err = stringOf(members["transmission_id"]); err != nil || !wire.ValidUUID(m.transmissionID) {
			return clientMsg{}, errMalformed
		}
	case typeHeard:
		if m.transmissionID, err = stringOf(members["transmission_id"]); err != nil || !wire.ValidUUID(m.transmissionID) {
			return clientMsg{}, errMalformed
		}
		if m.lastSeq, err = intOf(members["last_seq"], maxHeardSeq); err != nil {
			return clientMsg{}, errMalformed
		}
	case typeSenderKey:
		if m.senderKey, err = signedOf(members["key"]); err != nil {
			return clientMsg{}, errMalformed
		}
	case typePing:
		// t is an opaque client clock value (0 to 2^53-1), echoed in the pong and never interpreted.
		if m.t, err = intOf(members["t"], 1<<53-1); err != nil {
			return clientMsg{}, errMalformed
		}
	}
	return m, nil
}

// stringOf decodes a JSON string value (and nothing else: a number is not a string).
func stringOf(raw json.RawMessage) (string, error) {
	if len(raw) == 0 || raw[0] != '"' {
		return "", errMalformed
	}
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return "", errMalformed
	}
	return s, nil
}

// boolOf decodes a JSON boolean literal.
func boolOf(raw json.RawMessage) (bool, error) {
	switch string(raw) {
	case "true":
		return true, nil
	case "false":
		return false, nil
	}
	return false, errMalformed
}

// signedOf decodes a signed object (section 3) with exactly payload, signer and sig, all strings.
func signedOf(raw json.RawMessage) (signed.Object, error) {
	members, err := wire.StrictObject(raw, "payload", "signer", "sig")
	if err != nil {
		return signed.Object{}, errMalformed
	}
	var w signed.Wire
	for name, dst := range map[string]*string{"payload": &w.Payload, "signer": &w.Signer, "sig": &w.Sig} {
		if *dst, err = stringOf(members[name]); err != nil {
			return signed.Object{}, errMalformed
		}
	}
	obj, err := signed.Decode(w)
	if err != nil {
		return signed.Object{}, errMalformed
	}
	return obj, nil
}

// intOf decodes a JSON integer literal in [0, limit]: digits only, no sign, fraction, exponent
// or leading zero. A value too long to represent counts as limit's overflow only for the
// callers that accept any size; others reject it by the range check.
func intOf(raw json.RawMessage, limit int64) (int64, error) {
	if len(raw) == 0 || len(raw) > 1 && raw[0] == '0' {
		return 0, errMalformed
	}
	for _, b := range raw {
		if b < '0' || b > '9' {
			return 0, errMalformed
		}
	}
	n, err := strconv.ParseInt(string(raw), 10, 64)
	if err != nil {
		// More digits than int64: an integer, just a huge one.
		if limit == math.MaxInt64 {
			return math.MaxInt64, nil
		}
		return 0, errMalformed
	}
	if n > limit {
		return 0, errMalformed
	}
	return n, nil
}

// validTransmissionID reports whether s is a canonical lowercase version-4 UUID.
func validTransmissionID(s string) bool {
	if !wire.ValidUUID(s) {
		return false
	}
	u, err := uuid.Parse(s)
	return err == nil && u.Version() == 4 && u.Variant() == uuid.RFC4122
}

// Server messages (protocol section 11.2). Emitters put type first.

type helloMsg struct {
	Type       string `json:"type"`
	Protocol   int64  `json:"protocol"`
	DeviceID   string `json:"device_id"`
	ServerTime int64  `json:"server_time"`
}

type pageStateMsg struct {
	Type   string `json:"type"`
	PageID string `json:"page_id"`
	State  string `json:"state"`
}

type peerMsg struct {
	Type     string `json:"type"` // peer_joined | peer_left
	DeviceID string `json:"device_id"`
}

type sessionEndMsg struct {
	Type   string `json:"type"`
	Reason string `json:"reason"`
}

type errorMsg struct {
	Type string `json:"type"`
	Code string `json:"code"`
}

type pongMsg struct {
	Type       string `json:"type"`
	T          int64  `json:"t"`
	ServerTime int64  `json:"server_time"`
}

// encode marshals a server message. The messages are plain structs of strings and integers,
// so marshalling cannot fail.
func encode(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

// Error codes of an error message (section 11.2).
const (
	errNotFound        = "not_found"
	errNoPeer          = "no_peer"
	errBadTransmission = "bad_transmission_id"
)
