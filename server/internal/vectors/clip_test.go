package vectors

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"github.com/jonwilkey/ptt/server/internal/audioframe"
)

// Vectors for docs/protocol.md section 13 (clip fallback, protocol 2). They live in their own
// section, clip_fallback, so a milestone-4 receiver's checks of audio_frames stay valid.

// ClipResume is a talker-side resume case (section 13.6): after resume_from with ResumeFrom, the
// talker sends exactly Resend, the stored frames of Transmission (a name in clip_fallback's
// transmissions) with seq above ResumeFrom, in seq order, byte for byte as first sealed.
type ClipResume struct {
	Name         string   `json:"name"`
	Transmission string   `json:"transmission"`
	ResumeFrom   int      `json:"resume_from"`
	Resend       []string `json:"resend"`
}

// ClipSection is the clip_fallback section of the file.
type ClipSection struct {
	Transmissions []AudioTransmission `json:"transmissions"`
	Resume        []ClipResume        `json:"resume"`
	Sessions      []AudioSession      `json:"sessions"`
}

// clipTID4 is a fourth transmission id, used only by the clip sessions.
const clipTID4 = "6b1e9d40-27c5-4f8a-93d2-0c7f5a1e8b46"

// resendAfter is the talker's resend after resume_from: every stored frame above from.
func resendAfter(t AudioTransmission, from int) []string {
	out := []string{}
	for _, f := range t.Frames {
		if int(f.Seq) > from {
			out = append(out, f.Frame)
		}
	}
	return out
}

