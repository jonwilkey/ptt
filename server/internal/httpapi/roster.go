package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"net/http"

	"github.com/jonwilkey/ptt/server/internal/roster"
	"github.com/jonwilkey/ptt/server/internal/signed"
)

// RosterNotifier is told after a roster is accepted and stored, so it can push it to the
// family's phones. It must not block the request for long and its failures never fail the PUT.
type RosterNotifier interface {
	RosterPublished(ctx context.Context, version int64)
}

// NopRosterNotifier does nothing; it is the default when no notifier is configured.
type NopRosterNotifier struct{}

// RosterPublished implements RosterNotifier.
func (NopRosterNotifier) RosterPublished(context.Context, int64) {}

// getRoster returns the stored signed roster byte for byte, or 404 if none was published.
func (a *api) getRoster(w http.ResponseWriter, r *http.Request) {
	row, err := a.q.GetRoster(r.Context())
	if errors.Is(err, sql.ErrNoRows) {
		notFound(w, r)
		return
	}
	if err != nil {
		a.serverError(w, r, "get roster", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(row.SignedRoster)
}

// putRoster accepts a new signed roster under the rules of docs/protocol.md section 4.
// A malformed envelope is invalid_request; everything the rules reject is roster_invalid.
func (a *api) putRoster(w http.ResponseWriter, r *http.Request) {
	var req signed.Wire
	if !decodeBody(w, r, &req) {
		return
	}
	obj, err := signed.Decode(req)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	caller, _ := CallerFrom(r.Context())
	res, err := roster.Publish(r.Context(), a.db, a.now(), caller.DeviceID, obj)
	switch {
	case errors.Is(err, roster.ErrInvalid):
		writeError(w, http.StatusBadRequest, "roster_invalid")
		return
	case errors.Is(err, roster.ErrStale):
		writeError(w, http.StatusConflict, "roster_stale")
		return
	case err != nil:
		a.serverError(w, r, "publish roster", err)
		return
	}
	// The roster is committed; a client that hung up must not stop the push.
	a.notifier.RosterPublished(context.WithoutCancel(r.Context()), res.Version)
	w.WriteHeader(http.StatusNoContent)
}
