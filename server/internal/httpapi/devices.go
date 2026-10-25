package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/jonwilkey/ptt/server/internal/adminops"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

const (
	maxChecklistItems = 32
	maxChecklistKey   = 64
)

// UpdateSelfRequest is the body of PUT /v1/devices/me. Absent (or null) fields are unchanged.
type UpdateSelfRequest struct {
	FCMToken   *string         `json:"fcm_token"`
	AppVersion *string         `json:"app_version"`
	DeviceName *string         `json:"device_name"`
	Checklist  map[string]bool `json:"checklist"`
}

// updateSelf lets a phone refresh its FCM token, version, name and setup checklist.
func (a *api) updateSelf(w http.ResponseWriter, r *http.Request) {
	var req UpdateSelfRequest
	if !decodeBody(w, r, &req) {
		return
	}
	if (req.FCMToken != nil && (*req.FCMToken == "" || len(*req.FCMToken) > maxFCMTokenBytes)) ||
		(req.AppVersion != nil && !validText(*req.AppVersion, maxAppVersionRunes)) ||
		(req.DeviceName != nil && !validText(*req.DeviceName, maxNameRunes)) ||
		len(req.Checklist) > maxChecklistItems {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	for k := range req.Checklist {
		if !validText(k, maxChecklistKey) {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
	}
	var checklist sql.NullString
	if req.Checklist != nil {
		// The map was decoded from a JSON object of booleans, so it always re-encodes.
		b, _ := json.Marshal(req.Checklist)
		checklist = sql.NullString{String: string(b), Valid: true}
	}

	caller, _ := CallerFrom(r.Context())
	err := a.q.UpdateDeviceSelf(r.Context(), sqlcdb.UpdateDeviceSelfParams{
		FcmToken:        ptrText(req.FCMToken),
		AppVersion:      ptrText(req.AppVersion),
		Name:            ptrText(req.DeviceName),
		ChecklistStatus: checklist,
		ID:              caller.DeviceID,
	})
	if err != nil {
		a.serverError(w, r, "update device", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func ptrText(s *string) sql.NullString {
	if s == nil {
		return sql.NullString{}
	}
	return sql.NullString{String: *s, Valid: true}
}

// revokeDevice revokes a device; revoking an already-revoked one succeeds (idempotent), and an
// admin may revoke their own device.
func (a *api) revokeDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validUUID(id) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	caller, _ := CallerFrom(r.Context())
	_, err := adminops.RevokeAs(r.Context(), a.db, id, caller.DeviceID, a.now())
	switch {
	case errors.Is(err, adminops.ErrUnknownDevice):
		writeError(w, http.StatusNotFound, "not_found")
	case err != nil:
		a.serverError(w, r, "revoke device", err)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// Contact is one person in GET /v1/contacts.
type Contact struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
	Admin       bool   `json:"admin"`
	Presence    string `json:"presence"`
}

// ContactsResponse is the body of GET /v1/contacts.
type ContactsResponse struct {
	Users []Contact `json:"users"`
}

// contacts lists every person with at least one live, non-test device, with a presence summary:
// offline if no device was seen in 48 h, else warning if any device's checklist has a false
// item, else ok.
func (a *api) contacts(w http.ResponseWriter, r *http.Request) {
	rows, err := a.q.ListContactDevices(r.Context())
	if err != nil {
		a.serverError(w, r, "list contacts", err)
		return
	}
	cutoff := a.now().Add(-offlineAfter)

	// Rows arrive grouped by person, so one pass with a running entry suffices.
	users := []Contact{}
	var seen, warn bool
	flush := func() {
		if n := len(users); n > 0 {
			users[n-1].Presence = presence(seen, warn)
		}
	}
	for _, row := range rows {
		if n := len(users); n == 0 || users[n-1].ID != row.UserID {
			flush()
			users = append(users, Contact{ID: row.UserID, DisplayName: row.DisplayName, Admin: row.IsAdmin == 1})
			seen, warn = false, false
		}
		if row.LastSeenAt.Valid && time.UnixMilli(row.LastSeenAt.Int64).After(cutoff) {
			seen = true
		}
		warn = warn || checklistFailing(row.ChecklistStatus)
	}
	flush()
	writeJSON(w, http.StatusOK, ContactsResponse{Users: users})
}

func presence(seen, warn bool) string {
	switch {
	case !seen:
		return "offline"
	case warn:
		return "warning"
	default:
		return "ok"
	}
}

// checklistFailing reports whether a stored checklist has a false item. Unparseable data
// (never written by this server) counts as no warning.
func checklistFailing(s sql.NullString) bool {
	if !s.Valid {
		return false
	}
	var items map[string]bool
	if json.Unmarshal([]byte(s.String), &items) != nil {
		return false
	}
	for _, ok := range items {
		if !ok {
			return true
		}
	}
	return false
}
