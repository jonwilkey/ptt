package roster

import (
	"encoding/base64"
	"strings"
	"testing"
)

const (
	idA = "0b7e4c1d-9a2f-4e68-b3c5-71d0a9e2f845"
	idB = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
)

var key = base64.RawURLEncoding.EncodeToString(make([]byte, 32))

func dev(id, extra string) string {
	return `{"id":"` + id + `","user_id":"` + idA + `","display_name":"Alex","device_name":"Phone","admin":true,"ed25519":"` +
		key + `","x25519":"` + key + `"` + extra + `}`
}

func payload(head string, devices ...string) []byte {
	return []byte(`{` + head + `,"devices":[` + strings.Join(devices, ",") + `]}`)
}

const head = `"format":1,"version":2,"issued_at":5`

func TestParse(t *testing.T) {
	r, err := Parse(payload(head, dev(idA, ""), dev(idB, "")))
	if err != nil || r.Version != 2 || len(r.Devices) != 2 {
		t.Fatalf("valid roster: %+v %v", r, err)
	}
	if d, ok := r.Find(idB); !ok || !d.Admin || len(d.Ed25519) != 32 {
		t.Errorf("Find = %+v %v", d, ok)
	}

	bad := map[string][]byte{
		"format 2":        payload(`"format":2,"version":2,"issued_at":5`, dev(idA, "")),
		"version 0":       payload(`"format":1,"version":0,"issued_at":5`, dev(idA, "")),
		"no issued_at":    payload(`"format":1,"version":2`, dev(idA, "")),
		"duplicate id":    payload(head, dev(idA, ""), dev(idA, "")),
		"unsorted":        payload(head, dev(idB, ""), dev(idA, "")),
		"uppercase id":    payload(head, dev(strings.ToUpper(idA), "")),
		"unknown field":   payload(head, dev(idA, `,"x":1`)),
		"unknown top":     payload(head+`,"x":1`, dev(idA, "")),
		"short key":       []byte(strings.Replace(string(payload(head, dev(idA, ""))), key, "AAAA", 1)),
		"empty name":      []byte(strings.Replace(string(payload(head, dev(idA, ""))), `"Phone"`, `" "`, 1)),
		"Admin key":       []byte(strings.Replace(string(payload(head, dev(idA, ""))), `"admin"`, `"Admin"`, 1)),
		"duplicate admin": payload(head, dev(idA, `,"admin":false`)),
		"missing admin":   []byte(strings.Replace(string(payload(head, dev(idA, ""))), `"admin":true,`, ``, 1)),
		"devices null":    []byte(`{` + head + `,"devices":null}`),
		"devices missing": []byte(`{` + head + `}`),
		"no devices":      payload(head),
		"no admin":        []byte(strings.Replace(string(payload(head, dev(idA, ""))), `"admin":true`, `"admin":false`, 1)),
		"control char":    []byte(strings.Replace(string(payload(head, dev(idA, ""))), `"Phone"`, `"Ph\u0007one"`, 1)),
		"Version key":     []byte(strings.Replace(string(payload(head, dev(idA, ""))), `"version"`, `"Version"`, 1)),
		"trailing data":   append(payload(head, dev(idA, "")), []byte(` {}`)...),
		"not an object":   []byte(`[]`),
		"empty":           nil,
		"padded key":      []byte(strings.Replace(string(payload(head, dev(idA, ""))), key, key+"=", 1)),
		"standard base64": []byte(strings.Replace(string(payload(head, dev(idA, ""))), key, strings.Repeat("+", 43), 1)),
	}
	for name, p := range bad {
		t.Run(name, func(t *testing.T) {
			if _, err := Parse(p); err == nil {
				t.Fatal("accepted")
			}
		})
	}
}
