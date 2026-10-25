package db

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/jonwilkey/ptt/server/internal/db/sqlcdb"
)

// InTx runs f in a transaction, committing only if f succeeds. Open's pool starts transactions
// with BEGIN IMMEDIATE, so concurrent writers queue on the busy timeout instead of failing
// midway.
func InTx(ctx context.Context, d *sql.DB, f func(q *sqlcdb.Queries) error) error {
	tx, err := d.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	if err := f(sqlcdb.New(tx)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}
	return nil
}

// Audit writes an audit_log row through q (normally inside InTx). An empty actor means no
// acting device (an SSH admin action). detail is JSON-encoded and must never hold tokens,
// codes or other secrets.
func Audit(ctx context.Context, q *sqlcdb.Queries, now time.Time, actor, action string, detail any) error {
	b, err := json.Marshal(detail)
	if err != nil {
		return fmt.Errorf("encode audit detail: %w", err)
	}
	err = q.InsertAuditLog(ctx, sqlcdb.InsertAuditLogParams{
		At:          now.UnixMilli(),
		ActorDevice: sql.NullString{String: actor, Valid: actor != ""},
		Action:      action,
		Detail:      sql.NullString{String: string(b), Valid: true},
	})
	if err != nil {
		return fmt.Errorf("write audit log: %w", err)
	}
	return nil
}
