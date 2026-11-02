package pages

import (
	"encoding/json"
	"strings"
	"testing"
)

func senderKeyJSON(mod func(m map[string]any)) string {
	m := map[string]any{
		"format": 1, "page_id": idPage, "session_id": idSession, "from_device": idFrom, "key": key80,
	}
	if mod != nil {
		mod(m)
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func TestParseSenderKey(t *testing.T) {
	good := senderKeyJSON(nil)
	sk, err := ParseSenderKey([]byte(good))
	if err != nil {
		t.Fatal(err)
	}
	if sk.PageID != idPage || sk.SessionID != idSession || sk.FromDevice != idFrom {
		t.Errorf("sender key = %+v", sk)
	}
	for name, in := range map[string]string{
		"duplicate key":     strings.Replace(good, `"format":1`, `"format":1,"format":1`, 1),
		"duplicate id":      strings.Replace(good, `"page_id":"`+idPage+`"`, `"page_id":"`+idPage+`","page_id":"`+idPage+`"`, 1),
		"null id":           senderKeyJSON(func(m map[string]any) { m["page_id"] = nil }),
		"null format":       senderKeyJSON(func(m map[string]any) { m["format"] = nil }),
		"null key":          senderKeyJSON(func(m map[string]any) { m["key"] = nil }),
		"invalid UTF-8":     strings.Replace(good, `"key":"`, "\"key\":\"\xff", 1),
		"invalid UTF-8 key": strings.Replace(good, `"format"`, "\"form\xffat\"", 1),
		"not an object":     `[]`,
		"extra field":       senderKeyJSON(func(m map[string]any) { m["extra"] = 1 }),
		"missing field":     senderKeyJSON(func(m map[string]any) { delete(m, "session_id") }),
		"format 2":          senderKeyJSON(func(m map[string]any) { m["format"] = 2 }),
		"upper-case id":     senderKeyJSON(func(m map[string]any) { m["from_device"] = strings.ToUpper(idFrom) }),
		"short key":         senderKeyJSON(func(m map[string]any) { m["key"] = key80[:len(key80)-4] }),
	} {
		if _, err := ParseSenderKey([]byte(in)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
