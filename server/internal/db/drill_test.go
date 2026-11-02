//go:build drill

package db

import "testing"

func TestDrillBuildAddsDrillMigration(t *testing.T) {
	d, _ := openTemp(t)
	m := mustMigrator(t, d)
	if got := m.LatestVersion(); got != 99999 {
		t.Fatalf("LatestVersion() = %d, want 99999", got)
	}
	if err := m.Up(t.Context()); err != nil {
		t.Fatal(err)
	}
	if !hasTable(t, d, "drill_marker") {
		t.Fatal("drill_marker table missing after migrating")
	}
}
