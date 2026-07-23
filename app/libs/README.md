# Vendored tailsync AAR

`tailsync.aar` is the [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) binding of
`deedles.dev/tailsync/mobile` (sync engine). It is large (~29MB) because it embeds tsnet/Tailscale.

## Rebuild

Requires Android SDK + NDK and gomobile:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

# From a module that requires deedles.dev/tailsync (or a temp module with replace)
# and has: go get -tool golang.org/x/mobile/cmd/gobind
export ANDROID_HOME=...   # SDK root
export ANDROID_NDK_HOME=... # NDK path

gomobile bind -target=android/arm64,android/amd64 -androidapi 24 \
  -o tailsync.aar deedles.dev/tailsync/mobile

# Copy into this directory (sources jar is optional for IDE attach only):
# cp tailsync.aar /path/to/Tailsync/app/libs/
```

The Android app depends only on `tailsync.aar` (not `*-sources.jar`).

## Policy

- Do not commit secrets (auth keys) into this tree.
- Prefer LFS / CI artifact if git history size becomes a problem; the binary is intentional to vendor for offline builds.
