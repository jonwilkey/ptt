package vectors

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"slices"
	"testing"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
	"github.com/jonwilkey/ptt/server/internal/wire"
)

// Reference implementation of docs/protocol.md section 12. The server never encrypts; this code
// exists only to make and check the shared vectors (the Android crypto implements the same
// rules independently).

// AudioFrame is one frame of a transmission. Plaintext is the Opus packet, Nonce the 12-byte
// GCM nonce and Frame the whole wire frame: header || ciphertext || tag. All b64u.
type AudioFrame struct {
	Seq       uint32 `json:"seq"`
	Plaintext string `json:"plaintext"`
	Nonce     string `json:"nonce"`
	Frame     string `json:"frame"`
}

// AudioTransmission is the key derivation and the frames of one transmission. Info is b64u of
// the raw HKDF info bytes.
type AudioTransmission struct {
	Name            string       `json:"name"`
	SessionKey      string       `json:"session_key"`
	SessionID       string       `json:"session_id"`
	TalkerDeviceID  string       `json:"talker_device_id"`
	TransmissionID  string       `json:"transmission_id"`
	Info            string       `json:"info"`
	TransmissionKey string       `json:"transmission_key"`
	Frames          []AudioFrame `json:"frames"`
}

// AudioReject is one frame a receiver must drop. SessionKey, SessionID, TalkerDeviceID and
// TransmissionID are what the receiver believes (from its envelope and floor_granted); Frame is
// the received bytes. Reason is the first failing check, in the order of docs/protocol.md
// section 12.5: bad_length, bad_type, bad_seq, wrong_transmission, decrypt_failed.
type AudioReject struct {
	Name           string `json:"name"`
	SessionKey     string `json:"session_key"`
	SessionID      string `json:"session_id"`
	TalkerDeviceID string `json:"talker_device_id"`
	TransmissionID string `json:"transmission_id"`
	Frame          string `json:"frame"`
	Reason         string `json:"reason"`
}

// AudioReplay is a stateful receiver scenario for one transmission: the frames are delivered in
// order to one receiver and each gets the verdict at the same index. "ok" = authenticated and
// first time its seq is accepted; "replay" = authenticated but the seq was already accepted;
// "drop" = failed a check of docs/protocol.md section 12.5 (and consumed no seq).
type AudioReplay struct {
	Name           string   `json:"name"`
	SessionKey     string   `json:"session_key"`
	SessionID      string   `json:"session_id"`
	TalkerDeviceID string   `json:"talker_device_id"`
	TransmissionID string   `json:"transmission_id"`
	Frames         []string `json:"frames"`
	Expect         []string `json:"expect"`
}

// AudioStep is one event of an AudioSession as the receiver sees it. Op is "granted"
// (floor_granted, Talker and TransmissionID; Expect "accept" or "refuse"), "released"
// (floor_released, TransmissionID), "reconnected" (the receiver's own socket closed and it
// rejoined; a current transmission becomes suspended) or "frame" (Frame b64u; Expect "ok", "replay" or "drop").
// A "granted" repeating the current, not yet released grant (a rejoin snapshot) is accepted and
// keeps the accepted-seq set; after "reconnected" the current transmission is suspended: the same
// talker and id resume it, any other grant first treats it as released (its id is refused
// forever) and is then judged as a fresh grant; frames for a suspended transmission are dropped.
// Any other grant of an id already seen is refused.
type AudioStep struct {
	Op             string `json:"op"`
	Talker         string `json:"talker,omitempty"`
	TransmissionID string `json:"transmission_id,omitempty"`
	Frame          string `json:"frame,omitempty"`
	Expect         string `json:"expect,omitempty"`
}

// AudioSession is a stateful receiver scenario across floor events (rules 4 and 5 of section 12.4).
type AudioSession struct {
	Name       string      `json:"name"`
	SessionKey string      `json:"session_key"`
	SessionID  string      `json:"session_id"`
	Steps      []AudioStep `json:"steps"`
}

// AudioSection is the audio_frames section of the file.
type AudioSection struct {
	Transmissions []AudioTransmission `json:"transmissions"`
	Reject        []AudioReject       `json:"reject"`
	Replay        []AudioReplay       `json:"replay"`
	Sessions      []AudioSession      `json:"sessions"`
}

// receiverModel is the floor and replay state of section 12.4-12.5.
type receiverModel struct {
	sk        []byte
	sessionID string
	talker    string // current talker, "" when the floor is free
	tid       string // current transmission id
	suspended bool   // own socket closed during tid; waiting for the rejoin snapshot
	seen      map[string]bool
	accepted  map[string]map[uint32]bool
}

