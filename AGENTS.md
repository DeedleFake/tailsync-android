# AGENTS.md

Instructions for AI coding agents working in this repository.

## Project overview

**Tailsync** (this repo) is the **Android app wrapper** for [DeedleFake/tailsync](https://github.com/DeedleFake/tailsync), a Tailscale-based directory synchronization daemon.

The sync engine lives in the Go module (`deedles.dev/tailsync`). This project embeds it on Android via [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) (`deedles.dev/tailsync/mobile` → AAR) and owns:

- App UI and settings
- Lifecycle (typically a **foreground service**)
- Paths, auth key storage, and permissions
- Wiring mobile events/status into Android APIs

Do **not** reimplement the sync protocol or index logic here. Prefer changes in the Go repo when the engine itself needs work; keep this repo focused on Android integration and UX.

Prefer discovering structure from the tree and Gradle files over assuming a layout.

## Technology stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin (and Android resources / manifests as needed) |
| Build | Gradle Kotlin DSL, version catalog in `gradle/libs.versions.toml` |
| Module | Single app module `:app` (`applicationId` / namespace `dev.deedles.tailsync`) |
| Sync engine | `deedles.dev/tailsync/mobile` via gomobile-produced AAR |
| Network (mobile) | Always **tsnet** (embedded Tailscale node); host/plain are not used on Android |

Do not pin SDK, AGP, library, or dependency versions in this file (they go stale). Prefer “as specified in `gradle/libs.versions.toml` / module build files” or unversioned names.

## Relationship to the Go project

| Concern | Where it lives |
|---------|----------------|
| Protocol, index, scan, delta transfer | Go module `deedles.dev/tailsync` |
| Mobile API (`Config`, `Node`, `EventListener`, …) | Go package `deedles.dev/tailsync/mobile` |
| Android UI, service, storage, keys | This repository |

Upstream docs (README Android / gomobile section) describe binding and the mobile API. Rebuild the AAR from a tailsync checkout when the engine API changes:

```bash
# In a tailsync (Go) checkout — not this repo
gomobile bind -target=android -o tailsync.aar deedles.dev/tailsync/mobile
```

How the AAR is vendored or published for this app may evolve; follow whatever the tree currently does (`libs/`, local Maven, etc.) rather than inventing a new path without need.

## Development commands

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
./gradlew connectedAndroidTest   # requires device/emulator
./gradlew lint
```

Use the project wrapper (`./gradlew`), not a system Gradle install. Exact tasks may grow as the app matures.

## Android integration notes

- **Sync dir (Config.Dir)** — user-chosen **absolute, writable** path on shared storage. Requires **`MANAGE_EXTERNAL_STORAGE`** (all-files access) so SAF tree picks can resolve to real filesystem paths. **Do not** use app-private storage (`filesDir/sync`) as the product sync root or as a silent fallback when pick/permission fails. Gate start and folder pick on `Environment.isExternalStorageManager()`.
- **State dir (Config.StateDir)** — remains **app-private** (index + tsnet state under `context.filesDir`). Not user-facing as “where my files live.”
- **Lifecycle** — call `Node.Start()` / `Stop()` from a service (or other long-lived component), **off the main thread** (`Start` can block long enough to ANR). Call `Stop` when the service is destroyed.
- **Events** — `EventListener` runs on a Go background thread; keep handlers fast and post to the main thread only for UI. On `{"type":"auth","url":"..."}` post opening a Custom Tab / browser; do not block the callback on login completing (`Start` stays blocked until login finishes).
- **Net mode** — always `"tsnet"`. First registration uses **browser / Custom Tab login** when AuthKey is empty (auth event + `StatusJSON` `needs_login` / `auth_url`). Optional **auth key** is a secondary path (EncryptedSharedPreferences). Existing tsnet state under `StateDir` reconnects silently. Do not expose host/plain in the UI.
- **Android networking (tsnet)** — before `Node.Start`, publish host interfaces with `Mobile.setNetworkInterfacesJSON` plus default route/gateway from `ConnectivityManager` / `java.net.NetworkInterface` (`AndroidNetworkBridge`). Go’s `net.Interfaces()` fails with netlink permission errors on API 30+. On connectivity changes, update again and call `notifyNetworkChange`.
- **Secrets** — never log, ship, or commit Tailscale auth keys, tokens, or machine-specific secrets. Prefer secure storage (e.g. EncryptedSharedPreferences / Keystore-backed storage) for keys the user provides. Empty AuthKey is valid for browser login.
- **Status** — use `StatusJSON()` / events for UI; auth keys must never appear in status or logs.

## Code style and conventions

- Prefer **small, focused changes**. No drive-by refactors or unrelated reformatting.
- Match existing project style (Kotlin official code style is set in `gradle.properties`).
- Put new app code under `app/src/main/java/dev/deedles/tailsync/` (or `kotlin/` if the tree moves there).
- Use the version catalog for dependencies; do not hardcode versions only in one module without a clear reason.
- Avoid growing hand-maintained source files past ~1000 lines without decomposing them.
- Do not introduce public Internet or bind-to-all networking for sync; tailsync is tailnet-oriented.

## Agent guidelines

1. **Git is read-only under all circumstances.** Never run write/mutating git commands. That includes (non-exhaustive): `commit`, `add`, `rm`, `mv`, `restore --staged`, `checkout`, `switch`, `branch` (create/delete), `merge`, `rebase`, `cherry-pick`, `stash`, `reset`, `clean`, `tag`, `push`, `pull` (when it updates refs), `am`, `revert`, `commit --amend`, or anything that modifies the index, working tree via git, or remote state. Read-only commands (`status`, `diff`, `log`, `show`, `blame`, `ls-files`, etc.) are fine. Leave all commits and branch management to the user.
2. **Do not pin versions in this file** — refer to Gradle catalog / build files or unversioned names so these instructions stay valid as versions change.
3. **Verify** with `./gradlew` tasks appropriate to the change (`assembleDebug`, `test`, `lint`, etc.) before considering work done.
4. **Secrets** — do not commit tokens, API keys, Tailscale auth keys, `local.properties`, or machine-specific paths.
5. **Engine boundary** — do not fork or reimplement the Go sync engine in Kotlin; integrate the mobile API and improve the Android shell around it.
6. **Scope** — this is the Android wrapper only; do not modify an adjacent Go tailsync checkout unless the user explicitly asks.

## PR checklist

- [ ] `./gradlew` build/tests relevant to the change pass
- [ ] No secrets (auth keys, tokens) in the diff
- [ ] Sync engine changes deferred to the Go repo when appropriate
- [ ] No agent-created git commits or other git writes
