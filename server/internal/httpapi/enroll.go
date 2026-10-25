package httpapi

import (
	"crypto/sha256"
	"database/sql"
	"errors"
	"net/http"

	"github.com/google/uuid"

	"github.com/jonwilkey/ptt/server/internal/adminops"
	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db"
	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

const (
	maxNameRunes       = 64
	maxAppVersionRunes = 32
	maxFCMTokenBytes   = 1024
)

// CreateCodeRequest is the body of POST /v1/enrollment-codes.
type CreateCodeRequest struct {
	DisplayName string `json:"display_name"`
	// MakeAdmin is a pointer so "omitted" (inherit from user_id) differs from false.
	MakeAdmin *bool  `json:"make_admin"`
	UserID    string `json:"user_id"`
}

// CreateCodeResponse is the 201 body of POST /v1/enrollment-codes.
type CreateCodeResponse struct {
	Code      string `json:"code"`
	CodeID    string `json:"code_id"`
	ExpiresAt int64  `json:"expires_at"`
}

// createEnrollmentCode mints a 24 h single-use code for a new person or a new device of an
// existing one. The code is returned once and only its hash is stored.
func (a *api) createEnrollmentCode(w http.ResponseWriter, r *http.Request) {
	var req CreateCodeRequest
	if !decodeBody(w, r, &req) {
		return
	}
	caller, _ := CallerFrom(r.Context())

	displayName, makeAdmin := req.DisplayName, req.MakeAdmin != nil && *req.MakeAdmin
	var userID sql.NullString
	if req.UserID != "" {
		if !validUUID(req.UserID) {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		u, err := a.q.GetUser(r.Context(), req.UserID)
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		if err != nil {
			a.serverError(w, r, "get user", err)
			return
		}
		// The code inherits the person's identity; a request that contradicts it is a mistake.
		userAdmin := u.IsAdmin == 1
		if (req.MakeAdmin != nil && *req.MakeAdmin != userAdmin) ||
			(req.DisplayName != "" && req.DisplayName != u.DisplayName) {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		displayName, makeAdmin = u.DisplayName, userAdmin
		userID = sql.NullString{String: u.ID, Valid: true}
	} else if !validText(displayName, maxNameRunes) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}

	code, hash, err := auth.NewEnrollmentCode()
	if err != nil {
		a.serverError(w, r, "generate code", err)
		return
	}
	now := a.now()
	expires := now.Add(adminops.CodeTTL).UnixMilli()
	err = db.InTx(r.Context(), a.db, func(q *sqlcdb.Queries) error {
		err := q.InsertEnrollmentCode(r.Context(), sqlcdb.InsertEnrollmentCodeParams{
			CodeHash:        hash,
			DisplayName:     displayName,
			MakeAdmin:       boolInt(makeAdmin),
			CreatedByDevice: sql.NullString{String: caller.DeviceID, Valid: true},
			UserID:          userID,
			CreatedAt:       now.UnixMilli(),
			ExpiresAt:       expires,
		})
		if err != nil {
			return err
		}
		// The public code_id identifies the code; the code itself is never recorded.
		var auditUser *string
		if userID.Valid {
			auditUser = &userID.String
		}
		return db.Audit(r.Context(), q, now, caller.DeviceID, "enrollment_code_created",
			map[string]any{"code_id": auth.CodeID(hash), "user_id": auditUser, "make_admin": makeAdmin})
	})
	if err != nil {
		a.serverError(w, r, "insert enrollment code", err)
		return
	}
	writeJSON(w, http.StatusCreated, CreateCodeResponse{Code: code, CodeID: auth.CodeID(hash), ExpiresAt: expires})
}

// CodeDevice is the enrolled device in a CodeStatusResponse.
type CodeDevice struct {
	ID          string `json:"id"`
	UserID      string `json:"user_id"`
	DisplayName string `json:"display_name"`
	DeviceName  string `json:"device_name"`
	Admin       bool   `json:"admin"`
	Ed25519     string `json:"ed25519"`
	X25519      string `json:"x25519"`
	MAC         string `json:"mac,omitempty"`
}

// CodeStatusResponse is the body of GET /v1/enrollment-codes/{code_id}.
type CodeStatusResponse struct {
	Status string      `json:"status"`
	Device *CodeDevice `json:"device,omitempty"`
}

// getEnrollmentCode reports whether a code is pending, used or expired. Used codes carry the
// enrolled device's keys and MAC, which the admin phone verifies before signing the roster.
func (a *api) getEnrollmentCode(w http.ResponseWriter, r *http.Request) {
	hash, ok := decodeB64u(r.PathValue("code_id"), sha256.Size)
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	code, err := a.q.GetEnrollmentCode(r.Context(), hash)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if err != nil {
		a.serverError(w, r, "get enrollment code", err)
		return
	}

	switch {
	case code.UsedAt.Valid:
		d, err := a.q.GetDeviceWithUser(r.Context(), code.DeviceID.String)
		if err != nil {
			a.serverError(w, r, "get enrolled device", err)
			return
		}
		writeJSON(w, http.StatusOK, CodeStatusResponse{Status: "used", Device: &CodeDevice{
			ID:          d.ID,
			UserID:      d.UserID,
			DisplayName: d.DisplayName,
			DeviceName:  d.Name,
			Admin:       d.IsAdmin == 1,
			Ed25519:     b64u.EncodeToString(d.Ed25519Pub),
			X25519:      b64u.EncodeToString(d.X25519Pub),
			MAC:         b64u.EncodeToString(code.EnrollMac),
		}})
	case code.ExpiresAt <= a.now().UnixMilli():
		writeJSON(w, http.StatusOK, CodeStatusResponse{Status: "expired"})
	default:
		writeJSON(w, http.StatusOK, CodeStatusResponse{Status: "pending"})
	}
}

// EnrollRequest is the body of POST /v1/enroll.
type EnrollRequest struct {
	Code       string  `json:"code"`
	DeviceID   string  `json:"device_id"`
	DeviceName string  `json:"device_name"`
	Ed25519    string  `json:"ed25519"`
	X25519     string  `json:"x25519"`
	MAC        *string `json:"mac"`
	FCMToken   string  `json:"fcm_token"`
	AppVersion string  `json:"app_version"`
}

// EnrollResponse is the 201 body of POST /v1/enroll.
type EnrollResponse struct {
	Token       string `json:"token"`
	DeviceID    string `json:"device_id"`
	UserID      string `json:"user_id"`
	DisplayName string `json:"display_name"`
	Admin       bool   `json:"admin"`
}

// Outcomes of the enrollment transaction that are answers, not failures.
var (
	errCodeInvalid = errors.New("enrollment code invalid")
	errDeviceTaken = errors.New("device id already enrolled")
	errMACPresence = errors.New("mac presence does not match the code type")
)

// enroll redeems a code for a new device. Everything in the body is validated before the code is
// touched, and the redemption, the user (if new) and the device are one transaction, so a
// failure never burns a code. Rate limiting counts every attempt, valid or not, because code
// guessing is what it exists to slow.
func (a *api) enroll(w http.ResponseWriter, r *http.Request) {
	if ok, retry := a.enrollLimiter.Allow(clientIP(r)); !ok {
		rateLimited(w, retry)
		return
	}
	var req EnrollRequest
	if !decodeBody(w, r, &req) {
		return
	}

	codeHash, err := auth.HashEnrollmentCode(req.Code)
	edKey, edOK := decodeB64u(req.Ed25519, 32)
	xKey, xOK := decodeB64u(req.X25519, 32)
	var mac []byte
	macOK := true
	if req.MAC != nil {
		mac, macOK = decodeB64u(*req.MAC, sha256.Size)
	}
	if err != nil || !edOK || !xOK || !macOK ||
		!validUUID(req.DeviceID) ||
		!validText(req.DeviceName, maxNameRunes) ||
		len(req.FCMToken) > maxFCMTokenBytes ||
		!validOptionalText(req.AppVersion) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}

	token, tokenHash, err := auth.NewToken()
	if err != nil {
		a.serverError(w, r, "generate token", err)
		return
	}
	now := a.now()
	resp := EnrollResponse{Token: token, DeviceID: req.DeviceID}

	err = db.InTx(r.Context(), a.db, func(q *sqlcdb.Queries) error {
		code, err := q.GetEnrollmentCode(r.Context(), codeHash)
		if errors.Is(err, sql.ErrNoRows) {
			return errCodeInvalid
		}
		if err != nil {
			return err
		}
		if code.UsedAt.Valid || code.ExpiresAt <= now.UnixMilli() {
			return errCodeInvalid
		}
		// Codes minted by an admin phone are MAC-protected; bootstrap codes are not.
		if code.CreatedByDevice.Valid != (mac != nil) {
			return errMACPresence
		}
		if n, err := q.DeviceExists(r.Context(), req.DeviceID); err != nil {
			return err
		} else if n > 0 {
			return errDeviceTaken
		}

		resp.UserID, resp.DisplayName, resp.Admin = code.UserID.String, code.DisplayName, code.MakeAdmin == 1
		if code.UserID.Valid {
			u, err := q.GetUser(r.Context(), code.UserID.String)
			if errors.Is(err, sql.ErrNoRows) {
				return errCodeInvalid
			}
			if err != nil {
				return err
			}
			resp.DisplayName, resp.Admin = u.DisplayName, u.IsAdmin == 1
		} else {
			resp.UserID = uuid.NewString()
			err := q.InsertUser(r.Context(), sqlcdb.InsertUserParams{
				ID:          resp.UserID,
				DisplayName: code.DisplayName,
				IsAdmin:     code.MakeAdmin,
				CreatedAt:   now.UnixMilli(),
			})
			if err != nil {
				return err
			}
		}

		err = q.InsertDevice(r.Context(), sqlcdb.InsertDeviceParams{
			ID:         req.DeviceID,
			UserID:     resp.UserID,
			Name:       req.DeviceName,
			TokenHash:  tokenHash,
			FcmToken:   optText(req.FCMToken),
			AppVersion: optText(req.AppVersion),
			CreatedAt:  now.UnixMilli(),
			LastSeenAt: sql.NullInt64{Int64: now.UnixMilli(), Valid: true},
			Ed25519Pub: edKey,
			X25519Pub:  xKey,
		})
		if err != nil {
			return err
		}
		n, err := q.RedeemEnrollmentCode(r.Context(), sqlcdb.RedeemEnrollmentCodeParams{
			Now:       sql.NullInt64{Int64: now.UnixMilli(), Valid: true},
			DeviceID:  sql.NullString{String: req.DeviceID, Valid: true},
			EnrollMac: mac,
			CodeHash:  codeHash,
		})
		if err != nil {
			return err
		}
		if n != 1 {
			return errCodeInvalid
		}
		return db.Audit(r.Context(), q, now, req.DeviceID, "device_enrolled",
			map[string]any{"user_id": resp.UserID, "admin": resp.Admin})
	})
	switch {
	case errors.Is(err, errCodeInvalid):
		writeError(w, http.StatusBadRequest, "code_invalid")
	case errors.Is(err, errMACPresence):
		writeError(w, http.StatusBadRequest, "invalid_request")
	case errors.Is(err, errDeviceTaken):
		writeError(w, http.StatusConflict, "conflict")
	case err != nil:
		a.serverError(w, r, "enroll", err)
	default:
		writeJSON(w, http.StatusCreated, resp)
	}
}

func boolInt(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

// optText maps "" to NULL.
func optText(s string) sql.NullString { return sql.NullString{String: s, Valid: s != ""} }

// validOptionalText accepts "" or valid text.
func validOptionalText(s string) bool { return s == "" || validText(s, maxAppVersionRunes) }