func newReceiverModel(sk []byte, sessionID string) *receiverModel {
	return &receiverModel{sk: sk, sessionID: sessionID, seen: map[string]bool{}, accepted: map[string]map[uint32]bool{}}
}

func (m *receiverModel) granted(talker, tid string) string {
	if m.tid != "" && m.suspended {
		if tid == m.tid && talker == m.talker {
			m.suspended = false
			return "accept"
		}
		m.talker, m.tid, m.suspended = "", "", false // released while away; its id stays seen
	}
	if m.tid != "" { // floor held: only a repeat of the very same grant is fine
		if tid == m.tid && talker == m.talker {
			return "accept"
		}
		return "refuse"
	}
	if m.seen[tid] { // released before: refused forever
		return "refuse"
	}
	m.talker, m.tid, m.seen[tid] = talker, tid, true
	m.accepted[tid] = map[uint32]bool{}
	return "accept"
}

func (m *receiverModel) released(tid string) {
	if tid == m.tid {
		m.talker, m.tid = "", ""
	}
}

func (m *receiverModel) reconnected() { m.suspended = m.tid != "" }

func (m *receiverModel) frame(f []byte) string {
	if m.tid == "" || m.suspended {
		return "drop"
	}
	if _, reason := receiveFrame(m.sk, m.sessionID, m.talker, m.tid, f); reason != "" {
		return "drop"
	}
	h, _, _ := audioframe.Parse(f)
	if m.accepted[m.tid][h.Seq] {
		return "replay"
	}
	m.accepted[m.tid][h.Seq] = true
	return "ok"
}

// run feeds the steps to a fresh model and returns the verdict of each step ("" for released).
func (m *receiverModel) run(steps []AudioStep, frame func(AudioStep) []byte) []string {
	var out []string
	for _, st := range steps {
		switch st.Op {
		case "granted":
			out = append(out, m.granted(st.Talker, st.TransmissionID))
		case "released":
			m.released(st.TransmissionID)
			out = append(out, "")
		case "reconnected":
			m.reconnected()
			out = append(out, "")
		default:
			out = append(out, m.frame(frame(st)))
		}
	}
	return out
}

func audioSessions(sk []byte) []AudioSession {
	alex, sam := alexPhone.id, samPhone.id
	fr := func(talker, tid string, seq uint32) AudioStep {
		key := transmissionKey(sk, audioSessionID, talker, tid)
		return AudioStep{Op: "frame", Frame: b64(sealFrame(key, audioframe.TypeLive, tid, seq, opusLike("session/"+tid+"/"+talker, 60)))}
	}
	gr := func(talker, tid string) AudioStep {
		return AudioStep{Op: "granted", Talker: talker, TransmissionID: tid}
	}
	rel := func(tid string) AudioStep { return AudioStep{Op: "released", TransmissionID: tid} }
	reco := AudioStep{Op: "reconnected"}
	sessions := []AudioSession{
		{Name: "reconnect_then_other_grant_treats_old_as_released", Steps: []AudioStep{
			gr(alex, audioTID1), fr(alex, audioTID1, 0), reco, fr(alex, audioTID1, 1),
			gr(sam, audioTID2), fr(sam, audioTID2, 0), gr(alex, audioTID1),
		}},
		{Name: "reconnect_then_same_grant_resumes_seq_set", Steps: []AudioStep{
			gr(alex, audioTID1), fr(alex, audioTID1, 0), reco, gr(alex, audioTID1), fr(alex, audioTID1, 0), fr(alex, audioTID1, 1),
		}},
		{Name: "rejoin_mid_transmission_keeps_seq_set", Steps: []AudioStep{
			gr(alex, audioTID1), fr(alex, audioTID1, 0), gr(alex, audioTID1), fr(alex, audioTID1, 0), fr(alex, audioTID1, 1),
			rel(audioTID1), gr(alex, audioTID1), fr(alex, audioTID1, 2),
		}},
		{Name: "same_seq_under_new_transmission_is_fresh", Steps: []AudioStep{
			gr(alex, audioTID1), fr(alex, audioTID1, 0), rel(audioTID1),
			gr(alex, audioTID2), fr(alex, audioTID2, 0), fr(alex, audioTID1, 1),
		}},
		{Name: "released_id_never_regranted_to_other_talker", Steps: []AudioStep{
			gr(alex, audioTID1), rel(audioTID1), gr(sam, audioTID1), fr(sam, audioTID1, 0),
		}},
		{Name: "second_grant_while_floor_held_is_refused", Steps: []AudioStep{
			gr(alex, audioTID1), gr(sam, audioTID2), fr(sam, audioTID2, 0), fr(alex, audioTID1, 0),
		}},
		{Name: "grant_repeat_naming_other_talker_is_refused", Steps: []AudioStep{
			gr(alex, audioTID1), gr(sam, audioTID1), fr(alex, audioTID1, 0),
		}},
		{Name: "frame_with_no_floor_is_dropped", Steps: []AudioStep{
			fr(alex, audioTID1, 0), gr(alex, audioTID1), rel(audioTID1), fr(alex, audioTID1, 1),
		}},
	}
	for i := range sessions {
		sessions[i].SessionKey, sessions[i].SessionID = b64(sk), audioSessionID
		m := newReceiverModel(sk, audioSessionID)
		verdicts := m.run(sessions[i].Steps, func(st AudioStep) []byte { return mustB64Plain(st.Frame) })
		for j, v := range verdicts {
			sessions[i].Steps[j].Expect = v
		}
	}
	return sessions
}

