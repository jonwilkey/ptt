// Package migrations embeds the goose SQL migrations so the binary carries its own schema.
//
// Files are named NNNNN_description.sql and are never edited once released; follow
// expand-then-contract so the previous release keeps working against the new schema.
package migrations

import "embed"

// FS holds every *.sql migration in this directory.
//
//go:embed *.sql
var FS embed.FS
