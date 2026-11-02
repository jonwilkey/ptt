package vectors

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/jonwilkey/ptt/server/internal/pages"
	"github.com/jonwilkey/ptt/server/internal/roster"
	"github.com/jonwilkey/ptt/server/internal/signed"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// Vectors for the sender key of docs/protocol.md section 7 and the sender's recovery of section
// 13.7. They live in their own section, sender_keys, so the older sections keep their contents.

// contextSenderKey signs the sender key. Only phones sign or verify it; the server stores it.
const contextSenderKey = "ptt-sender-key-v1"

// senderInfo is the HPKE info of the sender's own wrap: a label distinct from hpkeInfo's.
func senderInfo(pageID, sessionID, deviceID string) []byte {
	return []byte("ptt-sender-session-key-v1\x00" + pageID + sessionID + deviceID)
}

// SenderKeyRecover is the sender's recovery (section 13.7, steps 2-4): its own identity, the push's
// page id, and the envelope and sender key that GET /v1/pages/{id} returned (signed-object wire
// JSON). Expect is "ok" with SessionKey, or "reject" with the first failing check as Reason.
type SenderKeyRecover struct {
	Name       string `json:"name"`
	DeviceID   string `json:"self_device_id"`
	Ed25519    string `json:"self_ed25519"`
	Private    string `json:"self_private"`
	PushPageID string `json:"push_page_id"`
	Envelope   string `json:"envelope"`
	SenderKey  string `json:"sender_key"`
	Expect     string `json:"expect"`
	Reason     string `json:"reason,omitempty"`
	SessionKey string `json:"session_key,omitempty"`
}

// SenderKeySection is the sender_keys section of the file. HPKE holds the sender's own wraps (Info
// uses the sender label); Receive holds envelope_receive-style scenarios showing that a receiver
// cannot use a sender-key wrap moved into keys.
type SenderKeySection struct {
	HPKE    []HPKEVector       `json:"hpke"`
	Recover []SenderKeyRecover `json:"recover"`
	Receive []ReceiveVector    `json:"receive"`
}

type senderKeyJSON struct {
	Format     int    `json:"format"`
	PageID     string `json:"page_id"`
	SessionID  string `json:"session_id"`
	FromDevice string `json:"from_device"`
	Key        string `json:"key"`
}

type senderKey struct {
	pageID, sessionID, fromDevice string
	wrapped                       []byte
}

// parseSenderKey is the strict payload parse of section 7 (a phone's; the server's 15b check is the
// same).
func parseSenderKey(payload []byte) (senderKey, error) {
	top, err := wire.StrictObject(payload, "format", "page_id", "session_id", "from_device", "key")
	if err != nil {
		return senderKey{}, err
	}
	var format int64
	var p senderKey
	var key string
	for name, dst := range map[string]any{
		"format": &format, "page_id": &p.pageID, "session_id": &p.sessionID, "from_device": &p.fromDevice, "key": &key,
	} {
		if err := json.Unmarshal(top[name], dst); err != nil {
			return senderKey{}, fmt.Errorf("%s: %w", name, err)
		}
	}
	if format != 1 {
		return senderKey{}, errors.New("unsupported format")
	}
	for _, id := range []string{p.pageID, p.sessionID, p.fromDevice} {
		if !wire.ValidUUID(id) {
			return senderKey{}, errors.New("not a canonical uuid")
		}
	}
	var ok bool
	if p.wrapped, ok = wire.DecodeB64u(key, pages.WrappedKeyBytes); !ok {
		return senderKey{}, errors.New("bad wrapped key")
	}
	return p, nil
}