const transmissionInfoLabel = "ptt-transmission-key-v1"

// transmissionInfo is the HKDF info: label || 0x00 || session_id || talker device_id ||
// transmission_id, the three ids as 36 ASCII characters each.
func transmissionInfo(sessionID, talker, tid string) []byte {
	return []byte(transmissionInfoLabel + "\x00" + sessionID + talker + tid)
}

// transmissionKey is HKDF-SHA256(IKM = session key, salt = empty, info, L = 32).
func transmissionKey(sessionKey []byte, sessionID, talker, tid string) []byte {
	k, err := hkdf.Key(sha256.New, sessionKey, nil, string(transmissionInfo(sessionID, talker, tid)), 32)
	if err != nil {
		panic(err)
	}
	return k
}

// frameNonce is 8 zero bytes || seq (big endian).
func frameNonce(seq uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[8:], seq)
	return n
}

func gcmFor(key []byte) cipher.AEAD {
	blk, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	g, err := cipher.NewGCM(blk)
	if err != nil {
		panic(err)
	}
	return g
}

func mustUUIDBytes(s string) [16]byte {
	u, err := uuid.Parse(s)
	if err != nil {
		panic(err)
	}
	return u
}

// sealFrame builds a whole wire frame. typ and the header seq are parameters so the reject
// vectors can build deliberately wrong ones.
func sealFrame(key []byte, typ byte, tid string, seq uint32, plaintext []byte) []byte {
	h := audioframe.Header{Type: typ, TransmissionID: mustUUIDBytes(tid), Seq: seq}
	hdr := h.AppendHeader(nil)
	return gcmFor(key).Seal(hdr, frameNonce(seq), plaintext, hdr)
}

// receiveFrame is the receiver of section 12.5 without replay state: it returns the Opus packet
// or the reason for dropping the frame.
func receiveFrame(sessionKey []byte, sessionID, talker, tid string, frame []byte) ([]byte, string) {
	h, sealed, err := audioframe.Parse(frame)
	switch {
	case err == nil:
	case errors.Is(err, audioframe.ErrType):
		return nil, "bad_type"
	case errors.Is(err, audioframe.ErrSeq):
		return nil, "bad_seq"
	default:
		return nil, "bad_length"
	}
	if h.TransmissionID != mustUUIDBytes(tid) {
		return nil, "wrong_transmission"
	}
	key := transmissionKey(sessionKey, sessionID, talker, tid)
	pt, err := gcmFor(key).Open(nil, frameNonce(h.Seq), sealed, frame[:audioframe.HeaderSize])
	if err != nil {
		return nil, "decrypt_failed"
	}
	return pt, ""
}

// opusLike returns n deterministic bytes standing in for an Opus packet.
func opusLike(label string, n int) []byte {
	var out []byte
	for i := 0; len(out) < n; i++ {
		h := sha256.Sum256(fmt.Appendf(nil, "ptt-test-vector/opus/%s/%d", label, i))
		out = append(out, h[:]...)
	}
	return out[:n]
}

const (
	audioSessionID = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
	audioTID1      = "3c9f1e72-8a04-4d6b-b1f5-62e7a0c94d18"
	audioTID2      = "d27b5a09-6e13-4c88-9f40-1a8c3e5b7d62"
	audioTID3      = "58e0c4b1-9d27-4a3f-8c16-f4b2d7a09e35"
)

