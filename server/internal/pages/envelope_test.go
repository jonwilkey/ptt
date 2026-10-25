package pages

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/wire"
)

const (
	idPage    = "5f0c2a9e-3b1d-4c7a-9e21-6d8b0f4a7c13"
	idSession = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
	idFrom    = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
	idUser    = "0b7e4c1d-9a2f-4e68-b3c5-71d0a9e2f845"
)

var key80 = wire.B64u.EncodeToString(make([]byte, 80))

func envJSON(mod func(m map[string]any)) string {
	m := map[string]any{
		"format": 1, "page_id": idPage, "session_id": idSession, "from_device": idFrom,
		"to_user": idUser, "level": "normal", "created_at": 1789920000000,
		"keys": map[string]string{idFrom: key80},
	}
	if mod != nil {
		mod(m)
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func TestParseEnvelopeValid(t *testing.T) {
	other := "0a000000-0000-4000-8000-000000000000"
	env, err := ParseEnvelope([]byte(envJSON(func(m map[string]any) {
		m["keys"] = map[string]string{idFrom: key80, other: key80}
	})))
	if err != nil {
		t.Fatal(err)
	}
	if env.PageID != idPage || env.SessionID != idSession || env.FromDevice != idFrom || env.ToUser != idUser ||
		env.Level != "normal" || env.CreatedAt != 1789920000000 {
		t.Errorf("env = %+v", env)
	}
	if len(env.Keys) != 2 || env.Keys[0] != other {
		t.Errorf("keys = %v, want sorted [%s %s]", env.Keys, other, idFrom)
	}
}

func TestParseEnvelopeRejects(t *testing.T) {
	good := envJSON(nil)
	tests := map[string]string{
		"valid json but not an object": `[]`,
		"duplicate field":              strings.Replace(good, `"level":"normal"`, `"level":"normal","level":"urgent"`, 1),
		"wrong case":                   strings.Replace(good, `"level"`, `"Level"`, 1),
		"unknown field":                strings.Replace(good, `{"created_at"`, `{"extra":1,"created_at"`, 1),
		"missing field":                envJSON(func(m map[string]any) { delete(m, "session_id") }),
		"null field":                   envJSON(func(m map[string]any) { m["level"] = nil }),
		"trailing data":                good + " {}",
		"format 2":                     envJSON(func(m map[string]any) { m["format"] = 2 }),
		"format as string":             envJSON(func(m map[string]any) { m["format"] = "1" }),
		"fractional created_at":        strings.Replace(good, `"created_at":1789920000000`, `"created_at":1.5`, 1),
		"zero created_at":              envJSON(func(m map[string]any) { m["created_at"] = 0 }),
		"bad level":                    envJSON(func(m map[string]any) { m["level"] = "loud" }),
		"uppercase page id":            envJSON(func(m map[string]any) { m["page_id"] = strings.ToUpper(idPage) }),
		"bad session id":               envJSON(func(m map[string]any) { m["session_id"] = "nope" }),
		"keys not an object":           envJSON(func(m map[string]any) { m["keys"] = []string{key80} }),
		"empty keys":                   envJSON(func(m map[string]any) { m["keys"] = map[string]string{} }),
		"key id not a uuid":            envJSON(func(m map[string]any) { m["keys"] = map[string]string{"dev": key80} }),
		"key id uppercase":             envJSON(func(m map[string]any) { m["keys"] = map[string]string{strings.ToUpper(idFrom): key80} }),
		"duplicate key id":             strings.Replace(good, `"keys":{"`+idFrom+`":"`+key80+`"}`, `"keys":{"`+idFrom+`":"`+key80+`","`+idFrom+`":"`+key80+`"}`, 1),
		"null key value":               strings.Replace(good, `"`+key80+`"`, `null`, 1),
		"key value not a string":       envJSON(func(m map[string]any) { m["keys"] = map[string]int{idFrom: 1} }),
		"key too short": envJSON(func(m map[string]any) {
			m["keys"] = map[string]string{idFrom: wire.B64u.EncodeToString(make([]byte, 79))}
		}),
		"key too long": envJSON(func(m map[string]any) {
			m["keys"] = map[string]string{idFrom: wire.B64u.EncodeToString(make([]byte, 81))}
		}),
		"key padded":            envJSON(func(m map[string]any) { m["keys"] = map[string]string{idFrom: key80 + "="} }),
		"key standard alphabet": envJSON(func(m map[string]any) { m["keys"] = map[string]string{idFrom: strings.Repeat("+", 108)} }),
		"invalid utf-8":         "{\"format\":1,\"x\xff\":1}",
	}
	for name, in := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := ParseEnvelope([]byte(in)); err == nil {
				t.Fatalf("accepted %s", in)
			}
		})
	}
}

func TestParseEnvelopeTooManyKeys(t *testing.T) {
	keys := map[string]string{}
	for range maxRecipients + 1 {
		keys[uuid.NewString()] = key80
	}
	if _, err := ParseEnvelope([]byte(envJSON(func(m map[string]any) { m["keys"] = keys }))); err == nil {
		t.Fatal("accepted too many keys")
	}
	delete(keys, func() string {
		for k := range keys {
			return k
		}
		return ""
	}())
	if _, err := ParseEnvelope([]byte(envJSON(func(m map[string]any) { m["keys"] = keys }))); err != nil {
		t.Fatalf("rejected %d keys: %v", len(keys), err)
	}
}
