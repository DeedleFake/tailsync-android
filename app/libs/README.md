# Vendored tailsync AAR

`tailsync.aar` is the [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)
binding of this repo’s `./mobile` package. That package wraps the public engine
API `deedles.dev/tailsync/daemon` (Go module `deedles.dev/tailsync`). The AAR is
large (~30MB) because it embeds tsnet/Tailscale.

`tailsync.aar.rev` records the engine revision used for the last rebuild
(VCS hash when known, otherwise the module version from `go.mod`).

Rebuild whenever the engine pin or the local `mobile` package changes.

## Layout

| Path | Role |
|------|------|
| `mobile/` | Gomobile-bindable API (`Config`, `Node`, Android net helpers) |
| `go.mod` | Module `tailsync-android`; pins `deedles.dev/tailsync` by version |
| `app/libs/tailsync.aar` | Prebuilt bind output consumed by Gradle |

## Rebuild

From the Android repo root:

```bash
./scripts/update-aar.sh
```

Uses the engine version already required in `go.mod`. To bump the engine:

```bash
go get deedles.dev/tailsync@latest   # or branch / tag / commit
go mod tidy
./scripts/update-aar.sh
```

Requirements: Go, Android SDK + NDK. `ANDROID_HOME` is read from
`local.properties` when unset; NDK defaults to the newest `$ANDROID_HOME/ndk/*`.

Uses `go tool gomobile` / `go tool gobind` from the `tool` directives in
`go.mod`. The script does not modify `go.mod`.

Manual bind (same as the script):

```bash
export ANDROID_HOME=...
export ANDROID_NDK_HOME=...
# gobind must be on PATH as a host binary (go tool -n gobind)
go tool gomobile bind \
  -target=android/arm64,android/amd64 -androidapi 24 \
  -o app/libs/tailsync.aar \
  ./mobile
```

The Android app depends only on `tailsync.aar` (not `*-sources.jar`).

## Tests

```bash
go test ./mobile/
```

## Policy

- Do not commit secrets (auth keys) into this tree.
- Prefer LFS / CI artifact if git history size becomes a problem; the binary is intentional to vendor for offline builds.