func audioTransmission(name string, sessionKey []byte, talker, tid string, seqLens [][2]int) AudioTransmission {
	key := transmissionKey(sessionKey, audioSessionID, talker, tid)
	t := AudioTransmission{
		Name: name, SessionKey: b64(sessionKey), SessionID: audioSessionID, TalkerDeviceID: talker,
		TransmissionID: tid, Info: b64(transmissionInfo(audioSessionID, talker, tid)), TransmissionKey: b64(key),
	}
	for _, sl := range seqLens {
		seq, n := uint32(sl[0]), sl[1]
		pt := opusLike(fmt.Sprintf("%s/%d", name, seq), n)
		t.Frames = append(t.Frames, AudioFrame{
			Seq: seq, Plaintext: b64(pt), Nonce: b64(frameNonce(seq)),
			Frame: b64(sealFrame(key, audioframe.TypeLive, tid, seq, pt)),
		})
	}
	return t
}

func audioVectors() AudioSection {
	sk := seedOf("audio/session-key")
	other := seedOf("audio/other-session-key")
	alex, sam := alexPhone.id, samPhone.id

	// 60 bytes is a 24 kbps 20 ms packet; 1 byte is a DTX-style packet; 1275 is the Opus maximum.
	lens := [][2]int{{0, 60}, {1, 61}, {2, 1}, {3, 1275}, {2999, 60}, {audioframe.MaxSeq, 60}}
	sender := audioTransmission("sender-talks", sk, alex, audioTID1, lens)
	answerer := audioTransmission("answerer-talks-same-tid", sk, sam, audioTID1, lens[:3])
	again := audioTransmission("sender-talks-again", sk, alex, audioTID2, lens[:3])
	sec := AudioSection{Transmissions: []AudioTransmission{sender, answerer, again}}

	good := mustB64Plain(sender.Frames[0].Frame)  // seq 0
	good1 := mustB64Plain(sender.Frames[1].Frame) // seq 1
	goodKey := transmissionKey(sk, audioSessionID, alex, audioTID1)
	rej := func(name string, frame []byte, mod func(*AudioReject)) {
		r := AudioReject{
			Name: name, SessionKey: b64(sk), SessionID: audioSessionID, TalkerDeviceID: alex,
			TransmissionID: audioTID1, Frame: b64(frame),
		}
		if mod != nil {
			mod(&r)
		}
		_, r.Reason = receiveFrame(mustB64Plain(r.SessionKey), r.SessionID, r.TalkerDeviceID, r.TransmissionID, frame)
		sec.Reject = append(sec.Reject, r)
	}
	flip := func(frame []byte, i int) []byte {
		out := slices.Clone(frame)
		out[i] ^= 0x01
		return out
	}

	rej("tampered_header_seq", flip(good, audioframe.HeaderSize-1), nil)
	rej("header_seq_differs_from_nonce_seq", sealWithHeaderSeq(goodKey, audioTID1, 0, 1, opusLike("sender-talks/0", 60)), nil)
	rej("tampered_transmission_id", flip(good, 1), nil)
	rej("tampered_ciphertext", flip(good, audioframe.HeaderSize), nil)
	rej("tampered_tag", flip(good, len(good)-1), nil)
	rej("truncated_tag", good[:len(good)-1], nil)
	rej("truncated_below_minimum", good[:audioframe.MinFrameSize-1], nil)
	rej("header_only", good[:audioframe.HeaderSize], nil)
	rej("empty", nil, nil)
	rej("extended", append(slices.Clone(good), 0x00), nil)
	rej("wrong_talker", good, func(r *AudioReject) { r.TalkerDeviceID = sam })
	rej("wrong_session_key", good, func(r *AudioReject) { r.SessionKey = b64(other) })
	rej("wrong_session_id", good, func(r *AudioReject) { r.SessionID = "b9e5f7c3-82da-4041-9c6f-3dab2e8f4a71" })
	rej("wrong_transmission_expected", good, func(r *AudioReject) { r.TransmissionID = audioTID2 })
	rej("answerer_frame_as_sender", mustB64Plain(answerer.Frames[0].Frame), nil)
	rej("reserved_clip_type", sealFrame(goodKey, audioframe.TypeClip, audioTID1, 0, opusLike("x", 60)), nil)
	rej("zero_type", sealFrame(goodKey, 0x00, audioTID1, 0, opusLike("x", 60)), nil)
	rej("type_flipped_after_sealing", flip(good, 0), nil)
	rej("seq_above_maximum", sealFrame(goodKey, audioframe.TypeLive, audioTID1, audioframe.MaxSeq+1, opusLike("x", 60)), nil)
	rej("packet_too_long", sealFrame(goodKey, audioframe.TypeLive, audioTID1, 0, opusLike("x", audioframe.MaxPacket+1)), nil)
	rej("empty_packet", sealFrame(goodKey, audioframe.TypeLive, audioTID1, 0, nil), nil)

	rp := func(name string, frames ...[]byte) {
		r := AudioReplay{
			Name: name, SessionKey: b64(sk), SessionID: audioSessionID, TalkerDeviceID: alex, TransmissionID: audioTID1,
		}
		seen := map[uint32]bool{}
		for _, f := range frames {
			r.Frames = append(r.Frames, b64(f))
			h, _, err := audioframe.Parse(f)
			verdict := "ok"
			if _, reason := receiveFrame(sk, audioSessionID, alex, audioTID1, f); err != nil || reason != "" {
				verdict = "drop"
			} else if seen[h.Seq] {
				verdict = "replay"
			} else {
				seen[h.Seq] = true
			}
			r.Expect = append(r.Expect, verdict)
		}
		sec.Replay = append(sec.Replay, r)
	}
	rp("duplicate_is_dropped", good, good1, good)
	rp("out_of_order_is_accepted_once", good1, good, good1, good)
	rp("forged_frame_does_not_consume_seq", flip(good1, audioframe.HeaderSize), good1, good1)
	sec.Sessions = audioSessions(sk)
	return sec
}

