package vectors

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/hpke"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/jonwilkey/ptt/server/internal/admin"
	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/roster"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// vectorsPath is the committed file, relative to this package directory.
const vectorsPath = "../../../testdata/protocol-vectors.json"

var update = flag.Bool("update", false, "rewrite testdata/protocol-vectors.json")

// Every key, id and name below is synthetic. Seeds are SHA-256 of a label, so the file is
// reproducible; only the HPKE `enc` values are random, and those are kept from the committed file.
func seedOf(label string) []byte {
	h := sha256.Sum256([]byte("ptt-test-vector/" + label))
	return h[:]
}

func b64(b []byte) string { return wire.B64u.EncodeToString(b) }

// ---- file schema ------------------------------------------------------------------------

// File is the whole vectors file.
type File struct {
	Comment         string           `json:"comment"`
	Signed          []SignedVector   `json:"signed"`
	Rosters         []RosterVector   `json:"rosters"`
	EnrollmentMACs  []MACVector      `json:"enrollment_macs"`
	QRs             []QRVector       `json:"qrs"`
	HPKE            []HPKEVector     `json:"hpke"`
	Envelopes       []EnvelopeVector `json:"envelope_payloads"`
	EnvelopeReceive []ReceiveVector  `json:"envelope_receive"`
	RosterAccept    []AcceptVector   `json:"roster_accept"`
}

// SignedVector: Wire is the JSON text of the signed object. Expect is "valid" (decodes and
// verifies under Public and Ctx), "malformed" (Decode rejects it) or "bad_signature". For valid
// vectors Payload and Sig are set and Seed signs them reproducibly (Ed25519 is deterministic).
type SignedVector struct {
	Name    string `json:"name"`
	Seed    string `json:"seed"`
	Public  string `json:"public"`
	Ctx     string `json:"ctx"`
	Payload string `json:"payload,omitempty"`
	Sig     string `json:"sig,omitempty"`
	Wire    string `json:"wire"`
	Expect  string `json:"expect"`
}

// RosterDevice mirrors one roster entry for the expected result of a valid payload.
type RosterDevice struct {
	ID          string `json:"id"`
	UserID      string `json:"user_id"`
	DisplayName string `json:"display_name"`
	DeviceName  string `json:"device_name"`
	Admin       bool   `json:"admin"`
	Ed25519     string `json:"ed25519"`
	X25519      string `json:"x25519"`
}

// RosterVector: Payload is b64u of the raw payload bytes (they may be invalid UTF-8).
type RosterVector struct {
	Name     string         `json:"name"`
	Payload  string         `json:"payload"`
	Expect   string         `json:"expect"` // "valid" | "invalid"
	Version  int64          `json:"version,omitempty"`
	IssuedAt int64          `json:"issued_at,omitempty"`
	Devices  []RosterDevice `json:"devices,omitempty"`
}

// MACVector: all binary fields are b64u except DeviceID.
type MACVector struct {
	Name        string `json:"name"`
	Secret      string `json:"secret"`
	Code        string `json:"code"`
	DeviceID    string `json:"device_id"`
	Ed25519     string `json:"ed25519"`
	X25519      string `json:"x25519"`
	MAC         string `json:"mac"`
	ExpectValid bool   `json:"expect_valid"`
}

// QRAdmin is one admin entry of an admin enrollment QR.
type QRAdmin struct {
	ID      string `json:"id"`
	Ed25519 string `json:"ed25519"`
}

// QRVector: Text is the exact QR string for the fields (key order url, code, secret, admins).
type QRVector struct {
	Name   string    `json:"name"`
	URL    string    `json:"url"`
	Code   string    `json:"code"`
	Secret string    `json:"secret,omitempty"`
	Admins []QRAdmin `json:"admins,omitempty"`
	Text   string    `json:"text"`
}

// HPKEVector: Wrapped = enc(32) || ct(48), produced by Go's crypto/hpke. Info is b64u of the
// raw info bytes.
type HPKEVector struct {
	Name       string `json:"name"`
	Private    string `json:"recipient_private"`
	Public     string `json:"recipient_public"`
	PageID     string `json:"page_id"`
	SessionID  string `json:"session_id"`
	DeviceID   string `json:"device_id"`
	Info       string `json:"info"`
	SessionKey string `json:"session_key"`
	Wrapped    string `json:"wrapped"`
}

// EnvelopeVector: Payload is b64u of the raw envelope payload bytes.
type EnvelopeVector struct {
	Name    string   `json:"name"`
	Payload string   `json:"payload"`
	Expect  string   `json:"expect"` // "valid" | "invalid"
	PageID  string   `json:"page_id,omitempty"`
	Level   string   `json:"level,omitempty"`
	From    string   `json:"from_device,omitempty"`
	ToUser  string   `json:"to_user,omitempty"`
	Created int64    `json:"created_at,omitempty"`
	Keys    []string `json:"keys,omitempty"` // recipient device ids, sorted
}

// ReceiveVector is a full receiver scenario. RosterPayload is the receiver's verified roster.
// Expect is "ok" (SessionKey is the unwrapped key) or "reject" (Reason is informational).
type ReceiveVector struct {
	Name          string `json:"name"`
	RosterPayload string `json:"roster_payload"`
	Wire          string `json:"wire"`
	DeviceID      string `json:"receiver_device_id"`
	Private       string `json:"receiver_private"`
	PushPageID    string `json:"push_page_id"`
	PushLevel     string `json:"push_level"`
	PushFromUser  string `json:"push_from_user"`
	Expect        string `json:"expect"`
	Reason        string `json:"reason,omitempty"`
	SessionKey    string `json:"session_key,omitempty"`
}

// SelfID is the phone's own identity for the first-roster rule.
type SelfID struct {
	DeviceID string `json:"device_id"`
	Ed25519  string `json:"ed25519"`
	X25519   string `json:"x25519"`
}

// AcceptVector is a phone-side roster acceptance scenario (docs/protocol.md sections 4 and 5).
// Current is the b64u payload of the roster the phone holds ("" = none: first roster after
// enrollment or bootstrap, judged against Trusted and Self). Wire is the candidate. Expect is
// "accept" or "reject"; Reason is the first failing rule: malformed_wire (the signed object itself
// does not decode), untrusted_signer, bad_signature, malformed_payload, stale_version,
// signer_not_listed, self_not_listed or no_trust_anchor.
type AcceptVector struct {
	Name    string    `json:"name"`
	Current string    `json:"current,omitempty"`
	Trusted []QRAdmin `json:"trusted_admins,omitempty"`
	Self    *SelfID   `json:"self,omitempty"`
	Wire    string    `json:"wire"`
	Expect  string    `json:"expect"`
	Reason  string    `json:"reason,omitempty"`
}

// ---- synthetic identities ---------------------------------------------------------------

type identity struct {
	label  string
	id     string // device id
	user   string
	disp   string
	devn   string
	admin  bool
	edPriv ed25519.PrivateKey
	xPriv  *ecdh.PrivateKey
}

func newIdentity(label, id, user, disp, devn string, admin bool) identity {
	xp, err := ecdh.X25519().NewPrivateKey(seedOf(label + "/x25519"))
	if err != nil {
		panic(err)
	}
	return identity{
		label: label, id: id, user: user, disp: disp, devn: devn, admin: admin,
		edPriv: ed25519.NewKeyFromSeed(seedOf(label + "/ed25519")), xPriv: xp,
	}
}

func (i identity) edPub() []byte { return []byte(i.edPriv.Public().(ed25519.PublicKey)) } //nolint:errcheck // always an ed25519.PublicKey
func (i identity) xPub() []byte  { return i.xPriv.PublicKey().Bytes() }

func (i identity) rosterDevice() RosterDevice {
	return RosterDevice{
		ID: i.id, UserID: i.user, DisplayName: i.disp, DeviceName: i.devn, Admin: i.admin,
		Ed25519: b64(i.edPub()), X25519: b64(i.xPub()),
	}
}

const (
	userAlex = "0b7e4c1d-9a2f-4e68-b3c5-71d0a9e2f845"
	userSam  = "5d1a9f30-2c7e-4b16-8a40-e3b9d6c25f07"
	userKit  = "92c4e7a8-61b3-4d5f-a0e2-48f1b7c93d6a"
)

