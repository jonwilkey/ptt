// Package signed implements the signed objects of docs/protocol.md section 3: a JSON envelope
// {payload, signer, sig} whose sig is Ed25519 over ctx || 0x00 || payload bytes.
package signed

import (
	"crypto/ed25519"
	"errors"

	"github.com/jonwilkey/ptt/server/internal/wire"
)

// Context strings that domain-separate signatures.
const (
	ContextRoster   = "ptt-roster-v1"
	ContextEnvelope = "ptt-envelope-v1"
)

// Wire is the JSON form of a signed object. Binary fields are strict b64u.
type Wire struct {
	Payload string `json:"payload"`
	Signer  string `json:"signer"`
	Sig     string `json:"sig"`
}

// Object is a decoded signed object. Payload is the exact signed byte string.
type Object struct {
	Payload []byte
	Signer  string
	Sig     []byte
}

// ErrMalformed means the object is not shaped as section 3 requires.
var ErrMalformed = errors.New("malformed signed object")

// ErrBadSignature means the signature does not verify.
var ErrBadSignature = errors.New("bad signature")

// Decode strictly decodes w: a non-empty b64u payload, a canonical-UUID signer and a b64u
// 64-byte signature. It does not look at the payload.
func Decode(w Wire) (Object, error) {
	payload, err := wire.B64u.DecodeString(w.Payload)
	if err != nil || len(payload) == 0 || !wire.ValidUUID(w.Signer) {
		return Object{}, ErrMalformed
	}
	sig, ok := wire.DecodeB64u(w.Sig, ed25519.SignatureSize)
	if !ok {
		return Object{}, ErrMalformed
	}
	return Object{Payload: payload, Signer: w.Signer, Sig: sig}, nil
}

// Encode is the inverse of Decode.
func (o Object) Encode() Wire {
	return Wire{
		Payload: wire.B64u.EncodeToString(o.Payload),
		Signer:  o.Signer,
		Sig:     wire.B64u.EncodeToString(o.Sig),
	}
}

func message(ctx string, payload []byte) []byte {
	m := make([]byte, 0, len(ctx)+1+len(payload))
	m = append(m, ctx...)
	m = append(m, 0)
	return append(m, payload...)
}

// Sign produces the object for payload under ctx (used by tests and, later, by tooling).
func Sign(priv ed25519.PrivateKey, signer, ctx string, payload []byte) Object {
	return Object{Payload: payload, Signer: signer, Sig: ed25519.Sign(priv, message(ctx, payload))}
}

// Verify checks o's signature under pub and ctx. The caller resolves o.Signer to pub.
func Verify(pub ed25519.PublicKey, ctx string, o Object) error {
	if len(pub) != ed25519.PublicKeySize || len(o.Sig) != ed25519.SignatureSize ||
		!ed25519.Verify(pub, message(ctx, o.Payload), o.Sig) {
		return ErrBadSignature
	}
	return nil
}