// recoverSessionKey is the reference sender of section 13.7: it checks only its own signatures.
func recoverSessionKey(selfID string, selfEd ed25519.PublicKey, selfPriv *ecdh.PrivateKey, pushPageID string, envObj, skObj signed.Object) ([]byte, string) {
	if envObj.Signer != selfID {
		return nil, "envelope_not_own"
	}
	if signed.Verify(selfEd, signed.ContextEnvelope, envObj) != nil {
		return nil, "envelope_bad_signature"
	}
	env, err := pages.ParseEnvelope(envObj.Payload)
	switch {
	case err != nil:
		return nil, "envelope_bad_payload"
	case env.FromDevice != selfID:
		return nil, "envelope_not_own"
	case env.PageID != pushPageID:
		return nil, "page_id_mismatch"
	}
	if skObj.Signer != selfID {
		return nil, "sender_key_not_own"
	}
	if signed.Verify(selfEd, contextSenderKey, skObj) != nil {
		return nil, "sender_key_bad_signature"
	}
	p, err := parseSenderKey(skObj.Payload)
	switch {
	case err != nil:
		return nil, "sender_key_bad_payload"
	case p.pageID != env.PageID || p.sessionID != env.SessionID:
		return nil, "sender_key_mismatch"
	case p.fromDevice != selfID:
		return nil, "sender_key_not_own"
	}
	sk, err := hpkeOpen(selfPriv, senderInfo(p.pageID, p.sessionID, selfID), p.wrapped)
	if err != nil || len(sk) != 32 {
		return nil, "decrypt_failed"
	}
	return sk, ""
}

const (
	otherPageID    = "c9d2e5f8-0a1b-4c3d-8e7f-a6b5c4d3e2f1"
	otherSessionID = "1e2d3c4b-5a69-4788-9a0b-c1d2e3f40516"
)

// senderKeyPayload wraps sk with the sender label to `to` and names fromDevice.
func (s *sealer) senderKeyPayload(page, session, fromDevice string, to identity, infoFor string, sk []byte) []byte {
	return []byte(jsonText(senderKeyJSON{
		Format: 1, PageID: page, SessionID: session, FromDevice: fromDevice,
		Key: b64(s.wrap(to.xPub(), senderInfo(page, session, infoFor), sk)),
	}))
}