var (
	alexPhone  = newIdentity("alex-phone", "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57", userAlex, "Alex", "Alex's phone", true)
	alexTablet = newIdentity("alex-tablet", "e8a1c5d2-4f37-4a9b-8c60-2d7f3e1b9a04", userAlex, "Alex", "Alex's tablet", false)
	samPhone   = newIdentity("sam-phone", "7b2d0e94-a1c8-4f53-9d16-b5e8a3c47f20", userSam, "Sam", "Sam's phone", false)
	samWatch   = newIdentity("sam-watch", "a4f6b830-9e21-4c7d-b5a8-1f0d6e2c8b93", userSam, "Sam", "Sam's old phone", false)
	kitPhone   = newIdentity("kit-phone", "0d58c2f7-3a9e-4e41-8b7c-6a1f9d4e3b25", userKit, "Kit", "Kit's phone", false)
	stranger   = newIdentity("stranger", "f1930a6c-8d47-4b25-a3e9-7c2b5d0e4a68", userKit, "Eve", "Eve's phone", true)

	family = []identity{alexPhone, alexTablet, samPhone, samWatch, kitPhone}
)

func sortedDevices(ids ...identity) []identity {
	out := append([]identity(nil), ids...)
	for i := range out {
		for j := i + 1; j < len(out); j++ {
			if out[j].id < out[i].id {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	return out
}

// jsonText marshals v without HTML escaping, matching what the Android encoder writes.
func jsonText(v any) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		panic(err)
	}
	return strings.TrimSuffix(buf.String(), "\n")
}

type rosterPayloadJSON struct {
	Format   int            `json:"format"`
	Version  int64          `json:"version"`
	IssuedAt int64          `json:"issued_at"`
	Devices  []RosterDevice `json:"devices"`
}

func rosterPayload(version int64, ids ...identity) []byte {
	devs := []RosterDevice{}
	for _, d := range sortedDevices(ids...) {
		devs = append(devs, d.rosterDevice())
	}
	return []byte(jsonText(rosterPayloadJSON{Format: 1, Version: version, IssuedAt: 1789920000000 + version, Devices: devs}))
}

func familyRoster() []byte { return rosterPayload(7, family...) }

func signObj(id identity, ctx string, payload []byte) signed.Object {
	return signed.Sign(id.edPriv, id.id, ctx, payload)
}

func wireText(o signed.Object) string { return jsonText(o.Encode()) }

// ---- HPKE with a cache of previously generated ciphertexts --------------------------------

func hpkeInfo(pageID, sessionID, deviceID string) []byte {
	return []byte("ptt-session-key-v1\x00" + pageID + sessionID + deviceID)
}

type sealer struct{ cache map[string]string }

func cacheKey(pub, info, sk []byte) string { return b64(pub) + "|" + b64(info) + "|" + b64(sk) }

// wrap returns enc||ct for sk to pub, reusing a cached ciphertext when one exists so that
// regenerating the file does not churn it.
func (s *sealer) wrap(pub, info, sk []byte) []byte {
	k := cacheKey(pub, info, sk)
	if c, ok := s.cache[k]; ok {
		raw, err := wire.B64u.DecodeString(c)
		if err == nil {
			return raw
		}
	}
	xp, err := ecdh.X25519().NewPublicKey(pub)
	if err != nil {
		panic(err)
	}
	pk, err := hpke.NewDHKEMPublicKey(xp)
	if err != nil {
		panic(err)
	}
	ct, err := hpke.Seal(pk, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), info, sk)
	if err != nil {
		panic(err)
	}
	s.cache[k] = b64(ct)
	return ct
}

func hpkeOpen(priv *ecdh.PrivateKey, info, wrapped []byte) ([]byte, error) {
	sk, err := hpke.NewDHKEMPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	return hpke.Open(sk, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), info, wrapped)
}

// loadCache collects every (public key, info, session key) -> wrapped triple of an existing file.
func loadCache(old *File) map[string]string {
	cache := map[string]string{}
	if old == nil {
		return cache
	}
	for _, v := range old.HPKE {
		pub, _ := wire.B64u.DecodeString(v.Public)
		info, _ := wire.B64u.DecodeString(v.Info)
		sk, _ := wire.B64u.DecodeString(v.SessionKey)
		cache[cacheKey(pub, info, sk)] = v.Wrapped
	}
	byID := map[string]identity{}
	for _, d := range append(append([]identity(nil), family...), stranger) {
		byID[d.id] = d
	}
	addPayload := func(payload []byte) {
		var p struct {
			PageID    string            `json:"page_id"`
			SessionID string            `json:"session_id"`
			Keys      map[string]string `json:"keys"`
		}
		if json.Unmarshal(payload, &p) != nil {
			return
		}
		for dev, wrapped := range p.Keys {
			d, ok := byID[dev]
			raw, err := wire.B64u.DecodeString(wrapped)
			if !ok || err != nil {
				continue
			}
			// Every envelope in the file wraps the same session key, but possibly under another
			// device's info (the deliberately mismatched vector): try them all.
			sk := seedOf("envelope/session-key")
			for other := range byID {
				info := hpkeInfo(p.PageID, p.SessionID, other)
				if got, err := hpkeOpen(d.xPriv, info, raw); err == nil && bytes.Equal(got, sk) {
					cache[cacheKey(d.xPub(), info, sk)] = wrapped
				}
			}
		}
	}
	for _, v := range old.Envelopes {
		if raw, err := wire.B64u.DecodeString(v.Payload); err == nil {
			addPayload(raw)
		}
	}
	for _, v := range old.EnvelopeReceive {
		var w signed.Wire
		if json.Unmarshal([]byte(v.Wire), &w) != nil {
			continue
		}
		if obj, err := signed.Decode(w); err == nil {
			addPayload(obj.Payload)
		}
	}
	return cache
}

// ---- envelope building -------------------------------------------------------------------

const (
	pageID    = "5f0c2a9e-3b1d-4c7a-9e21-6d8b0f4a7c13"
	sessionID = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
)

type envOpts struct {
	page, session string
	from          identity
	toUser        string
	level         string
	createdAt     int64
	recipients    []identity
	sessionKey    []byte
	wrapFor       map[string]identity // recipient id -> identity whose info/pub to use (for mix-ups)
}

type envelopeJSON struct {
	Format     int               `json:"format"`
	PageID     string            `json:"page_id"`
	SessionID  string            `json:"session_id"`
	FromDevice string            `json:"from_device"`
	ToUser     string            `json:"to_user"`
	Level      string            `json:"level"`
	CreatedAt  int64             `json:"created_at"`
	Keys       map[string]string `json:"keys"`
}

func (s *sealer) envelopePayload(o envOpts) []byte {
	keys := map[string]string{}
	for _, r := range o.recipients {
		key := o.sessionKey
		infoFor := r
		if w, ok := o.wrapFor[r.id]; ok {
			infoFor = w
		}
		keys[r.id] = b64(s.wrap(r.xPub(), hpkeInfo(o.page, o.session, infoFor.id), key))
	}
	return []byte(jsonText(envelopeJSON{
		Format: 1, PageID: o.page, SessionID: o.session, FromDevice: o.from.id, ToUser: o.toUser,
		Level: o.level, CreatedAt: o.createdAt, Keys: keys,
	}))
}

// ---- reference receiver (the protocol section 7 receiver checks, in Go) -------------------

type push struct{ pageID, level, fromUser string }

