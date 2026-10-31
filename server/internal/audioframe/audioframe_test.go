package audioframe

import (
	"bytes"
	"errors"
	"testing"
)

func frameOf(typ byte, tid byte, seq uint32, sealed int) []byte {
	h := Header{Type: typ, Seq: seq}
	for i := range h.TransmissionID {
		h.TransmissionID[i] = tid
	}
	return append(h.AppendHeader(nil), bytes.Repeat([]byte{0xAB}, sealed)...)
}

func TestConstants(t *testing.T) {
	if HeaderSize != 21 || MinFrameSize != 38 || MaxFrameSize != 1312 {
		t.Fatalf("sizes %d %d %d", HeaderSize, MinFrameSize, MaxFrameSize)
	}
}

func TestParseValid(t *testing.T) {
	for _, tc := range []struct {
		name   string
		seq    uint32
		sealed int
	}{
		{"min", 0, MinPacket + TagSize},
		{"typical 24 kbps", 1, 60 + TagSize},
		{"max packet", 2999, MaxPacket + TagSize},
		{"max seq", MaxSeq, 60 + TagSize},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := frameOf(TypeLive, 7, tc.seq, tc.sealed)
			h, sealed, err := Parse(f)
			if err != nil {
				t.Fatal(err)
			}
			if h.Type != TypeLive || h.Seq != tc.seq || h.TransmissionID[0] != 7 || len(sealed) != tc.sealed {
				t.Fatalf("got %+v len %d", h, len(sealed))
			}
			if !bytes.Equal(h.AppendHeader(nil), f[:HeaderSize]) {
				t.Fatal("header does not round-trip")
			}
		})
	}
}

func TestParseSeqIsBigEndian(t *testing.T) {
	f := frameOf(TypeLive, 1, 0, 1+TagSize)
	copy(f[17:21], []byte{0x00, 0x00, 0x01, 0x02})
	h, _, err := Parse(f)
	if err != nil || h.Seq != 0x0102 {
		t.Fatalf("seq %#x err %v", h.Seq, err)
	}
}

func TestParseRejects(t *testing.T) {
	for _, tc := range []struct {
		name  string
		frame []byte
		want  error
	}{
		{"nil", nil, ErrTooShort},
		{"header only", frameOf(TypeLive, 1, 0, 0), ErrTooShort},
		{"empty packet", frameOf(TypeLive, 1, 0, TagSize), ErrTooShort},
		{"one byte short", frameOf(TypeLive, 1, 0, MinPacket+TagSize-1), ErrTooShort},
		{"one byte too long", frameOf(TypeLive, 1, 0, MaxPacket+TagSize+1), ErrTooLong},
		{"reserved clip type", frameOf(TypeClip, 1, 0, 60+TagSize), ErrType},
		{"zero type", frameOf(0x00, 1, 0, 60+TagSize), ErrType},
		{"high type", frameOf(0xFF, 1, 0, 60+TagSize), ErrType},
		{"seq above max", frameOf(TypeLive, 1, MaxSeq+1, 60+TagSize), ErrSeq},
		{"seq 2^32-1", frameOf(TypeLive, 1, 0xFFFFFFFF, 60+TagSize), ErrSeq},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, _, err := Parse(tc.frame); !errors.Is(err, tc.want) {
				t.Fatalf("err = %v, want %v", err, tc.want)
			}
		})
	}
}

func FuzzParse(f *testing.F) {
	f.Add([]byte(nil))
	f.Add([]byte{TypeLive})
	f.Add(frameOf(TypeLive, 1, 0, MinPacket+TagSize))
	f.Add(frameOf(TypeLive, 0xFF, MaxSeq, 60+TagSize))
	f.Add(frameOf(TypeLive, 2, 7, MaxPacket+TagSize))
	f.Add(frameOf(TypeLive, 2, 7, MaxPacket+TagSize+1))
	f.Add(frameOf(TypeClip, 3, 0, 60+TagSize))
	f.Add(frameOf(TypeLive, 4, MaxSeq+1, 60+TagSize))
	f.Add(frameOf(TypeLive, 5, 0, TagSize))
	f.Fuzz(func(t *testing.T, frame []byte) {
		h, sealed, err := Parse(frame)
		if err != nil {
			if h != (Header{}) || sealed != nil {
				t.Fatalf("error %v with non-zero results", err)
			}
			return
		}
		if len(frame) < MinFrameSize || len(frame) > MaxFrameSize {
			t.Fatalf("accepted length %d", len(frame))
		}
		if h.Type != TypeLive || h.Seq > MaxSeq {
			t.Fatalf("accepted header %+v", h)
		}
		if len(sealed) < MinPacket+TagSize || len(sealed) > MaxPacket+TagSize {
			t.Fatalf("sealed length %d", len(sealed))
		}
		if !bytes.Equal(append(h.AppendHeader(nil), sealed...), frame) {
			t.Fatal("parse then re-encode changed the frame")
		}
	})
}