// sealWithHeaderSeq seals plaintext with the nonce of nonceSeq but writes headerSeq in the
// header, which the receiver must reject: it derives the nonce from the header it sees.
func sealWithHeaderSeq(key []byte, tid string, nonceSeq, headerSeq uint32, pt []byte) []byte {
	h := audioframe.Header{Type: audioframe.TypeLive, TransmissionID: mustUUIDBytes(tid), Seq: headerSeq}
	hdr := h.AppendHeader(nil)
	return gcmFor(key).Seal(hdr, frameNonce(nonceSeq), pt, hdr)
}

func mustB64Plain(s string) []byte {
	if s == "" {
		return nil
	}
	b, err := wire.B64u.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

// checkAudio verifies a (possibly committed) audio section against the reference code and the
// server's frame parser.
func checkAudio(t *testing.T, a AudioSection) {
	t.Helper()
	seenKeys := map[string]string{}
	for _, tr := range a.Transmissions {
		t.Run("audio/"+tr.Name, func(t *testing.T) {
			sk := mustB64(t, tr.SessionKey)
			// Written out here, apart from the generator's helpers.
			info := "ptt-transmission-key-v1" + "\x00" + tr.SessionID + tr.TalkerDeviceID + tr.TransmissionID
			if info != string(mustB64(t, tr.Info)) {
				t.Fatalf("info mismatch")
			}
			key := rfc5869SHA256(sk, nil, []byte(info), 32)
			if !bytes.Equal(key, mustB64(t, tr.TransmissionKey)) {
				t.Fatal("transmission key mismatch")
			}
			if prev, dup := seenKeys[tr.TransmissionKey]; dup {
				t.Fatalf("transmission key equals the one of %s", prev)
			}
			seenKeys[tr.TransmissionKey] = tr.Name
			blk, err := aes.NewCipher(key)
			if err != nil {
				t.Fatal(err)
			}
			gcm, err := cipher.NewGCM(blk)
			if err != nil {
				t.Fatal(err)
			}
			for _, f := range tr.Frames {
				frame := mustB64(t, f.Frame)
				nonce := make([]byte, 12)
				nonce[8], nonce[9], nonce[10], nonce[11] = byte(f.Seq>>24), byte(f.Seq>>16), byte(f.Seq>>8), byte(f.Seq)
				if !bytes.Equal(nonce, mustB64(t, f.Nonce)) {
					t.Fatalf("seq %d: nonce mismatch", f.Seq)
				}
				tidRaw, err := uuid.Parse(tr.TransmissionID)
				if err != nil {
					t.Fatal(err)
				}
				if frame[0] != 0x01 || !bytes.Equal(frame[1:17], tidRaw[:]) || !bytes.Equal(frame[17:21], nonce[8:]) {
					t.Fatalf("seq %d: header mismatch", f.Seq)
				}
				pt, err := gcm.Open(nil, nonce, frame[21:], frame[:21])
				if err != nil || !bytes.Equal(pt, mustB64(t, f.Plaintext)) {
					t.Fatalf("seq %d: does not open to the plaintext (%v)", f.Seq, err)
				}
				// And the generator's receiver agrees.
				if _, reason := receiveFrame(sk, tr.SessionID, tr.TalkerDeviceID, tr.TransmissionID, frame); reason != "" {
					t.Fatalf("seq %d: receiver rejected: %s", f.Seq, reason)
				}
			}
		})
	}
	for _, r := range a.Reject {
		t.Run("audio-reject/"+r.Name, func(t *testing.T) {
			_, reason := receiveFrame(mustB64(t, r.SessionKey), r.SessionID, r.TalkerDeviceID, r.TransmissionID, mustB64(t, r.Frame))
			if reason == "" || reason != r.Reason {
				t.Fatalf("reason %q, want %q", reason, r.Reason)
			}
		})
	}
	for _, r := range a.Replay {
		t.Run("audio-replay/"+r.Name, func(t *testing.T) {
			sk := mustB64(t, r.SessionKey)
			seen := map[uint32]bool{}
			if len(r.Frames) != len(r.Expect) {
				t.Fatal("frames and expect differ in length")
			}
			for i, ft := range r.Frames {
				f := mustB64(t, ft)
				got := "drop"
				if _, reason := receiveFrame(sk, r.SessionID, r.TalkerDeviceID, r.TransmissionID, f); reason == "" {
					h, _, _ := audioframe.Parse(f)
					got = "ok"
					if seen[h.Seq] {
						got = "replay"
					} else {
						seen[h.Seq] = true
					}
				}
				if got != r.Expect[i] {
					t.Fatalf("frame %d: %s, want %s", i, got, r.Expect[i])
				}
			}
		})
	}
	for _, sc := range a.Sessions {
		t.Run("audio-session/"+sc.Name, func(t *testing.T) {
			m := newReceiverModel(mustB64(t, sc.SessionKey), sc.SessionID)
			got := m.run(sc.Steps, func(st AudioStep) []byte { return mustB64(t, st.Frame) })
			for i, st := range sc.Steps {
				if got[i] != st.Expect {
					t.Fatalf("step %d (%s): %q, want %q", i, st.Op, got[i], st.Expect)
				}
			}
		})
	}
}

// rfc5869SHA256 is HKDF (RFC 5869) with HMAC-SHA256, written out so the vector check does not
// share code with the generator.
func rfc5869SHA256(ikm, salt, info []byte, n int) []byte {
	if salt == nil {
		salt = make([]byte, sha256.Size)
	}
	ext := hmac.New(sha256.New, salt)
	ext.Write(ikm)
	prk := ext.Sum(nil)
	var okm, prev []byte
	for i := byte(1); len(okm) < n; i++ {
		x := hmac.New(sha256.New, prk)
		x.Write(prev)
		x.Write(info)
		x.Write([]byte{i})
		prev = x.Sum(nil)
		okm = append(okm, prev...)
	}
	return okm[:n]
}

// TestTransmissionKeyKnownAnswer pins the derivation to constants that do not come from the
// generator (the key was computed with an independent Python HKDF).
func TestTransmissionKeyKnownAnswer(t *testing.T) {
	sk := make([]byte, 32)
	for i := range sk {
		sk[i] = byte(i)
	}
	const (
		wantInfo = "ptt-transmission-key-v1\x00a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a573c9f1e72-8a04-4d6b-b1f5-62e7a0c94d18"
		wantOKM  = "ce096bbe48c72f894d5cc3fbd0e90218" + "c6541fe9371af92618f4d0f5913e226d" // known answer, not a secret
	)
	info := transmissionInfo("a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60", "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57", "3c9f1e72-8a04-4d6b-b1f5-62e7a0c94d18")
	if string(info) != wantInfo {
		t.Fatalf("info = %q", info)
	}
	if got := fmt.Sprintf("%x", rfc5869SHA256(sk, nil, info, 32)); got != wantOKM {
		t.Fatalf("key = %s", got)
	}
	if got := fmt.Sprintf("%x", transmissionKey(sk, "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60", "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57", "3c9f1e72-8a04-4d6b-b1f5-62e7a0c94d18")); got != wantOKM {
		t.Fatalf("generator key = %s", got)
	}
}