func receive(r roster.Roster, selfID string, selfPriv *ecdh.PrivateKey, obj signed.Object, p push) ([]byte, string) {
	signer, ok := r.Find(obj.Signer)
	if !ok {
		return nil, "unknown_signer"
	}
	if signed.Verify(signer.Ed25519, signed.ContextEnvelope, obj) != nil {
		return nil, "bad_signature"
	}
	env, err := pages.ParseEnvelope(obj.Payload)
	if err != nil {
		return nil, "bad_payload"
	}
	self, ok := r.Find(selfID)
	switch {
	case env.FromDevice != obj.Signer:
		return nil, "signer_mismatch"
	case env.PageID != p.pageID:
		return nil, "page_id_mismatch"
	case env.Level != p.level:
		return nil, "level_mismatch"
	case !ok || env.ToUser != self.UserID:
		return nil, "not_for_this_user"
	case signer.UserID != p.fromUser:
		return nil, "from_user_mismatch"
	}
	var raw struct {
		Keys map[string]string `json:"keys"`
	}
	if json.Unmarshal(obj.Payload, &raw) != nil {
		return nil, "bad_payload"
	}
	enc, ok := raw.Keys[selfID]
	if !ok {
		return nil, "no_key_for_this_device"
	}
	wrapped, ok := wire.DecodeB64u(enc, pages.WrappedKeyBytes)
	if !ok {
		return nil, "bad_payload"
	}
	sk, err := hpkeOpen(selfPriv, hpkeInfo(env.PageID, env.SessionID, selfID), wrapped)
	if err != nil || len(sk) != 32 {
		return nil, "decrypt_failed"
	}
	return sk, ""
}

// ---- generation --------------------------------------------------------------------------

func generate(old *File) File {
	s := &sealer{cache: loadCache(old)}
	f := File{Comment: "Generated by `go test ./internal/vectors -update` in server/. Do not edit by hand. See testdata/README.md."}
	f.Signed = signedVectors()
	f.Rosters = rosterVectors()
	f.EnrollmentMACs = macVectors()
	f.QRs = qrVectors()
	f.HPKE = hpkeVectors(s)
	f.Envelopes = envelopeVectors(s)
	f.EnvelopeReceive = receiveVectors(s)
	f.RosterAccept = acceptVectors()
	return f
}

func signedVectors() []SignedVector {
	var out []SignedVector
	add := func(name string, id identity, ctx string, payload []byte, mutate func(*signed.Wire), verifyCtx, expect string) {
		o := signObj(id, ctx, payload)
		w := o.Encode()
		if mutate != nil {
			mutate(&w)
		}
		v := SignedVector{
			Name: name, Seed: b64(seedOf(id.label + "/ed25519")), Public: b64(id.edPub()), Ctx: verifyCtx,
			Wire: jsonText(w), Expect: expect,
		}
		if expect == "valid" {
			v.Payload, v.Sig = b64(payload), b64(o.Sig)
		}
		out = append(out, v)
	}
	rp := familyRoster()
	ep := []byte(`{"format":1,"page_id":"` + pageID + `"}`)
	add("roster-valid", alexPhone, signed.ContextRoster, rp, nil, signed.ContextRoster, "valid")
	add("envelope-ctx-valid", samPhone, signed.ContextEnvelope, ep, nil, signed.ContextEnvelope, "valid")
	add("unicode-payload-valid", samPhone, signed.ContextEnvelope, []byte(`{"name":"Zoë 🎉"}`), nil, signed.ContextEnvelope, "valid")
	add("wrong-context", alexPhone, signed.ContextRoster, rp, nil, signed.ContextEnvelope, "bad_signature")
	add("tampered-payload", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		raw, _ := wire.B64u.DecodeString(w.Payload)
		raw[len(raw)/2] ^= 0x01
		w.Payload = b64(raw)
	}, signed.ContextRoster, "bad_signature")
	add("tampered-sig", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		raw, _ := wire.B64u.DecodeString(w.Sig)
		raw[10] ^= 0x80
		w.Sig = b64(raw)
	}, signed.ContextRoster, "bad_signature")
	add("signature-from-other-key", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		w.Sig = b64(ed25519.Sign(samPhone.edPriv, append([]byte(signed.ContextRoster+"\x00"), rp...)))
	}, signed.ContextRoster, "bad_signature")
	add("padded-payload", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		w.Payload += strings.Repeat("=", (4-len(w.Payload)%4)%4+4)
	}, signed.ContextRoster, "malformed")
	add("payload-with-newline", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		w.Payload = w.Payload[:8] + "\n" + w.Payload[8:]
	}, signed.ContextRoster, "malformed")
	add("sig-with-carriage-return", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		w.Sig = w.Sig[:8] + "\r" + w.Sig[8:]
	}, signed.ContextRoster, "malformed")
	add("padded-sig", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) { w.Sig += "==" }, signed.ContextRoster, "malformed")
	add("standard-alphabet-payload", alexPhone, signed.ContextRoster, []byte{0xfb, 0xff, 0xfe, 0xfb, 0xff, 0xfe, 0x01},
		func(w *signed.Wire) {
			w.Payload = base64.RawStdEncoding.EncodeToString([]byte{0xfb, 0xff, 0xfe, 0xfb, 0xff, 0xfe, 0x01})
		},
		signed.ContextRoster, "malformed")
	add("short-sig", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		raw, _ := wire.B64u.DecodeString(w.Sig)
		w.Sig = b64(raw[:63])
	}, signed.ContextRoster, "malformed")
	add("long-sig", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		raw, _ := wire.B64u.DecodeString(w.Sig)
		w.Sig = b64(append(raw, 0))
	}, signed.ContextRoster, "malformed")
	add("empty-payload", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) { w.Payload = "" }, signed.ContextRoster, "malformed")
	add("uppercase-signer", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) {
		w.Signer = strings.ToUpper(w.Signer)
	}, signed.ContextRoster, "malformed")
	add("signer-not-uuid", alexPhone, signed.ContextRoster, rp, func(w *signed.Wire) { w.Signer = "alex" }, signed.ContextRoster, "malformed")
	return out
}

// dev renders one roster device as JSON text, with overridable pieces for the invalid cases.
type devText struct {
	id, user, disp, devn, admin, ed, x string // admin is the literal JSON token
}

func (d devText) String() string {
	return fmt.Sprintf(`{"id":"%s","user_id":"%s","display_name":%s,"device_name":%s,"admin":%s,"ed25519":"%s","x25519":"%s"}`,
		d.id, d.user, d.disp, d.devn, d.admin, d.ed, d.x)
}

func devOf(i identity) devText {
	return devText{
		id: i.id, user: i.user, disp: `"` + i.disp + `"`, devn: `"` + i.devn + `"`,
		admin: fmt.Sprint(i.admin), ed: b64(i.edPub()), x: b64(i.xPub()),
	}
}

func rosterText(format, version, issued string, devs ...devText) string {
	parts := make([]string, len(devs))
	for i, d := range devs {
		parts[i] = d.String()
	}
	return fmt.Sprintf(`{"format":%s,"version":%s,"issued_at":%s,"devices":[%s]}`, format, version, issued, strings.Join(parts, ","))
}

