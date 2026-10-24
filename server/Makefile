# pttd developer targets. Tools are taken from PATH; override to pin, e.g.
#   make vuln GOVULNCHECK="go run golang.org/x/vuln/cmd/govulncheck@v1.9.0"

GO            ?= go
GOLANGCI_LINT ?= golangci-lint
SQLC          ?= sqlc
GOVULNCHECK   ?= govulncheck

# Release builds pass VERSION explicitly (the image build does); this is for local builds.
VERSION ?= $(shell git describe --tags --match 'server-v*' --always --dirty 2>/dev/null || echo dev)
LDFLAGS := -s -w -X main.version=$(VERSION)

.PHONY: all build test lint generate sqlc-check vuln clean

all: lint test build

## build: static linux/amd64 binary at bin/pttd
build:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -trimpath -ldflags "$(LDFLAGS)" -o bin/pttd ./cmd/pttd

## test: unit and integration tests with the race detector
test:
	$(GO) test -race ./...

## lint: golangci-lint (linters and gofmt/goimports)
lint:
	$(GOLANGCI_LINT) run ./...

## generate: regenerate sqlc code from migrations/ and internal/db/queries/
generate:
	$(SQLC) generate

## sqlc-check: fail if the committed sqlc output is stale
sqlc-check:
	$(SQLC) diff

## vuln: govulncheck against the module and the Go toolchain
vuln:
	$(GOVULNCHECK) ./...

clean:
	rm -rf bin
