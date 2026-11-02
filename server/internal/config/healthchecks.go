package config

import (
	"fmt"
	"net/url"
	"strings"
)

// Healthchecks.io settings (docs/operations.md, "Monitoring").
const (
	// EnvHealthchecksPingKey is the secret name of the project ping key; the file path is
	// PTTD_HEALTHCHECKS_PING_KEY_FILE (see ReadSecret).
	EnvHealthchecksPingKey = "PTTD_HEALTHCHECKS_PING_KEY"
	EnvHealthchecksURL     = "PTTD_HEALTHCHECKS_URL"

	DefaultHealthchecksURL = "https://hc-ping.com"
)

// Healthchecks is the ping configuration. PingKey is a secret: never log it.
type Healthchecks struct {
	// PingKey is empty when pings are off.
	PingKey string
	// BaseURL has no trailing slash.
	BaseURL string
}

// LoadHealthchecks reads the ping key file and the base URL. An unset key means no pings.
func LoadHealthchecks(lookup LookupFunc) (Healthchecks, error) {
	key, _, err := ReadSecret(lookup, EnvHealthchecksPingKey)
	if err != nil {
		return Healthchecks{}, err
	}
	base := DefaultHealthchecksURL
	if v, ok := lookup(EnvHealthchecksURL); ok && strings.TrimSpace(v) != "" {
		base = strings.TrimSpace(v)
	}
	u, err := url.Parse(base)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Hostname() == "" ||
		u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return Healthchecks{}, fmt.Errorf("%s: %q is not an absolute http(s) URL without credentials, query or fragment",
			EnvHealthchecksURL, base)
	}
	return Healthchecks{PingKey: key, BaseURL: strings.TrimRight(u.String(), "/")}, nil
}