func rosterVectors() []RosterVector {
	var out []RosterVector
	valid := func(name string, payload []byte) {
		r, err := roster.Parse(payload)
		if err != nil {
			panic(fmt.Sprintf("vector %s: %v", name, err))
		}
		v := RosterVector{Name: name, Payload: b64(payload), Expect: "valid", Version: r.Version, IssuedAt: r.IssuedAt}
		for _, d := range r.Devices {
			v.Devices = append(v.Devices, RosterDevice{
				ID: d.ID, UserID: d.UserID, DisplayName: d.DisplayName, DeviceName: d.DeviceName, Admin: d.Admin,
				Ed25519: b64(d.Ed25519), X25519: b64(d.X25519),
			})
		}
		out = append(out, v)
	}
	invalid := func(name string, payload []byte) {
		out = append(out, RosterVector{Name: name, Payload: b64(payload), Expect: "invalid"})
	}
	// sorted family: ids ascending
	sorted := sortedDevices(alexPhone, alexTablet, samPhone)
	d0, d1, d2 := devOf(sorted[0]), devOf(sorted[1]), devOf(sorted[2])
	noAdmin := func(d devText) devText { d.admin = "false"; return d }
	adminOf := func(d identity) devText { x := devOf(d); x.admin = "true"; return x }
	a0, a1, a2 := adminOf(sorted[0]), adminOf(sorted[1]), adminOf(sorted[2])
	_ = a1
	_ = a2
	base := func(devs ...devText) string { return rosterText("1", "7", "1789920000000", devs...) }

	valid("family", familyRoster())
	valid("single-admin-bootstrap", rosterPayload(1, alexPhone))
	const bs = "\\"
	valid("pretty-printed", []byte("\n {\n \"format\" : 1,\r\n\t\"version\":2 , \"issued_at\":1789920000000,\n\"devices\":[ "+
		devOf(alexPhone).String()+" ] }\n  "))
	valid("escaped-key-and-value", []byte(strings.Replace(rosterText("1", "7", "1789920000000", devOf(alexPhone)),
		`"format"`, `"\u0066ormat"`, 1)))
	valid("escaped-name", []byte(rosterText("1", "3", "1789920000000", func() devText {
		d := devOf(alexPhone)
		d.disp = `"Z\u00f6\u00eb \ud83c\udf89 \"q\" \\ \/"`
		return d
	}())))
	valid("unicode-names", []byte(rosterText("1", "3", "1789920000000", func() devText {
		d := devOf(alexPhone)
		d.disp = `"Zoë 🎉 日本"`
		d.devn = `"é's phone"`
		return d
	}())))
	nameCase := func(name, disp string, ok bool) {
		d := devOf(alexPhone)
		d.disp = disp
		p := []byte(rosterText("1", "3", "1789920000000", d))
		if ok {
			valid(name, p)
		} else {
			invalid(name, p)
		}
	}
	nameCase("name-64-code-points", `"`+strings.Repeat("🎉", 64)+`"`, true)
	nameCase("name-65-code-points", `"`+strings.Repeat("🎉", 65)+`"`, false)
	nameCase("name-zero-width-space-only", `"\u200b"`, true)
	nameCase("name-lone-high-surrogate", `"`+bs+`ud800"`, false)
	nameCase("name-lone-low-surrogate", `"`+bs+`udc00x"`, false)
	nameCase("name-reversed-surrogates", `"`+bs+`udc00`+bs+`ud800"`, false)
	nameCase("name-high-surrogate-then-text", `"a`+bs+`ud83cb"`, false)
	nameCase("name-escaped-backslash-then-u", `"`+bs+bs+`ud800"`, true)
	nameCase("name-empty", `""`, false)
	nameCase("name-spaces-only", `"   "`, false)
	nameCase("name-nbsp-only", `"\u00a0"`, false)
	nameCase("name-em-space-only", `"\u2003\u2003"`, false)
	nameCase("name-control-escape", `"Al\u0007ex"`, false)
	nameCase("name-newline-escape", `"Al\nex"`, false)
	nameCase("name-del", `"Al\u007fex"`, false)
	nameCase("name-c1-control", `"Al\u0085ex"`, false)
	nameCase("name-raw-control-char", "\"Al\x07ex\"", false)
	nameCase("name-raw-tab", "\"Al\tex\"", false)

	invalid("not-an-object", []byte(`[1]`))
	invalid("empty", []byte(``))
	invalid("trailing-data", []byte(base(a0)+`x`))
	invalid("trailing-object", []byte(base(a0)+`{}`))
	invalid("bom-prefix", append([]byte{0xef, 0xbb, 0xbf}, base(a0)...))
	invalid("format-2", []byte(rosterText("2", "7", "1789920000000", a0)))
	invalid("format-string", []byte(rosterText(`"1"`, "7", "1789920000000", a0)))
	invalid("format-float", []byte(rosterText("1.0", "7", "1789920000000", a0)))
	invalid("version-zero", []byte(rosterText("1", "0", "1789920000000", a0)))
	invalid("version-negative", []byte(rosterText("1", "-1", "1789920000000", a0)))
	invalid("version-string", []byte(rosterText("1", `"7"`, "1789920000000", a0)))
	invalid("version-exponent", []byte(rosterText("1", "7e0", "1789920000000", a0)))
	invalid("version-leading-zero", []byte(rosterText("1", "07", "1789920000000", a0)))
	invalid("version-overflow", []byte(rosterText("1", "9223372036854775808", "1789920000000", a0)))
	invalid("issued-at-zero", []byte(rosterText("1", "7", "0", a0)))
	invalid("case-variant-key-top", []byte(strings.Replace(base(a0), `"version"`, `"Version"`, 1)))
	invalid("case-variant-key-device", []byte(strings.Replace(base(a0), `"admin"`, `"Admin"`, 1)))
	invalid("duplicate-key-top", []byte(strings.Replace(base(a0), `"version":7`, `"version":7,"version":8`, 1)))
	invalid("duplicate-key-escaped", []byte(strings.Replace(base(a0), `"version":7`, `"version":7,"vers\u0069on":8`, 1)))
	invalid("duplicate-key-device", []byte(strings.Replace(base(a0), `"admin":true`, `"admin":true,"admin":false`, 1)))
	invalid("missing-field-top", []byte(strings.Replace(base(a0), `"issued_at":1789920000000,`, ``, 1)))
	invalid("missing-field-device", []byte(strings.Replace(base(a0), `"device_name":`+a0.devn+`,`, ``, 1)))
	invalid("null-device-name", []byte(strings.Replace(base(a0), a0.devn, `null`, 1)))
	invalid("null-devices", []byte(`{"format":1,"version":7,"issued_at":1789920000000,"devices":null}`))
	invalid("null-device-entry", []byte(`{"format":1,"version":7,"issued_at":1789920000000,"devices":[null]}`))
	invalid("unknown-field-top", []byte(strings.Replace(base(a0), `"format":1,`, `"format":1,"extra":1,`, 1)))
	invalid("unknown-field-device", []byte(strings.Replace(base(a0), `"admin":true`, `"admin":true,"role":"x"`, 1)))
	invalid("admin-as-string", []byte(strings.Replace(base(a0), `"admin":true`, `"admin":"true"`, 1)))
	invalid("devices-empty", []byte(base()))
	invalid("devices-not-array", []byte(`{"format":1,"version":7,"issued_at":1789920000000,"devices":{}}`))
	invalid("devices-unsorted", []byte(base(a1, a0)))
	invalid("duplicate-device-id", []byte(base(a0, a0)))
	invalid("duplicate-device-id-different-keys", []byte(base(a0, func() devText { x := d1; x.id = a0.id; return x }())))
	invalid("no-admin", []byte(base(noAdmin(d0), noAdmin(d1), noAdmin(d2))))
	invalid("bad-device-uuid-uppercase", []byte(base(func() devText { x := a0; x.id = strings.ToUpper(x.id); return x }())))
	invalid("bad-device-uuid-no-dashes", []byte(base(func() devText { x := a0; x.id = strings.ReplaceAll(x.id, "-", ""); return x }())))
	invalid("bad-device-uuid-braces", []byte(base(func() devText { x := a0; x.id = "{" + x.id + "}"; return x }())))
	invalid("bad-user-uuid-uppercase", []byte(base(func() devText { x := a0; x.user = strings.ToUpper(x.user); return x }())))
	invalid("bad-user-uuid-urn", []byte(base(func() devText { x := a0; x.user = "urn:uuid:" + x.user; return x }())))
	invalid("key-short", []byte(base(func() devText { x := a0; x.ed = b64(sorted[0].edPub()[:31]); return x }())))
	invalid("key-long", []byte(base(func() devText { x := a0; x.x = b64(append(sorted[0].xPub(), 0)); return x }())))
	invalid("key-padded", []byte(base(func() devText { x := a0; x.ed += "="; return x }())))
	invalid("key-trailing-bits", []byte(base(func() devText {
		x := a0
		// 32 bytes leave 2 spare bits in the 43rd character: set one.
		raw := append([]byte(nil), sorted[0].edPub()...)
		enc := []byte(b64(raw))
		const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
		last := strings.IndexByte(alphabet, enc[42])
		enc[42] = alphabet[last|1]
		x.ed = string(enc)
		return x
	}())))
	invalid("key-std-alphabet", []byte(base(func() devText {
		x := a0
		x.x = base64.RawStdEncoding.EncodeToString(bytes.Repeat([]byte{0xfb, 0xff, 0xbf, 0xfe}, 8))
		return x
	}())))
	invalid("key-with-escaped-newline", []byte(base(func() devText {
		x := a0
		enc := b64(sorted[0].edPub())
		x.ed = enc[:10] + `\n` + enc[10:]
		return x
	}())))
	invalid("key-empty", []byte(base(func() devText { x := a0; x.ed = ""; return x }())))
	invalid("name-invalid-utf8", func() []byte {
		d := a0
		d.disp = "\"Al\xffex\""
		return []byte(base(d))
	}())
	invalid("name-truncated-utf8", func() []byte {
		d := a0
		d.devn = "\"phone\xe2\x82\""
		return []byte(base(d))
	}())
	invalid("invalid-utf8-outside-strings", []byte(base(a0)+"\xff"))
	invalid("invalid-escape", []byte(strings.Replace(base(a0), a0.disp, `"A\qb"`, 1)))
	invalid("unterminated", []byte(base(a0)[:len(base(a0))-3]))
	return out
}

