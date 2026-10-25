package adminops

import (
	"database/sql"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jonwilkey/ptt/server/internal/auth"
	"github.com/jonwilkey/ptt/server/internal/db"
)

var testNow = time.Date(2026, 10, 24, 15, 4, 5, 0, time.UTC)

func openMigrated(t *testing.T) *sql.DB {
	t.Helper()
	d, err := db.Open(t.Context(), filepath.Join(t.TempDir(), "pttd.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = d.Close() })
	m, err := db.NewMigrator(d, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Up(t.Context()); err != nil {
		t.Fatal(err)
	}
	return d
}

func exec(t *testing.T, d *sql.DB, q string, args ...any) {
	t.Helper()
	if _, err := d.ExecContext(t.Context(), q, args...); err != nil {
		t.Fatalf("%s: %v", q, err)
	}
}

func count(t *testing.T, d *sql.DB, q string, args ...any) int {
	t.Helper()
	var n int
	if err := d.QueryRowContext(t.Context(), q, args...).Scan(&n); err != nil {
		t.Fatalf("%s: %v", q, err)
	}
	return n
}

// seedFamily adds an admin (two devices) and a non-admin (one device), plus a roster.
func seedFamily(t *testing.T, d *sql.DB) {
	t.Helper()
	exec(t, d, `INSERT INTO users (id, display_name, is_admin, created_at) VALUES ('u1', 'Alex', 1, 1), ('u2', 'Sam', 0, 2)`)
	tok := func() []byte { _, h, _ := auth.NewToken(); return h }
	exec(t, d, `INSERT INTO devices (id, user_id, name, token_hash, created_at, app_version) VALUES ('d1', 'u1', 'Phone', ?, 10, '1.0')`, tok())
	exec(t, d, `INSERT INTO devices (id, user_id, name, token_hash, created_at, last_seen_at) VALUES ('d2', 'u1', 'Tablet', ?, 11, 5000)`, tok())
	exec(t, d, `INSERT INTO devices (id, user_id, name, token_hash, created_at) VALUES ('d3', 'u2', 'Phone', ?, 12)`, tok())
	exec(t, d, `INSERT INTO roster (id, version, signed_roster, signer_device, updated_at) VALUES (1, 3, x'00', 'd1', 20)`)
}

func TestBootstrapFresh(t *testing.T) {
	d := openMigrated(t)
	code, err := Bootstrap(t.Context(), d, "Alex", false, testNow)
	if err != nil {
		t.Fatal(err)
	}
	hash, err := auth.HashEnrollmentCode(code)
	if err != nil {
		t.Fatalf("returned code is malformed: %v", err)
	}

	var name string
	var admin, created, expires int64
	var createdBy sql.NullString
	err = d.QueryRowContext(t.Context(),
		`SELECT display_name, make_admin, created_by_device, created_at, expires_at FROM enrollment_codes WHERE code_hash = ?`, hash).
		Scan(&name, &admin, &createdBy, &created, &expires)
	if err != nil {
		t.Fatal(err)
	}
	if name != "Alex" || admin != 1 || createdBy.Valid {
		t.Errorf("code row = %q admin=%d createdBy=%v", name, admin, createdBy)
	}
	if created != testNow.UnixMilli() || expires != testNow.Add(24*time.Hour).UnixMilli() {
		t.Errorf("created=%d expires=%d", created, expires)
	}

	var action, detail string
	var actor sql.NullString
	if err := d.QueryRowContext(t.Context(), `SELECT action, actor_device, detail FROM audit_log`).Scan(&action, &actor, &detail); err != nil {
		t.Fatal(err)
	}
	if action != "bootstrap" || actor.Valid {
		t.Errorf("audit = %q actor=%v", action, actor)
	}
	if strings.Contains(detail, code) {
		t.Errorf("audit detail leaks the code: %s", detail)
	}
}

func TestBootstrapRefusesWhenAdminExists(t *testing.T) {
	d := openMigrated(t)
	seedFamily(t, d)
	_, err := Bootstrap(t.Context(), d, "Alex", false, testNow)
	if !errors.Is(err, ErrAdminExists) {
		t.Fatalf("err = %v, want ErrAdminExists", err)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes`); n != 0 {
		t.Errorf("%d codes inserted by a refused bootstrap", n)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM audit_log`); n != 0 {
		t.Errorf("%d audit rows written by a refused bootstrap", n)
	}
}

func TestBootstrapRepeatRetiresEarlierCodes(t *testing.T) {
	d := openMigrated(t)
	first, err := Bootstrap(t.Context(), d, "Alex", false, testNow)
	if err != nil {
		t.Fatal(err)
	}
	later := testNow.Add(time.Minute)
	second, err := Bootstrap(t.Context(), d, "Alex", false, later)
	if err != nil {
		t.Fatal(err)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes`); n != 2 {
		t.Errorf("codes = %d, want 2", n)
	}
	h1, _ := auth.HashEnrollmentCode(first)
	h2, _ := auth.HashEnrollmentCode(second)
	if n := count(t, d, `SELECT expires_at FROM enrollment_codes WHERE code_hash = ?`, h1); n != int(later.UnixMilli()) {
		t.Errorf("first code expires_at = %d, want %d", n, later.UnixMilli())
	}
	if n := count(t, d, `SELECT expires_at > ? FROM enrollment_codes WHERE code_hash = ?`, later.UnixMilli(), h2); n != 1 {
		t.Error("second code is not live")
	}
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes WHERE used_at IS NOT NULL`); n != 0 {
		t.Error("a code was marked used")
	}
	if n := count(t, d, `SELECT COUNT(*) FROM audit_log WHERE action = 'bootstrap' AND detail LIKE '%"retired_codes":1%'`); n != 1 {
		t.Error("retired count not audited")
	}
}

func TestBootstrapReset(t *testing.T) {
	d := openMigrated(t)
	seedFamily(t, d)
	future := testNow.Add(time.Hour).UnixMilli()
	exec(t, d, `INSERT INTO enrollment_codes (code_hash, display_name, created_at, expires_at) VALUES (zeroblob(32), 'Old', 1, ?)`, future)
	exec(t, d, `INSERT INTO enrollment_codes (code_hash, display_name, created_at, expires_at, used_at) VALUES (randomblob(32), 'Done', 1, 2, 7)`)
	exec(t, d, `UPDATE devices SET revoked_at = 99 WHERE id = 'd3'`)

	code, err := Bootstrap(t.Context(), d, "Jo", true, testNow)
	if err != nil {
		t.Fatal(err)
	}

	if n := count(t, d, `SELECT COUNT(*) FROM devices WHERE revoked_at IS NULL`); n != 0 {
		t.Errorf("%d devices still active", n)
	}
	// The earlier revocation time is preserved, not overwritten.
	if n := count(t, d, `SELECT revoked_at FROM devices WHERE id = 'd3'`); n != 99 {
		t.Errorf("d3 revoked_at = %d, want 99", n)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM roster`); n != 0 {
		t.Errorf("roster rows = %d", n)
	}
	// Outstanding codes are expired, not marked used.
	if n := count(t, d, `SELECT expires_at FROM enrollment_codes WHERE display_name = 'Old'`); n != int(testNow.UnixMilli()) {
		t.Errorf("old code expires_at = %d", n)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes WHERE display_name = 'Old' AND used_at IS NOT NULL`); n != 0 {
		t.Error("old code was marked used")
	}
	if n := count(t, d, `SELECT used_at FROM enrollment_codes WHERE display_name = 'Done'`); n != 7 {
		t.Errorf("used code used_at = %d, want 7", n)
	}
	hash, _ := auth.HashEnrollmentCode(code)
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes WHERE code_hash = ? AND used_at IS NULL AND display_name = 'Jo'`, hash); n != 1 {
		t.Errorf("new code row missing or already used")
	}
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes WHERE expires_at > ?`, testNow.UnixMilli()); n != 1 {
		t.Errorf("%d unexpired codes, want only the new one", n)
	}

	var detail string
	if err := d.QueryRowContext(t.Context(), `SELECT detail FROM audit_log WHERE action = 'bootstrap_reset'`).Scan(&detail); err != nil {
		t.Fatal(err)
	}
	if detail != `{"revoked_devices":2}` {
		t.Errorf("reset detail = %s", detail)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM audit_log WHERE action = 'bootstrap'`); n != 1 {
		t.Errorf("bootstrap audit rows = %d", n)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%' || ? || '%'`, code); n != 0 {
		t.Error("audit log contains the code")
	}
}

func TestBootstrapName(t *testing.T) {
	d := openMigrated(t)
	for _, name := range []string{"", "  \t"} {
		if _, err := Bootstrap(t.Context(), d, name, false, testNow); err == nil {
			t.Errorf("name %q accepted", name)
		}
	}
	if _, err := Bootstrap(t.Context(), d, "  Alex ", false, testNow); err != nil {
		t.Fatal(err)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM enrollment_codes WHERE display_name = 'Alex'`); n != 1 {
		t.Error("name was not trimmed")
	}
}

func TestListDevices(t *testing.T) {
	d := openMigrated(t)
	got, err := ListDevices(t.Context(), d)
	if err != nil || len(got) != 0 {
		t.Fatalf("empty db: %v, %v", got, err)
	}

	seedFamily(t, d)
	exec(t, d, `UPDATE devices SET revoked_at = 9000 WHERE id = 'd3'`)
	got, err = ListDevices(t.Context(), d)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 {
		t.Fatalf("got %d devices", len(got))
	}
	d1, d2, d3 := got[0], got[1], got[2]
	if d1.ID != "d1" || d1.Person != "Alex" || !d1.Admin || d1.Name != "Phone" || d1.Version != "1.0" || d1.LastSeenAt != nil || d1.RevokedAt != nil {
		t.Errorf("d1 = %+v", d1)
	}
	if d2.ID != "d2" || d2.LastSeenAt == nil || !d2.LastSeenAt.Equal(time.UnixMilli(5000)) {
		t.Errorf("d2 = %+v", d2)
	}
	if d3.ID != "d3" || d3.Admin || d3.RevokedAt == nil || !d3.RevokedAt.Equal(time.UnixMilli(9000)) {
		t.Errorf("d3 = %+v", d3)
	}
}

func TestRevoke(t *testing.T) {
	d := openMigrated(t)
	seedFamily(t, d)

	changed, err := Revoke(t.Context(), d, "d2", testNow)
	if err != nil || !changed {
		t.Fatalf("Revoke = %v, %v", changed, err)
	}
	if n := count(t, d, `SELECT revoked_at FROM devices WHERE id = 'd2'`); n != int(testNow.UnixMilli()) {
		t.Errorf("revoked_at = %d", n)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM devices WHERE revoked_at IS NOT NULL`); n != 1 {
		t.Errorf("revoked devices = %d, want 1", n)
	}

	// Revoking again is a no-op: no error, no second audit row, original time kept.
	changed, err = Revoke(t.Context(), d, "d2", testNow.Add(time.Hour))
	if err != nil || changed {
		t.Fatalf("second Revoke = %v, %v", changed, err)
	}
	if n := count(t, d, `SELECT COUNT(*) FROM audit_log WHERE action = 'device_revoked' AND actor_device IS NULL AND detail = '{"device_id":"d2","expired_codes":0}'`); n != 1 {
		t.Errorf("device_revoked audit rows = %d, want 1", n)
	}
	if n := count(t, d, `SELECT revoked_at FROM devices WHERE id = 'd2'`); n != int(testNow.UnixMilli()) {
		t.Errorf("revoked_at changed to %d", n)
	}

	_, err = Revoke(t.Context(), d, "nope", testNow)
	if !errors.Is(err, ErrUnknownDevice) {
		t.Errorf("unknown device err = %v", err)
	}
}
