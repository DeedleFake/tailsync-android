# Vendored tailsync AAR

`tailsync.aar` is the [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) binding of
`deedles.dev/tailsync/mobile` (sync engine). It is large (~29MB) because it embeds tsnet/Tailscale.

Rebuild whenever the Go engine or mobile lifecycle changes, even if Config/Node
field names are unchanged (behavior and bugfixes ship inside the AAR).

## Rebuild

Requires Android SDK + NDK and gomobile:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

# From the tailsync Go module checkout (simplest):
export ANDROID_HOME=...    # SDK root
export ANDROID_NDK_HOME=... # NDK path
cd /path/to/tailsync
gomobile bind -target=android/arm64,android/amd64 -androidapi 24 \
  -o /path/to/Tailsync/app/libs/tailsync.aar \
  ./mobile
```

The Android app depends only on `tailsync.aar` (not `*-sources.jar`).

## Policy

- Do not commit secrets (auth keys) into this tree.
- Prefer LFS / CI artifact if git history size becomes a problem; the binary is intentional to vendor for offline builds.
