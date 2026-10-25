package signed

import (
	"bytes"
	"crypto/ed25519"
	"errors"
	"testing"

	"github.com/jonwilkey/ptt/server/internal/wire"
)

const testSigner = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"

// testKey is a fixed key so the vectors below are stable.
func testKey() ed25519.PrivateKey {
	return ed25519.NewKeyFromSeed(bytes.Repeat([]byte{7}, ed25519.SeedSize))
}

func TestSignVerifyRoundTrip(t *testing.T) {
	priv := testKey()
	pub := pubOf(priv)
	obj := Sign(priv, testSigner, ContextRoster, []byte(`{"format":1}`))

	back, err := Decode(obj.Encode())
	if err != nil {
		t.Fatal(err)
	}
	if err := Verify(pub, ContextRoster, back); err != nil {
		t.Fatalf("verify: %v", err)
	}

	// Fixed vector: the signature is deterministic, so a change in the message layout shows up.
	w := obj.Encode()
	again := Sign(priv, testSigner, ContextRoster, []byte(`{"format":1}`)).Encode()
	if w != again {
		t.Fatal("signing is not deterministic")
	}
	msg := append([]byte("ptt-roster-v1\x00"), `{"format":1}`...)
	if !ed25519.Verify(pub, msg, obj.Sig) {
		t.Fatal("signature is not over ctx || 0x00 || payload")
	}
}

// TestFixedVector pins the wire format: a signature produced once by this package for a fixed
// seed. An Android implementation can verify the same triple.
func TestFixedVector(t *testing.T) {
	const (
		pubB64 = "6kpsY-KcUgq-9VB7Ey7F-ZVHdq6-vnuSQh7qaRRG0iw"
		sigB64 = "Y4sOWE5TlYIM_Htsx7G2ucG7Y7qPOAc1ZFDDpbcdvVi5DDGQB8jA0eRSK-nIL-2Xnd2KtmTNvUBkmU3-HajSBw"
	)
	pub, ok := wire.DecodeB64u(pubB64, ed25519.PublicKeySize)
	if !ok {
		t.Fatal("bad pub vector")
	}
	obj, err := Decode(Wire{Payload: wire.B64u.EncodeToString([]byte(`{"format":1}`)), Signer: testSigner, Sig: sigB64})
	if err != nil {
		t.Fatal(err)
	}
	if err := Verify(pub, ContextRoster, obj); err != nil {
		t.Fatalf("fixed vector: %v", err)
	}
	if err := Verify(pub, ContextEnvelope, obj); err == nil {
		t.Fatal("vector verified under the wrong context")
	}
}

func TestVerifyRejects(t *testing.T) {
	priv := testKey()
	pub := pubOf(priv)
	other, _, _ := ed25519.GenerateKey(bytes.NewReader(bytes.Repeat([]byte{9}, 64)))
	obj := Sign(priv, testSigner, ContextRoster, []byte(`{"format":1}`))

	flip := func(b []byte) []byte {
		c := bytes.Clone(b)
		c[0] ^= 1
		return c
	}
	tests := []struct {
		name string
		pub  ed25519.PublicKey
		ctx  string
		obj  Object
	}{
		{"tampered payload", pub, ContextRoster, Object{Payload: flip(obj.Payload), Signer: obj.Signer, Sig: obj.Sig}},
		{"tampered sig", pub, ContextRoster, Object{Payload: obj.Payload, Signer: obj.Signer, Sig: flip(obj.Sig)}},
		{"wrong context", pub, ContextEnvelope, obj},
		{"wrong key", other, ContextRoster, obj},
		{"short key", pub[:31], ContextRoster, obj},
		{"short sig", pub, ContextRoster, Object{Payload: obj.Payload, Signer: obj.Signer, Sig: obj.Sig[:63]}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := Verify(tt.pub, tt.ctx, tt.obj); !errors.Is(err, ErrBadSignature) {
				t.Fatalf("err = %v, want ErrBadSignature", err)
			}
		})
	}
	// The signer field is not signed over: it only selects the key, so changing it alone
	// still verifies against the same key. The server resolves the key from the signer id.
	if err := Verify(pub, ContextRoster, Object{Payload: obj.Payload, Signer: "x", Sig: obj.Sig}); err != nil {
		t.Fatalf("signer id must not affect verification: %v", err)
	}
}

func TestDecode(t *testing.T) {
	good := Sign(testKey(), testSigner, ContextRoster, []byte("x")).Encode()
	if _, err := Decode(good); err != nil {
		t.Fatal(err)
	}
	mut := func(f func(*Wire)) Wire { w := good; f(&w); return w }
	tests := map[string]Wire{
		"padded payload":     mut(func(w *Wire) { w.Payload += "==" }),
		"standard alphabet":  mut(func(w *Wire) { w.Payload = "+/+/" }),
		"empty payload":      mut(func(w *Wire) { w.Payload = "" }),
		"uppercase signer":   mut(func(w *Wire) { w.Signer = "C3E9A7F1-5D2B-4B84-A6E0-9F1C8D3B2A57" }),
		"non-uuid signer":    mut(func(w *Wire) { w.Signer = "nope" }),
		"short sig":          mut(func(w *Wire) { w.Sig = good.Sig[:80] }),
		"padded sig":         mut(func(w *Wire) { w.Sig += "=" }),
		"nonzero trail bits": mut(func(w *Wire) { w.Payload = "AB" }),
	}
	for name, w := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := Decode(w); !errors.Is(err, ErrMalformed) {
				t.Fatalf("err = %v, want ErrMalformed", err)
			}
		})
	}
}

func pubOf(priv ed25519.PrivateKey) ed25519.PublicKey {
	return ed25519.PublicKey(priv[32:])
}
