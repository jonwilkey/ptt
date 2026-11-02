//go:build drill

package app

import "errors"

func init() { errDrill = errors.New("rollback drill build") }
