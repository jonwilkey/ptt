# server/

The Go service `pttd`: HTTP/WebSocket API, SQLite migrations, and `sqlc` queries, shipped as a
single binary in a Docker container.

Milestone 1 adds the skeleton with `/healthz`; milestone 2 adds enrollment, paging, and FCM.
Released by release-please with tags `server-vX.Y.Z`.
