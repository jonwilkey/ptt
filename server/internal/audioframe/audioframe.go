// Package audioframe parses and builds the header of the binary WebSocket audio frames of
// docs/protocol.md section 12.
//
// A frame is
//
//	type (1) || transmission_id (16 raw UUID bytes) || seq (4, big endian) || ciphertext || tag (16)
//
// The first 21 bytes are the header, which is also the AES-GCM associated data. The server reads
// only the header: it never holds a key and never encrypts or decrypts, so this package has no
// cryptography in it. The reference encryption used to make the shared test vectors lives in
// internal/vectors.
package audioframe

import (
	"encoding/binary"
	"errors"
)

// Frame types. Every other value is rejected.
const (
	// TypeLive is one 20 ms Opus packet of a live transmission.
	TypeLive byte = 0x01
	// TypeClip is reserved for milestone 5 (buffered clips); milestone 4 rejects it like any
	// unknown type.
	TypeClip byte = 0x02
)

// Sizes, in bytes.
const (
	// IDSize is the raw transmission UUID.
	IDSize = 16
	// SeqSize is the big-endian sequence number.
	SeqSize = 4
	// HeaderSize is type || transmission_id || seq: the AAD of every frame.
	HeaderSize = 1 + IDSize + SeqSize
	// TagSize is the AES-GCM tag appended to the ciphertext.
	TagSize = 16
	// MinPacket and MaxPacket bound one Opus packet (RFC 6716: 1 to 1275 bytes). AES-GCM
	// ciphertext has the same length as its plaintext.
	MinPacket = 1
	MaxPacket = 1275
	// MinFrameSize and MaxFrameSize bound a whole frame.
	MinFrameSize = HeaderSize + MinPacket + TagSize
	MaxFrameSize = HeaderSize + MaxPacket + TagSize
	// MaxSeq is the highest sequence number accepted. A transmission lasts at most 60 s, which
	// is 3000 frames of 20 ms; the limit leaves headroom and bounds a receiver's replay state.
	MaxSeq = 4095
)

// Parse errors. They are sentinels so callers (and the fuzz test) can tell the cases apart.
var (
	ErrTooShort = errors.New("audioframe: frame shorter than the minimum")
	ErrTooLong  = errors.New("audioframe: frame longer than the maximum")
	ErrType     = errors.New("audioframe: unknown or reserved frame type")
	ErrSeq      = errors.New("audioframe: sequence number above the maximum")
)

// Header is the readable, authenticated part of a frame.
type Header struct {
	Type           byte
	TransmissionID [IDSize]byte
	Seq            uint32
}

// Parse validates a whole frame and returns its header and the sealed part (ciphertext followed
// by the 16-byte tag). The returned slice aliases frame. Parse checks only what the server can
// check without a key: length, type and sequence bounds.
func Parse(frame []byte) (Header, []byte, error) {
	switch {
	case len(frame) < MinFrameSize:
		return Header{}, nil, ErrTooShort
	case len(frame) > MaxFrameSize:
		return Header{}, nil, ErrTooLong
	case frame[0] != TypeLive:
		return Header{}, nil, ErrType
	}
	h := Header{Type: frame[0], Seq: binary.BigEndian.Uint32(frame[1+IDSize : HeaderSize])}
	copy(h.TransmissionID[:], frame[1:1+IDSize])
	if h.Seq > MaxSeq {
		return Header{}, nil, ErrSeq
	}
	return h, frame[HeaderSize:], nil
}

// AppendHeader appends the 21-byte header of h to dst.
func (h Header) AppendHeader(dst []byte) []byte {
	dst = append(dst, h.Type)
	dst = append(dst, h.TransmissionID[:]...)
	return binary.BigEndian.AppendUint32(dst, h.Seq)
}
