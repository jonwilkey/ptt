package admin

import (
	"bytes"
	"strings"
	"testing"

	"rsc.io/qr"
)

func TestPrintQR(t *testing.T) {
	const text = "pttenroll1:eyJ1cmwiOiJodHRwczovL3B0dC5leGFtcGxlLmNvbSJ9"
	var buf bytes.Buffer
	if err := PrintQR(&buf, text); err != nil {
		t.Fatal(err)
	}

	code, err := qr.Encode(text, qr.M)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSuffix(buf.String(), "\n"), "\n")
	width := code.Size + 2*quietZone
	wantRows := (width + 1) / 2
	if len(lines) != wantRows {
		t.Fatalf("rows = %d, want %d", len(lines), wantRows)
	}

	// Decode the half blocks back into modules and compare with the encoder's matrix.
	for row, line := range lines {
		cells := []rune(line)
		if len(cells) != width {
			t.Fatalf("row %d has %d columns, want %d", row, len(cells), width)
		}
		for col, c := range cells {
			var top, bottom bool
			switch c {
			case '█':
				top, bottom = true, true
			case '▀':
				top = true
			case '▄':
				bottom = true
			case ' ':
			default:
				t.Fatalf("unexpected rune %q", c)
			}
			x, y := col-quietZone, 2*row-quietZone
			if got := isLight(code, x, y); got != top {
				t.Fatalf("module (%d,%d) light=%v, want %v", x, y, top, got)
			}
			if got := isLight(code, x, y+1); got != bottom {
				t.Fatalf("module (%d,%d) light=%v, want %v", x, y+1, bottom, got)
			}
		}
	}

	// The quiet zone is solid light: the first row and the first columns are all full blocks.
	if lines[0] != strings.Repeat("█", width) {
		t.Errorf("top quiet zone = %q", lines[0])
	}
}

func isLight(c *qr.Code, x, y int) bool {
	return x < 0 || y < 0 || x >= c.Size || y >= c.Size || !c.Black(x, y)
}