func macVectors() []MACVector {
	secret := seedOf("enroll/secret")
	code := seedOf("enroll/code")[:16]
	mk := func(name string, devID string, ed, x []byte, valid bool, macFor func() []byte) MACVector {
		return MACVector{
			Name: name, Secret: b64(secret), Code: b64(code), DeviceID: devID, Ed25519: b64(ed), X25519: b64(x),
			MAC: b64(macFor()), ExpectValid: valid,
		}
	}
	good := func(devID string, ed, x []byte) func() []byte {
		return func() []byte { return enrollMAC(secret, code, devID, ed, x) }
	}
	sp := samPhone
	return []MACVector{
		mk("valid", sp.id, sp.edPub(), sp.xPub(), true, good(sp.id, sp.edPub(), sp.xPub())),
		mk("valid-other-device", kitPhone.id, kitPhone.edPub(), kitPhone.xPub(), true,
			good(kitPhone.id, kitPhone.edPub(), kitPhone.xPub())),
		mk("mac-for-other-device-id", sp.id, sp.edPub(), sp.xPub(), false, good(kitPhone.id, sp.edPub(), sp.xPub())),
		mk("mac-for-other-ed25519", sp.id, sp.edPub(), sp.xPub(), false, good(sp.id, kitPhone.edPub(), sp.xPub())),
		mk("mac-for-other-x25519", sp.id, sp.edPub(), sp.xPub(), false, good(sp.id, sp.edPub(), kitPhone.xPub())),
		mk("mac-with-other-secret", sp.id, sp.edPub(), sp.xPub(), false, func() []byte {
			return enrollMAC(seedOf("enroll/other-secret"), code, sp.id, sp.edPub(), sp.xPub())
		}),
		mk("mac-truncated", sp.id, sp.edPub(), sp.xPub(), false, func() []byte {
			return enrollMAC(secret, code, sp.id, sp.edPub(), sp.xPub())[:31]
		}),
	}
}

// enrollMAC is the protocol section 5 MAC, written out independently of any other code.
func enrollMAC(secret, code []byte, deviceID string, ed, x []byte) []byte {
	m := hmac.New(sha256.New, secret)
	m.Write([]byte("ptt-enroll-mac-v1"))
	m.Write([]byte{0})
	m.Write(code)
	m.Write([]byte(deviceID))
	m.Write(ed)
	m.Write(x)
	return m.Sum(nil)
}

type qrAdminJSON struct {
	URL    string    `json:"url"`
	Code   string    `json:"code"`
	Secret string    `json:"secret"`
	Admins []QRAdmin `json:"admins"`
}

const qrPrefix = admin.EnrollPrefix

func qrVectors() []QRVector {
	code := b64(seedOf("enroll/code")[:16])
	secret := b64(seedOf("enroll/secret"))
	url := "https://ptt.example.com"
	boot := QRVector{Name: "bootstrap", URL: url, Code: code}
	text, err := admin.BootstrapQRText(url, code)
	if err != nil {
		panic(err)
	}
	boot.Text = text
	one := []QRAdmin{{ID: alexPhone.id, Ed25519: b64(alexPhone.edPub())}}
	adm := QRVector{Name: "admin-one-admin", URL: url, Code: code, Secret: secret, Admins: one}
	adm.Text = qrPrefix + b64([]byte(jsonText(qrAdminJSON{URL: url, Code: code, Secret: secret, Admins: one})))
	two := append(append([]QRAdmin(nil), one...), QRAdmin{ID: samPhone.id, Ed25519: b64(samPhone.edPub())})
	adm2 := QRVector{Name: "admin-two-admins-port-url", URL: "https://ptt.example.com:8443/base", Code: code, Secret: secret, Admins: two}
	adm2.Text = qrPrefix + b64([]byte(jsonText(qrAdminJSON{URL: adm2.URL, Code: code, Secret: secret, Admins: two})))
	return []QRVector{boot, adm, adm2}
}

func hpkeVectors(s *sealer) []HPKEVector {
	var out []HPKEVector
	for i, r := range []identity{samPhone, alexTablet} {
		page, sess := pageID, sessionID
		if i == 1 {
			page, sess = "c9d2e5f8-0a1b-4c3d-8e7f-a6b5c4d3e2f1", "1e2d3c4b-5a69-4788-9a0b-c1d2e3f40516"
		}
		sk := seedOf(fmt.Sprintf("hpke/session-key/%d", i))
		info := hpkeInfo(page, sess, r.id)
		out = append(out, HPKEVector{
			Name: fmt.Sprintf("wrap-%d", i+1), Private: b64(seedOf(r.label + "/x25519")), Public: b64(r.xPub()),
			PageID: page, SessionID: sess, DeviceID: r.id, Info: b64(info), SessionKey: b64(sk),
			Wrapped: b64(s.wrap(r.xPub(), info, sk)),
		})
	}
	return out
}

