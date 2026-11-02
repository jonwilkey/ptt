//go:build drill

package db

import (
	"embed"
	"io/fs"
	"testing/fstest"
)

//go:embed drill/99999_drill.sql
var drillFS embed.FS

func init() {
	merged := fstest.MapFS{}
	for _, fsys := range []fs.FS{migrationsFS, drillFS} {
		err := fs.WalkDir(fsys, ".", func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			b, err := fs.ReadFile(fsys, path)
			if err != nil {
				return err
			}
			merged[d.Name()] = &fstest.MapFile{Data: b}
			return nil
		})
		if err != nil {
			panic(err)
		}
	}
	migrationsFS = merged
}