func clipVectors() ClipSection {
	sk := seedOf("audio/session-key")
	alex, sam := alexPhone.id, samPhone.id
	contiguous := [][2]int{{0, 60}, {1, 60}, {2, 61}, {3, 1}, {4, 60}, {5, 59}}
	tx := audioTransmission("clip-talker", sk, alex, audioTID3, contiguous)
	sec := ClipSection{Transmissions: []AudioTransmission{tx}}

	for _, c := range []struct {
		name string
		from int
	}{
		{"resume_from_middle_resends_the_rest", 2},
		{"resume_from_none_resends_everything", -1},
		{"resume_from_last_resends_nothing", 5},
		{"resume_from_first_frame", 0},
	} {
		sec.Resume = append(sec.Resume, ClipResume{Name: c.name, Transmission: tx.Name, ResumeFrom: c.from, Resend: resendAfter(tx, c.from)})
	}

	fr := func(talker, tid string, seq uint32) AudioStep {
		key := transmissionKey(sk, audioSessionID, talker, tid)
		return AudioStep{Op: "frame", Frame: b64(sealFrame(key, audioframe.TypeLive, tid, seq, opusLike("session/"+tid+"/"+talker, 60)))}
	}
	// A frame sealed again under a (transmission_id, seq) with a different plaintext: what a
	// talker that broke rule 3 would send.
	reseal := func(talker, tid string, seq uint32) AudioStep {
		key := transmissionKey(sk, audioSessionID, talker, tid)
		return AudioStep{Op: "frame", Frame: b64(sealFrame(key, audioframe.TypeLive, tid, seq, opusLike("resealed/"+tid+"/"+talker, 60)))}
	}
	txf := func(seq int) AudioStep { return AudioStep{Op: "frame", Frame: tx.Frames[seq].Frame} }
	gr := func(talker, tid string) AudioStep {
		return AudioStep{Op: "granted", Talker: talker, TransmissionID: tid}
	}
	rel := func(tid string) AudioStep { return AudioStep{Op: "released", TransmissionID: tid} }
	clip := func(talker, tid string, last int) AudioStep {
		return AudioStep{Op: "clip", Talker: talker, TransmissionID: tid, LastSeq: &last}
	}
	heard := func(tid string) AudioStep { return AudioStep{Op: "heard", TransmissionID: tid} }
	reco := AudioStep{Op: "reconnected"}
	t1, t2, t3 := audioTID1, audioTID2, audioTID3

	t4 := clipTID4
	watch, kit, tablet := samWatch.id, kitPhone.id, alexTablet.id
	peer := func(dev string) AudioStep { return AudioStep{Op: "peer_joined", DeviceID: dev} }
	// Sam's phone answered Alex's page; Alex's phone sent it to Sam's two devices.
	answerer := &ClipReceiver{DeviceID: sam, Role: "answerer", FromDevice: alex}
	sender := &ClipReceiver{DeviceID: alex, Role: "sender", Keys: []string{sam, watch}}

	sec.Sessions = []AudioSession{
		{Name: "clip_after_release_plays_unaccepted_seqs", Receiver: answerer, Steps: []AudioStep{
			gr(alex, t1), fr(alex, t1, 0), fr(alex, t1, 1), rel(t1), heard(t1),
			clip(alex, t1, 3), fr(alex, t1, 0), fr(alex, t1, 1), fr(alex, t1, 2), fr(alex, t1, 3), heard(t1),
			fr(alex, t1, 4),
		}},
		{Name: "clip_of_unseen_transmission_then_its_grant_is_refused", Receiver: answerer, Steps: []AudioStep{
			heard(t1), clip(alex, t1, 2), fr(alex, t1, 1), heard(t1), fr(alex, t1, 0), heard(t1), fr(alex, t1, 2), heard(t1),
			gr(alex, t1), fr(alex, t1, 2),
		}},
		{Name: "clip_ends_suspended_transmission", Receiver: answerer, Steps: []AudioStep{
			gr(alex, t1), fr(alex, t1, 0), reco, fr(alex, t1, 1), clip(alex, t1, 2),
			fr(alex, t1, 0), fr(alex, t1, 1), fr(alex, t1, 2), heard(t1), gr(alex, t1), gr(alex, t2), fr(alex, t2, 0),
		}},
		{Name: "clip_ends_current_transmission_and_frees_floor", Receiver: answerer, Steps: []AudioStep{
			gr(alex, t1), fr(alex, t1, 0), clip(alex, t1, 1), fr(alex, t1, 1), gr(sam, t2),
		}},
		{Name: "clip_of_own_transmission_naming_peer_is_refused", Receiver: answerer, Steps: []AudioStep{
			gr(sam, t1), rel(t1), clip(alex, t1, 1), fr(alex, t1, 1), heard(t1),
		}},
		{Name: "resealed_frame_is_refused", Receiver: answerer, Steps: []AudioStep{
			gr(alex, t1), fr(alex, t1, 0), fr(alex, t1, 1), reseal(alex, t1, 1), rel(t1),
			clip(alex, t1, 2), reseal(alex, t1, 1), fr(alex, t1, 1), fr(alex, t1, 2), reseal(alex, t1, 2), heard(t1),
		}},
		{Name: "resumed_talker_resend_is_deduplicated", Receiver: answerer, Steps: []AudioStep{
			gr(alex, t3), txf(0), txf(1), txf(2), rel(t3), heard(t3),
			clip(alex, t3, 5), txf(2), txf(3), txf(3), txf(4), txf(5), heard(t3),
		}},
		{Name: "repeated_clip_after_reconnect_keeps_seq_set", Receiver: answerer, Steps: []AudioStep{
			clip(alex, t1, 1), fr(alex, t1, 0), reco, clip(alex, t1, 1), fr(alex, t1, 0), fr(alex, t1, 1), heard(t1),
		}},
		{Name: "live_and_clip_frames_interleave", Receiver: answerer, Steps: []AudioStep{
			clip(alex, t1, 1), gr(alex, t2), fr(alex, t2, 0), fr(alex, t1, 0), fr(alex, t2, 1), fr(alex, t1, 1),
			heard(t1), heard(t2), fr(alex, t1, 0), fr(alex, t1, 2),
		}},
		{Name: "clip_while_own_transmission_holds_floor", Receiver: answerer, Steps: []AudioStep{
			gr(sam, t2), clip(alex, t1, 0), fr(alex, t1, 0), gr(alex, t3),
		}},
		{Name: "clip_naming_self_is_refused", Receiver: answerer, Steps: []AudioStep{
			clip(sam, t1, 1), fr(sam, t1, 0), heard(t1), clip(alex, t2, 0), fr(alex, t2, 0),
		}},
		{Name: "answerer_refuses_clip_not_from_the_sender", Receiver: answerer, Steps: []AudioStep{
			clip(tablet, t1, 0), fr(tablet, t1, 0), clip(alex, t1, 0), fr(alex, t1, 0),
		}},
		{Name: "sender_peer_is_set_by_first_clip", Receiver: sender, Steps: []AudioStep{
			clip(alex, t1, 0), clip(kit, t1, 0), clip(watch, t2, 0), fr(watch, t2, 0), clip(sam, t3, 0), fr(sam, t3, 0),
			peer(sam), gr(sam, t4), gr(watch, t4),
		}},
		{Name: "sender_peer_is_set_by_peer_joined", Receiver: sender, Steps: []AudioStep{
			peer(kit), peer(sam), clip(watch, t1, 0), gr(watch, t2), clip(sam, t1, 0), fr(sam, t1, 0), gr(sam, t2), fr(sam, t2, 0),
		}},
		{Name: "recovered_sender_hears_clips_before_the_peer_joins", Receiver: sender, Steps: []AudioStep{
			clip(sam, t1, 2), fr(sam, t1, 0), fr(sam, t1, 1), fr(sam, t1, 2), heard(t1), clip(sam, t2, 0), fr(sam, t2, 0),
			peer(sam), gr(alex, t3), heard(t3),
		}},
	}
	fillExpect(sec.Sessions, sk)
	return sec
}

