package wire

import (
	"strings"
	"testing"
)

func TestStrictObject(t *testing.T) {
	got, err := StrictObject([]byte(` {"a": 1, "b": {"x":[1,2]}, "c":"s"} `), "a", "b", "c")
	if err != nil {
		t.Fatal(err)
	}
	if string(got["a"]) != "1" || string(got["b"]) != `{"x":[1,2]}` || string(got["c"]) != `"s"` {
		t.Errorf("raw values = %q", got)
	}

	bad := map[string]string{
		"duplicate":         `{"admin":true,"admin":false}`,
		"escaped duplicate": `{"admin":true,"\u0061dmin":false}`,
		"wrong case":        `{"Admin":true}`,
		"unknown key":       `{"admin":true,"zzz":1}`,
		"missing key":       `{}`,
		"null value":        `{"admin":null}`,
		"array":             `[]`,
		"string":            `"admin"`,
		"null document":     `null`,
		"empty":             ``,
		"trailing data":     `{"admin":true} {}`,
		"trailing garbage":  `{"admin":true}x`,
		"truncated":         `{"admin":true`,
		"invalid utf-8":     "{\"admin\":true,\"admin\xff\":1}",
		"invalid utf-8 val": "{\"admin\":\"\xff\"}",
	}
	for name, in := range bad {
		t.Run(name, func(t *testing.T) {
			if _, err := StrictObject([]byte(in), "admin"); err == nil {
				t.Fatal("accepted")
			}
		})
	}
}

func TestValidText(t *testing.T) {
	tests := []struct {
		s    string
		want bool
	}{
		{"Alex", true},
		{"Alex's phone", true},
		{strings.Repeat("é", 64), true},
		{strings.Repeat("é", 65), false},
		{"", false},
		{"   ", false},
		{"a\x07b", false},
		{"a\nb", false},
		{"a\xffb", false},
	}
	for _, tt := range tests {
		if got := ValidText(tt.s, 64); got != tt.want {
			t.Errorf("ValidText(%q) = %v, want %v", tt.s, got, tt.want)
		}
	}
}
