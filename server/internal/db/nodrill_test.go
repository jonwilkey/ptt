//go:build !drill

package db

import (
	"io/fs"
	"testing"
)

func TestNormalBuildHasNoDrillMigration(t *testing.T) {
	d, _ := openTemp(t)
	if got := mustMigrator(t, d).LatestVersion(); got >= 99999 {
		t.Fatalf("LatestVersion() = %d: the drill migration is in a normal build", got)
	}
	if _, err := fs.Stat(migrationsFS, "99999_drill.sql"); err == nil {
		t.Fatal("99999_drill.sql is embedded in a normal build")
	}
}