func envelopeVectors(s *sealer) []EnvelopeVector {
	var out []EnvelopeVector
	sk := seedOf("envelope/session-key")
	base := envOpts{
		page: pageID, session: sessionID, from: alexPhone, toUser: userSam, level: "normal",
		createdAt: 1789920000000, recipients: []identity{samPhone, samWatch}, sessionKey: sk,
	}
	goodPayload := s.envelopePayload(base)
	var env envelopeJSON
	if err := json.Unmarshal(goodPayload, &env); err != nil {
		panic(err)
	}
	wrapped := func(id string) string { return env.Keys[id] }
	validVec := func(name string, payload []byte) {
		e, err := pages.ParseEnvelope(payload)
		if err != nil {
			panic(fmt.Sprintf("envelope vector %s: %v", name, err))
		}
		out = append(out, EnvelopeVector{
			Name: name, Payload: b64(payload), Expect: "valid", PageID: e.PageID, Level: e.Level, From: e.FromDevice,
			ToUser: e.ToUser, Created: e.CreatedAt, Keys: e.Keys,
		})
	}
	invalidVec := func(name string, payload string) {
		out = append(out, EnvelopeVector{Name: name, Payload: b64([]byte(payload)), Expect: "invalid"})
	}
	validVec("two-recipients", goodPayload)
	urgent := base
	urgent.level = "urgent"
	urgent.recipients = []identity{samPhone}
	validVec("urgent-one-recipient", s.envelopePayload(urgent))
	text := string(goodPayload)
	validVec("escaped-key-and-value", []byte(strings.Replace(strings.Replace(text, `"level":"normal"`, `"level":"\u006eormal"`, 1),
		`"format"`, `"\u0066ormat"`, 1)))
	validVec("whitespace", []byte(" \n"+strings.Replace(text, `","`, "\",\n\t\"", 3)+"\r\n"))

	k1, k2 := samPhone.id, samWatch.id
	keysObj := func(kv ...string) string {
		var parts []string
		for i := 0; i < len(kv); i += 2 {
			parts = append(parts, fmt.Sprintf("%q:%q", kv[i], kv[i+1]))
		}
		return "{" + strings.Join(parts, ",") + "}"
	}
	mk := func(format, level, created, keys string) string {
		return fmt.Sprintf(`{"format":%s,"page_id":"%s","session_id":"%s","from_device":"%s","to_user":"%s","level":%s,"created_at":%s,"keys":%s}`,
			format, pageID, sessionID, alexPhone.id, userSam, level, created, keys)
	}
	goodKeys := keysObj(k1, wrapped(k1), k2, wrapped(k2))
	good := mk("1", `"normal"`, "1789920000000", goodKeys)
	invalidVec("format-2", mk("2", `"normal"`, "1789920000000", goodKeys))
	invalidVec("level-unknown", mk("1", `"loud"`, "1789920000000", goodKeys))
	invalidVec("level-uppercase", mk("1", `"Normal"`, "1789920000000", goodKeys))
	invalidVec("created-at-zero", mk("1", `"normal"`, "0", goodKeys))
	invalidVec("created-at-float", mk("1", `"normal"`, "1789920000000.0", goodKeys))
	invalidVec("keys-empty", mk("1", `"normal"`, "1789920000000", "{}"))
	invalidVec("keys-null", mk("1", `"normal"`, "1789920000000", "null"))
	invalidVec("keys-array", mk("1", `"normal"`, "1789920000000", "[]"))
	invalidVec("keys-value-null", mk("1", `"normal"`, "1789920000000", `{"`+k1+`":null}`))
	invalidVec("keys-duplicate-id", mk("1", `"normal"`, "1789920000000", `{"`+k1+`":"`+wrapped(k1)+`","`+k1+`":"`+wrapped(k1)+`"}`))
	invalidVec("keys-duplicate-id-escaped", mk("1", `"normal"`, "1789920000000",
		`{"`+k1+`":"`+wrapped(k1)+`","`+strings.Replace(k1, "7b2d", `\u0037b2d`, 1)+`":"`+wrapped(k1)+`"}`))
	invalidVec("keys-id-uppercase", mk("1", `"normal"`, "1789920000000", keysObj(strings.ToUpper(k1), wrapped(k1))))
	invalidVec("keys-id-not-uuid", mk("1", `"normal"`, "1789920000000", keysObj("sam", wrapped(k1))))
	invalidVec("keys-wrapped-short", mk("1", `"normal"`, "1789920000000", keysObj(k1, wrapped(k1)[:100])))
	invalidVec("keys-wrapped-79-bytes", func() string {
		raw, _ := wire.B64u.DecodeString(wrapped(k1))
		return mk("1", `"normal"`, "1789920000000", keysObj(k1, b64(raw[:79])))
	}())
	invalidVec("keys-wrapped-81-bytes", func() string {
		raw, _ := wire.B64u.DecodeString(wrapped(k1))
		return mk("1", `"normal"`, "1789920000000", keysObj(k1, b64(append(raw, 0))))
	}())
	invalidVec("keys-wrapped-newline", mk("1", `"normal"`, "1789920000000", keysObj(k1, wrapped(k1)[:20]+"\n"+wrapped(k1)[20:])))
	invalidVec("keys-wrapped-padded", mk("1", `"normal"`, "1789920000000", keysObj(k1, wrapped(k1)+"=")))
	invalidVec("keys-wrapped-not-string", mk("1", `"normal"`, "1789920000000", `{"`+k1+`":5}`))
	invalidVec("case-variant-key", strings.Replace(good, `"level"`, `"Level"`, 1))
	invalidVec("duplicate-key", strings.Replace(good, `"format":1,`, `"format":1,"format":1,`, 1))
	invalidVec("duplicate-key-escaped", strings.Replace(good, `"format":1,`, `"format":1,"\u0066ormat":1,`, 1))
	invalidVec("unknown-field", strings.Replace(good, `"format":1,`, `"format":1,"extra":true,`, 1))
	invalidVec("missing-to-user", strings.Replace(good, `"to_user":"`+userSam+`",`, ``, 1))
	invalidVec("null-level", strings.Replace(good, `"level":"normal"`, `"level":null`, 1))
	invalidVec("page-id-uppercase", strings.Replace(good, pageID, strings.ToUpper(pageID), 1))
	invalidVec("session-id-no-dashes", strings.Replace(good, sessionID, strings.ReplaceAll(sessionID, "-", ""), 1))
	invalidVec("trailing-data", good+" x")
	invalidVec("not-an-object", `"keys"`)
	invalidVec("invalid-utf8", strings.Replace(good, `"level"`, "\"lev\xffel\"", 1))
	invalidVec("invalid-utf8-in-value", strings.Replace(good, `"normal"`, "\"nor\xffmal\"", 1))
	return out
}

func receiveVectors(s *sealer) []ReceiveVector {
	sk := seedOf("envelope/session-key")
	rosterBytes := rosterPayload(9, alexPhone, alexTablet, samPhone, samWatch, kitPhone)
	good := envOpts{
		page: pageID, session: sessionID, from: alexPhone, toUser: userSam, level: "normal",
		createdAt: 1789920000000, recipients: []identity{samPhone, samWatch}, sessionKey: sk,
	}
	var out []ReceiveVector
	add := func(name string, ctx string, signer identity, o envOpts, recv identity, pushMod func(*push), mutate func(*signed.Object), expect, reason string) {
		payload := s.envelopePayload(o)
		obj := signed.Sign(signer.edPriv, signer.id, ctx, payload)
		if mutate != nil {
			mutate(&obj)
		}
		p := push{pageID: o.page, level: o.level, fromUser: o.from.user}
		if pushMod != nil {
			pushMod(&p)
		}
		v := ReceiveVector{
			Name: name, RosterPayload: b64(rosterBytes), Wire: wireText(obj), DeviceID: recv.id,
			Private: b64(seedOf(recv.label + "/x25519")), PushPageID: p.pageID, PushLevel: p.level,
			PushFromUser: p.fromUser, Expect: expect, Reason: reason,
		}
		if expect == "ok" {
			v.SessionKey = b64(sk)
		}
		out = append(out, v)
	}
	env := signed.ContextEnvelope
	add("first-recipient", env, alexPhone, good, samPhone, nil, nil, "ok", "")
	add("second-recipient-device", env, alexPhone, good, samWatch, nil, nil, "ok", "")
	urgent := good
	urgent.level = "urgent"
	add("urgent", env, alexPhone, urgent, samPhone, nil, nil, "ok", "")
	add("sender-tablet-signs", env, alexTablet, func() envOpts { o := good; o.from = alexTablet; return o }(), samPhone,
		func(p *push) { p.fromUser = userAlex }, nil, "ok", "")
	add("wrong-push-page-id", env, alexPhone, good, samPhone, func(p *push) { p.pageID = sessionID }, nil, "reject", "page_id_mismatch")
	add("wrong-push-level", env, alexPhone, good, samPhone, func(p *push) { p.level = "urgent" }, nil, "reject", "level_mismatch")
	add("wrong-push-from-user", env, alexPhone, good, samPhone, func(p *push) { p.fromUser = userKit }, nil, "reject", "from_user_mismatch")
	add("receiver-not-in-keys", env, alexPhone, func() envOpts { o := good; o.recipients = []identity{samPhone}; return o }(),
		samWatch, nil, nil, "reject", "no_key_for_this_device")
	add("receiver-other-user", env, alexPhone, func() envOpts {
		o := good
		o.recipients = []identity{kitPhone, samPhone}
		return o
	}(), kitPhone, nil, nil, "reject", "not_for_this_user")
	add("wrong-context", signed.ContextRoster, alexPhone, good, samPhone, nil, nil, "reject", "bad_signature")
	add("signer-not-in-roster", env, stranger, func() envOpts { o := good; o.from = stranger; return o }(), samPhone,
		func(p *push) { p.fromUser = stranger.user }, nil, "reject", "unknown_signer")
	add("from-device-differs-from-signer", env, alexTablet, good, samPhone, nil, nil, "reject", "signer_mismatch")
	add("tampered-signature", env, alexPhone, good, samPhone, nil, func(o *signed.Object) { o.Sig[3] ^= 0x01 }, "reject", "bad_signature")
	add("tampered-payload", env, alexPhone, good, samPhone, nil, func(o *signed.Object) {
		p := bytes.Replace(o.Payload, []byte(`"normal"`), []byte(`"urgent"`), 1)
		o.Payload = p
	}, "reject", "bad_signature")
	add("wrap-for-another-device", env, alexPhone, func() envOpts {
		o := good
		o.wrapFor = map[string]identity{samPhone.id: samWatch}
		return o
	}(), samPhone, nil, nil, "reject", "decrypt_failed")
	return out
}

