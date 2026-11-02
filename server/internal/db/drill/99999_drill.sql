-- The rollback drill's migration (docs/operations.md, "Proving it: the rollback drill"). Only the
-- drill build (go build -tags drill) embeds it; it is never in server/migrations/.

-- +goose Up
CREATE TABLE drill_marker (id INTEGER PRIMARY KEY) STRICT;

-- +goose Down
DROP TABLE drill_marker;
