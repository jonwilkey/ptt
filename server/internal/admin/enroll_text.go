package admin

import (
	"encoding/base64"
	"encoding/json"
)

// EnrollPrefix starts every enrollment QR payload (docs/protocol.md section 5).
const EnrollPrefix = "pttenroll1:"

// BootstrapQRText is the text of a bootstrap enrollment QR: EnrollPrefix + b64u({"url","code"}).
// Bootstrap QRs omit the secret and the admin list (docs/protocol.md section 5).
func BootstrapQRText(publicURL, code string) (string, error) {
	payload, err := json.Marshal(struct {
		URL  string `json:"url"`
		Code string `json:"code"`
	}{publicURL, code})
	if err != nil {
		return "", err
	}
	return EnrollPrefix + base64.RawURLEncoding.EncodeToString(payload), nil
}
