package httpapi

import (
	"context"
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/jonwilkey/ptt/server/internal/pages"
)

// PageService is the page lifecycle the handlers call (implemented by *pages.Service). The
// handlers only translate: parsing, authorization and every state decision live there.
type PageService interface {
	Create(ctx context.Context, caller pages.Caller, req pages.CreateRequest) (pages.CreateResult, error)
	Delivered(ctx context.Context, deviceID, pageID string) (string, error)
	Acknowledge(ctx context.Context, deviceID, pageID string) error
	Answer(ctx context.Context, deviceID, pageID string) error
	Cancel(ctx context.Context, deviceID, pageID string) error
}

// PageResponse is the body of a successful POST /v1/pages.
type PageResponse struct {
	ID    string `json:"id"`
	State string `json:"state"`
}

// pageRoutes registers the page endpoints (docs/protocol.md section 6); they need only a device.
func (a *api) pageRoutes(r chi.Router) {
	r.Post("/v1/pages", a.createPage)
	r.Post("/v1/pages/{id}/delivered", a.delivered)
	r.Post("/v1/pages/{id}/acknowledge", a.pageAction(a.pages.Acknowledge))
	r.Post("/v1/pages/{id}/answer", a.pageAction(a.pages.Answer))
	r.Post("/v1/pages/{id}/cancel", a.pageAction(a.pages.Cancel))
}

func (a *api) createPage(w http.ResponseWriter, r *http.Request) {
	var req pages.CreateRequest
	if !decodeBody(w, r, &req) {
		return
	}
	c, _ := CallerFrom(r.Context())
	res, err := a.pages.Create(r.Context(), pages.Caller{DeviceID: c.DeviceID, UserID: c.UserID}, req)
	if err != nil {
		a.pageError(w, r, "create page", err)
		return
	}
	status := http.StatusOK
	if res.Created {
		status = http.StatusCreated
	}
	writeJSON(w, status, PageResponse{ID: req.ID, State: res.State})
}

// DeliveredResponse is the body of a successful delivered call.
type DeliveredResponse struct {
	State string `json:"state"`
}

func (a *api) delivered(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	if !validUUID(id) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	c, _ := CallerFrom(r.Context())
	state, err := a.pages.Delivered(r.Context(), c.DeviceID, id)
	if err != nil {
		a.pageError(w, r, "page delivered", err)
		return
	}
	writeJSON(w, http.StatusOK, DeliveredResponse{State: state})
}

// pageAction adapts a transition: 204 on success, otherwise the error mapping.
func (a *api) pageAction(f func(ctx context.Context, deviceID, pageID string) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		if !validUUID(id) {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		c, _ := CallerFrom(r.Context())
		if err := f(r.Context(), c.DeviceID, id); err != nil {
			a.pageError(w, r, "page action", err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// pageError maps the lifecycle's outcomes to protocol section 1's codes.
func (a *api) pageError(w http.ResponseWriter, r *http.Request, op string, err error) {
	var limited *pages.RateLimitedError
	switch {
	case errors.As(err, &limited):
		rateLimited(w, limited.RetryAfter)
	case errors.Is(err, pages.ErrInvalid):
		writeError(w, http.StatusBadRequest, "invalid_request")
	case errors.Is(err, pages.ErrForbidden):
		writeError(w, http.StatusForbidden, "forbidden")
	case errors.Is(err, pages.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found")
	case errors.Is(err, pages.ErrConflict):
		writeError(w, http.StatusConflict, "conflict")
	case errors.Is(err, pages.ErrAlreadyAnswered):
		writeError(w, http.StatusConflict, "already_answered")
	case errors.Is(err, pages.ErrEnded):
		writeError(w, http.StatusGone, "page_ended")
	default:
		a.serverError(w, r, op, err)
	}
}