// ---- checks of the Go side against every vector -------------------------------------------

func decodeWire(t *testing.T, text string) (signed.Object, error) {
	t.Helper()
	var w signed.Wire
	if err := json.Unmarshal([]byte(text), &w); err != nil {
		return signed.Object{}, signed.ErrMalformed
	}
	return signed.Decode(w)
}

func mustB64(t *testing.T, s string) []byte {
	t.Helper()
	raw, err := wire.B64u.DecodeString(s)
	if err != nil {
		t.Fatalf("bad b64u %q: %v", s, err)
	}
	return raw
}

func checkFile(t *testing.T, f File) {
	t.Helper()
	for _, v := range f.Signed {
		t.Run("signed/"+v.Name, func(t *testing.T) {
			pub := ed25519.PublicKey(mustB64(t, v.Public))
			obj, err := decodeWire(t, v.Wire)
			got := "valid"
			switch {
			case err != nil:
				got = "malformed"
			case signed.Verify(pub, v.Ctx, obj) != nil:
				got = "bad_signature"
			}
			if got != v.Expect {
				t.Fatalf("got %s, want %s", got, v.Expect)
			}
			if v.Expect == "valid" {
				priv := ed25519.NewKeyFromSeed(mustB64(t, v.Seed))
				if !bytes.Equal(priv[32:], pub) {
					t.Fatal("seed does not match public key")
				}
				re := signed.Sign(priv, obj.Signer, v.Ctx, mustB64(t, v.Payload))
				if !bytes.Equal(re.Sig, mustB64(t, v.Sig)) || wireText(re) != v.Wire {
					t.Fatal("signature or wire form is not reproducible")
				}
			}
		})
	}
	for _, v := range f.Rosters {
		t.Run("roster/"+v.Name, func(t *testing.T) {
			r, err := roster.Parse(mustB64(t, v.Payload))
			if (err == nil) != (v.Expect == "valid") {
				t.Fatalf("parse error %v, expect %s", err, v.Expect)
			}
			if err == nil && (r.Version != v.Version || len(r.Devices) != len(v.Devices)) {
				t.Fatal("parsed roster differs from the vector")
			}
		})
	}
	for _, v := range f.EnrollmentMACs {
		t.Run("mac/"+v.Name, func(t *testing.T) {
			want := enrollMAC(mustB64(t, v.Secret), mustB64(t, v.Code), v.DeviceID, mustB64(t, v.Ed25519), mustB64(t, v.X25519))
			if hmac.Equal(want, mustB64(t, v.MAC)) != v.ExpectValid {
				t.Fatal("MAC outcome differs")
			}
		})
	}
	for _, v := range f.QRs {
		t.Run("qr/"+v.Name, func(t *testing.T) {
			if !strings.HasPrefix(v.Text, qrPrefix) {
				t.Fatal("prefix")
			}
			payload := mustB64(t, strings.TrimPrefix(v.Text, qrPrefix))
			keys := []string{"url", "code"}
			if v.Secret != "" {
				keys = append(keys, "secret", "admins")
			}
			if _, err := wire.StrictObject(payload, keys...); err != nil {
				t.Fatalf("QR payload: %v", err)
			}
			if len(mustB64(t, v.Code)) != 16 || (v.Secret != "" && len(mustB64(t, v.Secret)) != 32) {
				t.Fatal("code or secret length")
			}
		})
	}
	for _, v := range f.HPKE {
		t.Run("hpke/"+v.Name, func(t *testing.T) {
			priv, err := ecdh.X25519().NewPrivateKey(mustB64(t, v.Private))
			if err != nil || !bytes.Equal(priv.PublicKey().Bytes(), mustB64(t, v.Public)) {
				t.Fatal("private key does not match public key")
			}
			info := hpkeInfo(v.PageID, v.SessionID, v.DeviceID)
			if !bytes.Equal(info, mustB64(t, v.Info)) {
				t.Fatal("info")
			}
			wrapped := mustB64(t, v.Wrapped)
			if len(wrapped) != pages.WrappedKeyBytes {
				t.Fatalf("wrapped is %d bytes", len(wrapped))
			}
			sk, err := hpkeOpen(priv, info, wrapped)
			if err != nil || !bytes.Equal(sk, mustB64(t, v.SessionKey)) {
				t.Fatalf("open: %v", err)
			}
		})
	}
	for _, v := range f.Envelopes {
		t.Run("envelope/"+v.Name, func(t *testing.T) {
			e, err := pages.ParseEnvelope(mustB64(t, v.Payload))
			if (err == nil) != (v.Expect == "valid") {
				t.Fatalf("parse error %v, expect %s", err, v.Expect)
			}
			if err == nil && (e.PageID != v.PageID || e.Level != v.Level || e.FromDevice != v.From ||
				e.ToUser != v.ToUser || e.CreatedAt != v.Created || strings.Join(e.Keys, ",") != strings.Join(v.Keys, ",")) {
				t.Fatal("parsed envelope differs from the vector")
			}
		})
	}
	for _, v := range f.RosterAccept {
		t.Run("accept/"+v.Name, func(t *testing.T) {
			var cur *roster.Roster
			if v.Current != "" {
				r, err := roster.Parse(mustB64(t, v.Current))
				if err != nil {
					t.Fatalf("current: %v", err)
				}
				cur = &r
			}
			got, reason := "accept", ""
			if obj, err := decodeWire(t, v.Wire); err != nil {
				got, reason = "reject", "malformed_wire"
			} else if _, reason = acceptRoster(cur, v.Trusted, v.Self, obj); reason != "" {
				got = "reject"
			}
			if got != v.Expect || reason != v.Reason {
				t.Fatalf("got %s/%q, want %s/%q", got, reason, v.Expect, v.Reason)
			}
		})
	}
	for _, v := range f.EnvelopeReceive {
		t.Run("receive/"+v.Name, func(t *testing.T) {
			r, err := roster.Parse(mustB64(t, v.RosterPayload))
			if err != nil {
				t.Fatalf("roster: %v", err)
			}
			obj, err := decodeWire(t, v.Wire)
			if err != nil {
				t.Fatalf("wire: %v", err)
			}
			priv, err := ecdh.X25519().NewPrivateKey(mustB64(t, v.Private))
			if err != nil {
				t.Fatal(err)
			}
			sk, reason := receive(r, v.DeviceID, priv, obj, push{v.PushPageID, v.PushLevel, v.PushFromUser})
			if v.Expect == "ok" {
				if reason != "" || !bytes.Equal(sk, mustB64(t, v.SessionKey)) {
					t.Fatalf("rejected: %s", reason)
				}
			} else if reason != v.Reason {
				t.Fatalf("reason %q, want %q", reason, v.Reason)
			}
		})
	}
}

func encodeFile(f File) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(f); err != nil {
		panic(err)
	}
	return buf.Bytes()
}

func readExisting(t *testing.T) (*File, []byte) {
	t.Helper()
	raw, err := os.ReadFile(vectorsPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		t.Fatal(err)
	}
	var f File
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&f); err != nil {
		t.Fatalf("committed vectors do not parse: %v", err)
	}
	return &f, raw
}