func senderKeyVectors(s *sealer) SenderKeySection {
	sk := seedOf("envelope/session-key")
	var sec SenderKeySection
	sec.HPKE = append(sec.HPKE, HPKEVector{
		Name: "sender-wrap", Private: b64(seedOf(alexPhone.label + "/x25519")), Public: b64(alexPhone.xPub()),
		PageID: pageID, SessionID: sessionID, DeviceID: alexPhone.id, Info: b64(senderInfo(pageID, sessionID, alexPhone.id)),
		SessionKey: b64(sk), Wrapped: b64(s.wrap(alexPhone.xPub(), senderInfo(pageID, sessionID, alexPhone.id), sk)),
	})

	base := envOpts{
		page: pageID, session: sessionID, from: alexPhone, toUser: userSam, level: "normal",
		createdAt: 1789920000000, recipients: []identity{samPhone, samWatch}, sessionKey: sk,
	}
	envelope := signObj(alexPhone, signed.ContextEnvelope, s.envelopePayload(base))
	good := s.senderKeyPayload(pageID, sessionID, alexPhone.id, alexPhone, alexPhone.id, sk)
	signKey := func(payload []byte) signed.Object { return signObj(alexPhone, contextSenderKey, payload) }

	add := func(name string, env, key signed.Object, pushPage, expect, reason string) {
		v := SenderKeyRecover{
			Name: name, DeviceID: alexPhone.id, Ed25519: b64(alexPhone.edPub()), Private: b64(seedOf(alexPhone.label + "/x25519")),
			PushPageID: pushPage, Envelope: wireText(env), SenderKey: wireText(key), Expect: expect, Reason: reason,
		}
		if expect == "ok" {
			v.SessionKey = b64(sk)
		}
		sec.Recover = append(sec.Recover, v)
	}
	reject := func(name string, env, key signed.Object, reason string) {
		add(name, env, key, pageID, "reject", reason)
	}

	add("ok", envelope, signKey(good), pageID, "ok", "")

	tablet := base
	tablet.from = alexTablet
	reject("envelope-from-another-device", signObj(alexTablet, signed.ContextEnvelope, s.envelopePayload(tablet)), signKey(good), "envelope_not_own")
	tampered := envelope
	tampered.Payload = bytes.Replace(envelope.Payload, []byte(`"normal"`), []byte(`"urgent"`), 1)
	reject("envelope-tampered", tampered, signKey(good), "envelope_bad_signature")
	reject("envelope-signed-by-self-names-another-device", signObj(alexPhone, signed.ContextEnvelope, s.envelopePayload(tablet)), signKey(good), "envelope_not_own")
	reject("envelope-signed-by-self-unknown-field",
		signObj(alexPhone, signed.ContextEnvelope, bytes.Replace(envelope.Payload, []byte(`"format":1,`), []byte(`"format":1,"extra":true,`), 1)),
		signKey(good), "envelope_bad_payload")
	add("wrong-push-page-id", envelope, signKey(good), otherPageID, "reject", "page_id_mismatch")

	reject("sender-key-signed-by-another-device", envelope, signObj(samPhone, contextSenderKey, good), "sender_key_not_own")
	reject("sender-key-wrong-context", envelope, signObj(alexPhone, signed.ContextEnvelope, good), "sender_key_bad_signature")
	keyTampered := signKey(good)
	keyTampered.Payload = bytes.Replace(keyTampered.Payload, []byte(sessionID), []byte(otherSessionID), 1)
	reject("sender-key-tampered", envelope, keyTampered, "sender_key_bad_signature")
	reject("sender-key-unknown-field", envelope, signKey(bytes.Replace(good, []byte(`"format":1,`), []byte(`"format":1,"extra":true,`), 1)), "sender_key_bad_payload")
	reject("sender-key-format-2", envelope, signKey(bytes.Replace(good, []byte(`"format":1,`), []byte(`"format":2,`), 1)), "sender_key_bad_payload")
	reject("sender-key-missing-key", envelope, signKey([]byte(strings.Split(string(good), `,"key"`)[0]+"}")), "sender_key_bad_payload")
	reject("sender-key-other-session", envelope,
		signKey(s.senderKeyPayload(pageID, otherSessionID, alexPhone.id, alexPhone, alexPhone.id, sk)), "sender_key_mismatch")
	reject("sender-key-other-page", envelope,
		signKey(s.senderKeyPayload(otherPageID, otherSessionID, alexPhone.id, alexPhone, alexPhone.id, sk)), "sender_key_mismatch")
	reject("sender-key-names-another-device", envelope,
		signKey(s.senderKeyPayload(pageID, sessionID, alexTablet.id, alexTablet, alexTablet.id, sk)), "sender_key_not_own")
	reject("sender-key-wrapped-to-a-receiver", envelope,
		signKey(s.senderKeyPayload(pageID, sessionID, alexPhone.id, samPhone, alexPhone.id, sk)), "decrypt_failed")

	// A self page (Test alert): Alex's phone pages its own user, so it is also in keys. The roles
	// stay apart: its receiver wrap does not open as a sender key, and its sender wrap does not
	// open as a receiver key.
	self := base
	self.toUser, self.recipients = userAlex, []identity{alexPhone, alexTablet}
	selfPayload := s.envelopePayload(self)
	selfEnvelope := signObj(alexPhone, signed.ContextEnvelope, selfPayload)
	var selfEnv envelopeJSON
	if err := json.Unmarshal(selfPayload, &selfEnv); err != nil {
		panic(err)
	}
	moved := []byte(jsonText(senderKeyJSON{Format: 1, PageID: pageID, SessionID: sessionID, FromDevice: alexPhone.id, Key: selfEnv.Keys[alexPhone.id]}))
	reject("receiver-wrap-moved-into-sender-key", selfEnvelope, signKey(moved), "decrypt_failed")
	add("self-page-ok", selfEnvelope, signKey(good), pageID, "ok", "")

	rosterBytes := rosterPayload(9, alexPhone, alexTablet, samPhone, samWatch, kitPhone)
	receiveCase := func(name string, payload []byte, expect, reason string) {
		v := ReceiveVector{
			Name: name, RosterPayload: b64(rosterBytes), Wire: wireText(signObj(alexPhone, signed.ContextEnvelope, payload)),
			DeviceID: alexPhone.id, Private: b64(seedOf(alexPhone.label + "/x25519")), PushPageID: pageID, PushLevel: "normal",
			PushFromUser: userAlex, Expect: expect, Reason: reason,
		}
		if expect == "ok" {
			v.SessionKey = b64(sk)
		}
		sec.Receive = append(sec.Receive, v)
	}
	receiveCase("self-page-receiver-ok", selfPayload, "ok", "")
	selfEnv.Keys[alexPhone.id] = b64(s.wrap(alexPhone.xPub(), senderInfo(pageID, sessionID, alexPhone.id), sk))
	receiveCase("sender-wrap-moved-into-keys", []byte(jsonText(selfEnv)), "reject", "decrypt_failed")
	return sec
}

