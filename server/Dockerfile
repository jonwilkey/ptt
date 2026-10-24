# pttd container image: a static binary on distroless, run as nonroot.
#
# Built in CI only (`docker buildx build server/`). Base images are pinned by index digest;
# Renovate keeps the tags and digests current.
#
# Runtime contract (deploy/compose.yaml): read-only root filesystem, all capabilities dropped,
# no-new-privileges, and a volume at /data, which is the only writable path. SQLite keeps its
# transient files in memory (temp_store=MEMORY); SQLITE_TMPDIR points any that remain at /data.

FROM golang:1.27.3-trixie@sha256:fbb7ae40b62ff8afe31662df570d39b4f5052ac32b90dae8e60d7657a20a74f4 AS build

# Build with exactly the toolchain in this image and the committed go.sum.
ENV GOTOOLCHAIN=local \
    GOFLAGS=-mod=readonly \
    CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download && go mod verify

COPY . .
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/pttd ./cmd/pttd

# An empty /data owned by nonroot: a fresh named volume mounted there inherits the ownership.
RUN mkdir -p /out/data

FROM gcr.io/distroless/static-debian13:nonroot@sha256:18f4cc26c9ffa7f20a359298be7deadac5c607c8e03a02b8119f1a5fc2f82c99

COPY --from=build /out/pttd /pttd
COPY --from=build --chown=65532:65532 /out/data /data

ENV PTTD_LISTEN_ADDR=:8080 \
    PTTD_INTERNAL_ADDR=:9090 \
    PTTD_DB_PATH=/data/pttd.db \
    PTTD_SNAPSHOT_DIR=/data/snapshots \
    SQLITE_TMPDIR=/data

USER 65532:65532
# 8080: public API (Caddy proxies to it). 9090: /metrics and /internal/status; publish it only
# on the host's loopback or tailnet address, never through Caddy.
EXPOSE 8080 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --start-interval=2s --retries=3 \
    CMD ["/pttd", "healthcheck"]

ENTRYPOINT ["/pttd"]
CMD ["serve"]