func TestVectors(t *testing.T) {
	old, raw := readExisting(t)
	fresh := generate(old)
	checkFile(t, fresh)
	want := encodeFile(fresh)
	if *update {
		if err := os.WriteFile(vectorsPath, want, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	if old == nil {
		t.Fatal("testdata/protocol-vectors.json is missing; run: go test ./internal/vectors -update")
	}
	if !bytes.Equal(raw, want) {
		t.Fatal("testdata/protocol-vectors.json is stale; run: go test ./internal/vectors -update")
	}
	// The committed file itself (not just a regeneration of it) must satisfy the Go side.
	checkFile(t, *old)
}

// ---- roster acceptance (phone side) ---------------------------------------------------------

func acceptVectors() []AcceptVector {
	var out []AcceptVector
	current := b64(rosterPayload(7, family...))
	alexQR := []QRAdmin{{ID: alexPhone.id, Ed25519: b64(alexPhone.edPub())}}
	selfOf := func(i identity) *SelfID {
		return &SelfID{DeviceID: i.id, Ed25519: b64(i.edPub()), X25519: b64(i.xPub())}
	}
	add := func(name string, cur string, trusted []QRAdmin, self *SelfID, obj signed.Object, expect, reason string) {
		out = append(out, AcceptVector{
			Name: name, Current: cur, Trusted: trusted, Self: self, Wire: wireText(obj), Expect: expect, Reason: reason,
		})
	}
	addWire := func(name, cur string, wireJSON string) {
		out = append(out, AcceptVector{Name: name, Current: cur, Wire: wireJSON, Expect: "reject", Reason: "malformed_wire"})
	}
	ctx := signed.ContextRoster
	v8 := rosterPayload(8, family...)

	add("next-version", current, nil, nil, signObj(alexPhone, ctx, v8), "accept", "")
	add("next-version-big-jump", current, nil, nil, signObj(alexPhone, ctx, rosterPayload(900, family...)), "accept", "")
	add("rolled-back", current, nil, nil, signObj(alexPhone, ctx, rosterPayload(6, family...)), "reject", "stale_version")
	add("same-version", current, nil, nil, signObj(alexPhone, ctx, rosterPayload(7, family...)), "reject", "stale_version")
	add("signer-not-admin", current, nil, nil, signObj(samPhone, ctx, v8), "reject", "untrusted_signer")
	add("signer-unknown", current, nil, nil, signObj(stranger, ctx, v8), "reject", "untrusted_signer")
	unsigned := signObj(alexPhone, ctx, v8)
	unsigned.Sig = make([]byte, ed25519.SignatureSize)
	add("all-zero-signature", current, nil, nil, unsigned, "reject", "bad_signature")
	bad := signObj(alexPhone, ctx, v8)
	bad.Sig[7] ^= 0x01
	add("tampered-signature", current, nil, nil, bad, "reject", "bad_signature")
	tampered := signObj(alexPhone, ctx, v8)
	tampered.Payload = append([]byte(nil), tampered.Payload...)
	tampered.Payload[len(tampered.Payload)-3] ^= 0x01
	add("tampered-payload", current, nil, nil, tampered, "reject", "bad_signature")
	add("wrong-context", current, nil, nil, signObj(alexPhone, signed.ContextEnvelope, v8), "reject", "bad_signature")
	add("signature-by-another-key-under-admin-id", current, nil, nil,
		signed.Object{Payload: v8, Signer: alexPhone.id, Sig: signObj(stranger, ctx, v8).Sig}, "reject", "bad_signature")
	noAdminPayload := []byte(rosterText("1", "8", "1789920000008", noAdminDevs(alexPhone, samPhone)...))
	add("no-admin", current, nil, nil, signObj(alexPhone, ctx, noAdminPayload), "reject", "malformed_payload")
	add("malformed-payload", current, nil, nil, signObj(alexPhone, ctx, []byte(`{"format":1}`)), "reject", "malformed_payload")

	good := signObj(alexPhone, ctx, v8).Encode()
	addWire("bare-payload-not-a-signed-object", current, string(v8))
	addWire("bare-b64u-payload", current, `"`+good.Payload+`"`)
	addWire("missing-sig", current, fmt.Sprintf(`{"payload":"%s","signer":"%s"}`, good.Payload, alexPhone.id))
	addWire("null-sig", current, fmt.Sprintf(`{"payload":"%s","signer":"%s","sig":null}`, good.Payload, alexPhone.id))

	// First roster after enrollment (no current roster): QR admin keys and the phone's own keys.
	first := signObj(alexPhone, ctx, rosterPayload(7, family...))
	add("first-roster", "", alexQR, selfOf(samPhone), first, "accept", "")
	add("first-roster-version-1", "", alexQR, selfOf(alexPhone), signObj(alexPhone, ctx, rosterPayload(1, alexPhone)), "accept", "")
	add("first-qr-admin-key-mismatch", "", []QRAdmin{{ID: alexPhone.id, Ed25519: b64(samPhone.edPub())}},
		selfOf(samPhone), first, "reject", "bad_signature")
	add("first-signer-not-a-qr-admin", "", []QRAdmin{{ID: stranger.id, Ed25519: b64(stranger.edPub())}},
		selfOf(samPhone), first, "reject", "untrusted_signer")
	add("first-self-not-listed", "", alexQR, selfOf(stranger), first, "reject", "self_not_listed")
	wrongX := selfOf(samPhone)
	wrongX.X25519 = b64(alexPhone.xPub())
	add("first-self-x25519-mismatch", "", alexQR, wrongX, first, "reject", "self_not_listed")
	wrongEd := selfOf(samPhone)
	wrongEd.Ed25519 = b64(alexPhone.edPub())
	add("first-self-ed25519-mismatch", "", alexQR, wrongEd, first, "reject", "self_not_listed")
	samAdmin := samPhone
	samAdmin.admin = true
	withoutSigner := signObj(alexPhone, ctx, rosterPayload(7, samAdmin, kitPhone))
	add("first-signer-not-listed", "", alexQR, selfOf(samAdmin), withoutSigner, "reject", "signer_not_listed")
	alexDemoted := alexPhone
	alexDemoted.admin = false
	demoted := signObj(alexPhone, ctx, rosterPayload(7, alexDemoted, samAdmin))
	add("first-signer-listed-as-non-admin", "", alexQR, selfOf(samAdmin), demoted, "reject", "signer_not_listed")
	add("first-no-admin", "", alexQR, selfOf(samPhone), signObj(alexPhone, ctx, noAdminPayload), "reject", "malformed_payload")
	add("first-no-trusted-admins", "", nil, selfOf(samPhone), first, "reject", "no_trust_anchor")
	add("first-no-self-identity", "", alexQR, nil, first, "reject", "no_trust_anchor")
	return out
}

func noAdminDevs(ids ...identity) []devText {
	var out []devText
	for _, d := range sortedDevices(ids...) {
		x := devOf(d)
		x.admin = "false"
		out = append(out, x)
	}
	return out
}

// acceptRoster is the phone's acceptance order (protocol sections 4 and 5) written for the vectors.
func acceptRoster(cur *roster.Roster, trusted []QRAdmin, self *SelfID, obj signed.Object) (roster.Roster, string) {
	var signerKey []byte
	if cur != nil {
		if e, ok := cur.Find(obj.Signer); ok && e.Admin {
			signerKey = e.Ed25519
		}
	} else {
		for _, t := range trusted {
			if t.ID == obj.Signer {
				signerKey, _ = wire.B64u.DecodeString(t.Ed25519)
				break
			}
		}
	}
	if signerKey == nil {
		if cur == nil && (len(trusted) == 0 || self == nil) {
			return roster.Roster{}, "no_trust_anchor"
		}
		return roster.Roster{}, "untrusted_signer"
	}
	if signed.Verify(signerKey, signed.ContextRoster, obj) != nil {
		return roster.Roster{}, "bad_signature"
	}
	r, err := roster.Parse(obj.Payload)
	if err != nil {
		return roster.Roster{}, "malformed_payload"
	}
	if cur != nil {
		if r.Version <= cur.Version {
			return roster.Roster{}, "stale_version"
		}
		return r, ""
	}
	if self == nil {
		return roster.Roster{}, "no_trust_anchor"
	}
	if listed, ok := r.Find(obj.Signer); !ok || !listed.Admin || !bytes.Equal(listed.Ed25519, signerKey) {
		return roster.Roster{}, "signer_not_listed"
	}
	own, ok := r.Find(self.DeviceID)
	selfEd, _ := wire.B64u.DecodeString(self.Ed25519)
	selfX, _ := wire.B64u.DecodeString(self.X25519)
	if !ok || !bytes.Equal(own.Ed25519, selfEd) || !bytes.Equal(own.X25519, selfX) {
		return roster.Roster{}, "self_not_listed"
	}
	return r, ""
}
