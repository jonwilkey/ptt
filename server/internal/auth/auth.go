// Package auth generates and hashes the bearer secrets of docs/protocol.md section 2: device
// tokens and enrollment codes.
//
// Both are random byte strings carried as unpadded base64url. The server stores only the
// SHA-256 of the raw bytes, so a leaked database yields nothing usable.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
)

const (
	// TokenBytes is the size of a device token before encoding.
	TokenBytes = 32
	// CodeBytes is the size of an enrollment code before encoding.
	CodeBytes = 16
)

var b64 = base64.RawURLEncoding

// NewToken returns a fresh device token (43 characters) and the hash the server stores.
func NewToken() (token string, hash []byte, err error) {
	return newSecret(TokenBytes)
}

// HashToken returns the stored hash of a presented token. It rejects anything that is not
// exactly 32 bytes of unpadded base64url.
func HashToken(token string) ([]byte, error) {
	return hashSecret(token, TokenBytes)
}

// NewEnrollmentCode returns a fresh one-time enrollment code (22 characters) and its hash.
func NewEnrollmentCode() (code string, codeHash []byte, err error) {
	return newSecret(CodeBytes)
}

// HashEnrollmentCode returns the stored hash of a presented enrollment code.
func HashEnrollmentCode(code string) ([]byte, error) {
	return hashSecret(code, CodeBytes)
}

// CodeID returns the public, non-secret handle of an enrollment code: the unpadded base64url
// of its hash, as used in /v1/enrollment-codes/{code_id}.
func CodeID(codeHash []byte) string {
	return b64.EncodeToString(codeHash)
}

// Equal reports, in constant time, whether two hashes are identical.
func Equal(a, b []byte) bool {
	return subtle.ConstantTimeCompare(a, b) == 1
}

func newSecret(n int) (string, []byte, error) {
	raw := make([]byte, n)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, fmt.Errorf("generate secret: %w", err)
	}
	sum := sha256.Sum256(raw)
	return b64.EncodeToString(raw), sum[:], nil
}

func hashSecret(s string, n int) ([]byte, error) {
	raw, err := b64.DecodeString(s)
	if err != nil {
		return nil, fmt.Errorf("secret is not unpadded base64url: %w", err)
	}
	if len(raw) != n {
		return nil, errors.New("secret has the wrong length")
	}
	sum := sha256.Sum256(raw)
	return sum[:], nil
}
