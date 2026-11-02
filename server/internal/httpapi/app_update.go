package httpapi

import (
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

// apkWriteTimeout replaces the listener's write timeout for an APK download, so a slow cellular
// connection is not cut off (docs/protocol.md section 14.3).
const apkWriteTimeout = 10 * time.Minute

const apkContentType = "application/vnd.android.package-archive"

// appLatest answers GET /v1/app/latest.
func (a *api) appLatest(w http.ResponseWriter, _ *http.Request) {
	latest, err := a.apks.Latest()
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	writeJSON(w, http.StatusOK, latest)
}

// appAPK answers GET /v1/app/apk/{version}.
func (a *api) appAPK(w http.ResponseWriter, r *http.Request) {
	f, info, err := a.apks.Open(chi.URLParam(r, "version"))
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	defer func() { _ = f.Close() }()

	rc := http.NewResponseController(w)
	if err := rc.SetWriteDeadline(time.Now().Add(apkWriteTimeout)); err != nil && !errors.Is(err, http.ErrNotSupported) {
		a.logger.LogAttrs(r.Context(), slog.LevelWarn, "extend write deadline", slog.String("error", err.Error()))
	}
	w.Header().Set("Content-Type", apkContentType)
	w.Header().Set("Cache-Control", "no-store")
	http.ServeContent(w, r, "", info.ModTime(), f)
}