// cacheSenderKeys adds the wraps of an existing sender_keys section to the sealer cache.
func cacheSenderKeys(cache map[string]string, old SenderKeySection, addWrapped func(page, session, wrapped string), addPayload func([]byte)) {
	for _, v := range old.HPKE {
		pub, _ := wire.B64u.DecodeString(v.Public)
		info, _ := wire.B64u.DecodeString(v.Info)
		sk, _ := wire.B64u.DecodeString(v.SessionKey)
		cache[cacheKey(pub, info, sk)] = v.Wrapped
	}
	decode := func(text string) (signed.Object, bool) {
		var w signed.Wire
		if json.Unmarshal([]byte(text), &w) != nil {
			return signed.Object{}, false
		}
		obj, err := signed.Decode(w)
		return obj, err == nil
	}
	for _, v := range old.Recover {
		if obj, ok := decode(v.Envelope); ok {
			addPayload(obj.Payload)
		}
		if obj, ok := decode(v.SenderKey); ok {
			var p senderKeyJSON
			if json.Unmarshal(obj.Payload, &p) == nil {
				addWrapped(p.PageID, p.SessionID, p.Key)
			}
		}
	}
	for _, v := range old.Receive {
		if obj, ok := decode(v.Wire); ok {
			addPayload(obj.Payload)
		}
	}
}

func checkSenderKeys(t *testing.T, sec SenderKeySection) {
	t.Helper()
	for _, v := range sec.HPKE {
		t.Run("sender-hpke/"+v.Name, func(t *testing.T) {
			if !bytes.Equal(mustB64(t, v.Info), senderInfo(v.PageID, v.SessionID, v.DeviceID)) {
				t.Fatal("info is not the sender info")
			}
			priv, err := ecdh.X25519().NewPrivateKey(mustB64(t, v.Private))
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(priv.PublicKey().Bytes(), mustB64(t, v.Public)) {
				t.Fatal("private key does not match public key")
			}
			sk, err := hpkeOpen(priv, mustB64(t, v.Info), mustB64(t, v.Wrapped))
			if err != nil || !bytes.Equal(sk, mustB64(t, v.SessionKey)) {
				t.Fatalf("open: %v", err)
			}
			if _, err := hpkeOpen(priv, hpkeInfo(v.PageID, v.SessionID, v.DeviceID), mustB64(t, v.Wrapped)); err == nil {
				t.Fatal("a sender wrap opened under the receiver info")
			}
		})
	}
	for _, v := range sec.Recover {
		t.Run("recover/"+v.Name, func(t *testing.T) {
			env, err := decodeWire(t, v.Envelope)
			if err != nil {
				t.Fatalf("envelope wire: %v", err)
			}
			key, err := decodeWire(t, v.SenderKey)
			if err != nil {
				t.Fatalf("sender key wire: %v", err)
			}
			priv, err := ecdh.X25519().NewPrivateKey(mustB64(t, v.Private))
			if err != nil {
				t.Fatal(err)
			}
			sk, reason := recoverSessionKey(v.DeviceID, ed25519.PublicKey(mustB64(t, v.Ed25519)), priv, v.PushPageID, env, key)
			if v.Expect == "ok" {
				if reason != "" || !bytes.Equal(sk, mustB64(t, v.SessionKey)) {
					t.Fatalf("rejected: %s", reason)
				}
			} else if reason != v.Reason {
				t.Fatalf("reason %q, want %q", reason, v.Reason)
			}
		})
	}
	for _, v := range sec.Receive {
		t.Run("sender-receive/"+v.Name, func(t *testing.T) {
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

// TestSenderKeyMessageFits checks the size claim of section 13.7: a sender_key message is within
// the 1024-byte text-frame limit of section 11.1.
func TestSenderKeyMessageFits(t *testing.T) {
	sec := senderKeyVectors(&sealer{cache: map[string]string{}})
	key, err := decodeWire(t, sec.Recover[0].SenderKey)
	if err != nil {
		t.Fatal(err)
	}
	msg := jsonText(struct {
		Type string      `json:"type"`
		Key  signed.Wire `json:"key"`
	}{"sender_key", key.Encode()})
	if len(msg) > 1024 || len(msg) < 500 {
		t.Fatalf("sender_key message is %d bytes", len(msg))
	}
	t.Logf("sender_key message: %d bytes", len(msg))
}