func checkClip(t *testing.T, c ClipSection) {
	t.Helper()
	checkAudio(t, AudioSection{Transmissions: c.Transmissions})
	byName := map[string]AudioTransmission{}
	for _, tr := range c.Transmissions {
		for i, f := range tr.Frames {
			if int(f.Seq) != i {
				t.Fatalf("clip transmission %s is not contiguous at %d", tr.Name, i)
			}
		}
		byName[tr.Name] = tr
	}
	for _, r := range c.Resume {
		t.Run("clip-resume/"+r.Name, func(t *testing.T) {
			tr, ok := byName[r.Transmission]
			if !ok {
				t.Fatalf("unknown transmission %q", r.Transmission)
			}
			var want [][]byte
			for _, f := range tr.Frames {
				if int(f.Seq) > r.ResumeFrom {
					want = append(want, mustB64(t, f.Frame))
				}
			}
			if len(want) != len(r.Resend) {
				t.Fatalf("resend has %d frames, want %d", len(r.Resend), len(want))
			}
			for i := range want {
				if !bytes.Equal(want[i], mustB64(t, r.Resend[i])) {
					t.Fatalf("resend %d is not the stored frame", i)
				}
			}
		})
	}
	checkSessions(t, "clip-session/", c.Sessions)
}

// TestClipSessionsPinned writes the expected verdicts of section 13.8 out by hand, so the
// generator's model cannot quietly redefine them. A heard step is "send:<last_seq>" or "none";
// released and reconnected steps are "-".
func TestClipSessionsPinned(t *testing.T) {
	want := map[string]string{
		"clip_after_release_plays_unaccepted_seqs":              "accept ok ok - send:1 accept replay replay ok ok send:3 drop",
		"clip_of_unseen_transmission_then_its_grant_is_refused": "none accept ok none ok send:1 ok send:2 refuse replay",
		"clip_ends_suspended_transmission":                      "accept ok - drop accept replay ok ok send:2 refuse accept ok",
		"clip_ends_current_transmission_and_frees_floor":        "accept ok accept ok accept",
		"clip_of_own_transmission_naming_peer_is_refused":       "accept - refuse drop none",
		"resealed_frame_is_refused":                             "accept ok ok replay - accept replay replay ok replay send:2",
		"resumed_talker_resend_is_deduplicated":                 "accept ok ok ok - send:2 accept replay ok replay ok ok send:5",
		"repeated_clip_after_reconnect_keeps_seq_set":           "accept ok - accept replay ok send:1",
		"live_and_clip_frames_interleave":                       "accept accept ok ok ok ok send:1 send:1 replay drop",
		"clip_while_own_transmission_holds_floor":               "accept accept ok refuse",
		"clip_naming_self_is_refused":                           "refuse drop none accept ok",
		"answerer_refuses_clip_not_from_the_sender":             "refuse drop accept ok",
		"sender_peer_is_set_by_first_clip":                      "refuse refuse accept ok refuse drop refuse refuse accept",
		"sender_peer_is_set_by_peer_joined":                     "refuse accept refuse refuse accept ok accept ok",
		"recovered_sender_hears_clips_before_the_peer_joins":    "accept ok ok ok send:2 accept ok accept accept none",
	}
	sessions := clipVectors().Sessions
	if len(sessions) != len(want) {
		t.Fatalf("%d sessions, %d pinned", len(sessions), len(want))
	}
	for _, sc := range sessions {
		var got []string
		for _, st := range sc.Steps {
			switch {
			case st.Op == "heard" && st.LastSeq != nil:
				got = append(got, fmt.Sprintf("send:%d", *st.LastSeq))
			case st.Expect == "":
				got = append(got, "-")
			default:
				got = append(got, st.Expect)
			}
		}
		if g := strings.Join(got, " "); g != want[sc.Name] {
			t.Errorf("%s:\n got %s\nwant %s", sc.Name, g, want[sc.Name])
		}
	}
}
