#!/usr/bin/env bash
# Rebuild app/libs/tailsync.aar from this repo's mobile package (gomobile).
#
# Uses whatever deedles.dev/tailsync version is already pinned in go.mod.
# To change the engine version, run go get / go mod tidy yourself first.
#
# Usage:
#   scripts/update-aar.sh
#
# Environment (optional):
#   ANDROID_HOME      Android SDK root (falls back to local.properties sdk.dir)
#   ANDROID_NDK_HOME  NDK root (falls back to newest $ANDROID_HOME/ndk/*)
#   ANDROIDAPI        min API for gomobile (default: 24)
#   GOMOBILE_TARGETS  gomobile -target value (default: android/arm64,android/amd64)

set -euo pipefail

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

case "${1:-}" in
  -h | --help) usage 0 ;;
  "") ;;
  *)
    printf 'error: unexpected argument %q (engine version comes from go.mod)\n' "$1" >&2
    usage 1
    ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/app/libs"
OUT_AAR="$OUT_DIR/tailsync.aar"
ANDROIDAPI="${ANDROIDAPI:-24}"
GOMOBILE_TARGETS="${GOMOBILE_TARGETS:-android/arm64,android/amd64}"
ENGINE_MOD="deedles.dev/tailsync"

log() { printf '+ %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- Android SDK / NDK -------------------------------------------------------

if [[ -z "${ANDROID_HOME:-}" && -f "$ROOT/local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -n1 | sed 's|\\|/|g')"
  if [[ -n "$sdk_dir" ]]; then
    export ANDROID_HOME="$sdk_dir"
  fi
fi
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || die "ANDROID_HOME is not set or not a directory"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -d "$ANDROID_HOME/ndk" ]]; then
    newest="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1 || true)"
    [[ -n "$newest" ]] || die "no NDK installs under $ANDROID_HOME/ndk"
    export ANDROID_NDK_HOME="$newest"
  else
    die "ANDROID_NDK_HOME is not set and $ANDROID_HOME/ndk is missing"
  fi
fi
[[ -d "$ANDROID_NDK_HOME" ]] || die "ANDROID_NDK_HOME is not a directory: $ANDROID_NDK_HOME"

# --- Tools -------------------------------------------------------------------

command -v go >/dev/null || die "go not found in PATH"

# gomobile invokes "gobind" with GOOS=android in the environment. A plain
# `go run …/gobind@…` then tries to compile gobind itself for Android and fails.
# Put the host tool binary from go.mod on PATH so the name "gobind" resolves.
TOOL_BIN="$(mktemp -d "${TMPDIR:-/tmp}/tailsync-aar-tools.XXXXXX")"
cleanup() {
  if [[ -n "${TOOL_BIN:-}" && -d "${TOOL_BIN:-}" ]]; then
    rm -rf "$TOOL_BIN"
  fi
}
trap cleanup EXIT

cd "$ROOT"

GOBIND_BIN="$(go tool -n gobind 2>/dev/null || true)"
[[ -n "$GOBIND_BIN" && -x "$GOBIND_BIN" ]] \
  || die "gobind tool not found; add with: go get -tool golang.org/x/mobile/cmd/gobind"
ln -s "$GOBIND_BIN" "$TOOL_BIN/gobind"
export PATH="$TOOL_BIN:$PATH"

# --- Engine identity for logs / .rev ----------------------------------------

ENGINE_INFO="$(go list -m -f '{{.Path}} {{.Version}}' "$ENGINE_MOD")"
ENGINE_REV="$(go list -m -f '{{.Version}}' "$ENGINE_MOD")"
if origin_hash="$(go list -m -f '{{.Origin.Hash}}' "$ENGINE_MOD" 2>/dev/null)" && [[ -n "$origin_hash" && "$origin_hash" != "<no value>" ]]; then
  ENGINE_REV="$origin_hash"
fi
log "engine: $ENGINE_INFO"
log "engine rev: $ENGINE_REV"
log "ANDROID_HOME=$ANDROID_HOME"
log "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

# --- Bind --------------------------------------------------------------------

mkdir -p "$OUT_DIR"
log "go tool gomobile bind -target=$GOMOBILE_TARGETS -androidapi $ANDROIDAPI -o $OUT_AAR ./mobile"
go tool gomobile bind \
  -target="$GOMOBILE_TARGETS" \
  -androidapi "$ANDROIDAPI" \
  -o "$OUT_AAR" \
  ./mobile

AAR_SIZE="$(wc -c <"$OUT_AAR" | tr -d ' ')"
log "wrote $OUT_AAR (${AAR_SIZE} bytes)"
if [[ -f "$OUT_DIR/tailsync-sources.jar" ]]; then
  log "wrote $OUT_DIR/tailsync-sources.jar"
fi
printf '%s\n' "$ENGINE_REV" >"$OUT_DIR/tailsync.aar.rev"
log "recorded engine rev in $OUT_DIR/tailsync.aar.rev"
log "done"
