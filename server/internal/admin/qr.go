package admin

import (
	"fmt"
	"io"
	"strings"

	"rsc.io/qr"
)

// quietZone is the blank border, in modules, that the QR specification requires around a code.
const quietZone = 4

// PrintQR writes text as a QR code made of Unicode half blocks, two modules per character row.
//
// Terminals are normally light text on a dark background, so the light modules and the quiet
// zone are the filled blocks and the dark modules are the background: dark-on-light, as the QR
// specification expects. On a light terminal the image comes out inverted, which some scanners
// do not read.
func PrintQR(w io.Writer, text string) error {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return fmt.Errorf("encode QR code: %w", err)
	}
	// light reports whether the module at (x, y) is light, treating everything outside the
	// symbol (the quiet zone and the odd row below an odd-height symbol) as light.
	light := func(x, y int) bool {
		if x < 0 || y < 0 || x >= code.Size || y >= code.Size {
			return true
		}
		return !code.Black(x, y)
	}

	var sb strings.Builder
	lo, hi := -quietZone, code.Size+quietZone
	for y := lo; y < hi; y += 2 {
		for x := lo; x < hi; x++ {
			switch top, bottom := light(x, y), light(x, y+1); {
			case top && bottom:
				sb.WriteRune('█')
			case top:
				sb.WriteRune('▀')
			case bottom:
				sb.WriteRune('▄')
			default:
				sb.WriteRune(' ')
			}
		}
		sb.WriteByte('\n')
	}
	_, err = io.WriteString(w, sb.String())
	return err
}
