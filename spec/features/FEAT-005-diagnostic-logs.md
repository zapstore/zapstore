# FEAT-005 — Local Diagnostic Logs

## Goal

Capture rich, structured diagnostic logs and uncaught errors to local storage so users can export and share them through any channel of their choosing. Nothing leaves the device automatically.

## Non-Goals

- No remote upload, telemetry, or analytics of any kind.
- No opt-in "send report" path. Sharing is always user-initiated via the OS share sheet.
- No structured query language; a simple text filter is enough.
- No log streaming over ADB or web sockets (developers already have `flutter logs`).
- No replacement for in-app user-facing error UI (toasts, error overlays). Logs are diagnostic, not UX.

## Core Principles

- **Local-only.** Logs never leave the device unless the user explicitly exports and shares them.
- **Always on.** Default level in release builds is `debug`. There is no production "off" switch — logs are how we diagnose field issues.
- **Crash-safe.** Logs written before a crash MUST survive the crash and be readable on next launch.
- **Non-blocking.** Logging MUST NOT block the UI thread. Writes are batched and flushed on a background queue.
- **Secret-safe.** Sensitive values (signing keys, NWC URIs, encrypted-DM plaintext) MUST be redacted at write time, not at export time.

## Capture Surface

All four error sinks below MUST be wired in `main()`, set up before `runApp` is called inside the guarded zone:

| Sink | Source captured |
|------|-----------------|
| `FlutterError.onError` | Sync framework errors (build, layout, paint) |
| `PlatformDispatcher.instance.onError` | Engine and uncaught Dart errors not in a guarded zone |
| `runZonedGuarded` | Async errors originating inside the app's root zone |
| `Isolate.current.addErrorListener` | Uncaught errors from the main isolate that bypass zones |

Background isolates we own — at minimum the `workmanager` `callbackDispatcher` in `lib/services/background_update_service.dart` — MUST install the same four handlers and write to the shared log file.

The framework console dump (`FlutterError.presentError` / `dumpErrorToConsole`) MUST still run in debug builds so devs see errors in `flutter run` output.

## Storage Model

- Path: `getApplicationSupportDirectory()/logs/zapstore.log` with rotation `zapstore.log.1` … `zapstore.log.5`.
- Format: NDJSON, one record per line. Each record contains:
  - `ts` — ISO-8601 UTC timestamp
  - `level` — `trace` | `debug` | `info` | `warn` | `error` | `fatal`
  - `tag` — short logical area (e.g. `package_manager`, `relay`, `signer`)
  - `msg` — human-readable message
  - `fields` — optional flat map of structured context
  - `err` — error string if any
  - `stack` — stack trace string if any
  - `isolate` — `main` | `workmanager` | other named isolate
- Rotation: when `zapstore.log` exceeds 10 MB, rotate. Keep at most 5 historical files. Total disk budget ≤ 60 MB.
- All isolates write to the **same** active log file using append-mode writes plus an OS advisory file lock (`flock`/`LOCK_EX`) per batch.
- An in-memory **ring buffer of the last 500 entries** is maintained on the main isolate for fast in-app viewing without disk reads.

## User-Visible Behavior

A new "Diagnostics" section in the profile/settings screen exposes:

- **View recent logs** — full-screen scrollable viewer backed by the ring buffer plus the tail of the active log file. Supports:
  - Level filter (chips: debug / info / warn / error)
  - Free-text filter
  - Copy-to-clipboard for any single entry
- **Export logs** — bundles all rotated log files into `zapstore-logs-<UTC-timestamp>.zip` in the cache directory and opens the OS share sheet via `share_plus`. The user picks the destination (Signal, email, Files, etc.).
- **Clear logs** — confirms with a dialog, then deletes all log files and clears the ring buffer.
- **Log level** — selector for `debug` (default) / `info` / `warn`. Persisted in `SettingsService`.

States required:

- Empty (no logs yet) — viewer shows "No logs yet"; Export shows toast "No logs to export" and does not open the share sheet.
- Large export — show file size before opening the share sheet so the user can cancel.
- Disk error during export — toast with the error; do not open share sheet.

## Redaction

At write time, the logger MUST redact:

- Any string matching `nsec1[ac-hj-np-z02-9]+`
- Any string matching `ncryptsec1[ac-hj-np-z02-9]+`
- NWC connection URIs (`nostr+walletconnect://...`)
- Plaintext content of NIP-04 (kind 4), NIP-44 / NIP-17 (kind 13, 1059) events
- Values stored in `flutter_secure_storage`

Redaction replaces the value with `[REDACTED:<kind>]`. Public Nostr event JSON (kinds outside the list above) is **not** redacted — debuggability of public data is preferred.

A unit test MUST assert that none of the above patterns can appear in the log output even if passed as `msg`, `fields`, `err`, or `stack`.

## Riverpod Integration

A `ProviderObserver` MUST be installed on the root `ProviderContainer` to log:

- Provider build failures (`providerDidFail`) at `error` level
- `StorageError` states emitted from `query<T>` providers at `warn` level

This gives automatic coverage of the standard Riverpod async-error path described in `ARCHITECTURE.md`.

## Edge Cases

- **Disk full** — drop the oldest rotated file, then drop new entries; emit one `warn` to stderr per minute max so we don't loop.
- **Corrupted log file** — on read, tolerate malformed lines (skip them); on write, never fail the app.
- **Logs directory missing or read-only** — disable disk logging for the session, keep ring buffer working, surface a single warning entry.
- **Concurrent writes from multiple isolates** — serialised via file lock; entries from different isolates may interleave at line granularity but never at byte granularity.
- **Crash mid-write** — file is append-only with line-flushed writes; the worst case is a truncated final line, which the reader skips.
- **Clock skew / device time wrong** — record `ts` as device clock; do not attempt correction.
- **Export with no logs** — surface "No logs to export"; do not produce an empty zip.
- **Export while writing** — copy/snapshot files into the cache dir before zipping so the share is consistent.
- **Very large `fields` value** — truncate any single string field to 4 KB; replace with `…(truncated)`.

## Acceptance Criteria

- [ ] All four error sinks (`FlutterError.onError`, `PlatformDispatcher.onError`, `runZonedGuarded`, `Isolate.addErrorListener`) are wired in `main()` and route to `LogService`.
- [ ] The `workmanager` `callbackDispatcher` installs the same handlers and writes to the shared log file.
- [ ] Triggering each of: a sync UI exception, an async future error, an uncaught isolate error, and a workmanager task error each produces a `fatal`/`error` entry visible after app restart.
- [ ] Logs persist across app restart and rotate at 10 MB; at most 5 rotations are kept.
- [ ] Logging never blocks the UI thread (verified by a stress test of 1000 entries/sec for 5 s with no dropped frames).
- [ ] No log line ever contains an `nsec1…`, `ncryptsec1…`, `nostr+walletconnect://…`, or kind-4/13/1059 plaintext (verified by unit test).
- [ ] In-app viewer shows ring-buffer + on-disk tail, supports level and text filter, supports copy.
- [ ] Export produces a `zapstore-logs-<ts>.zip` and opens the OS share sheet; nothing is sent automatically.
- [ ] Clear logs removes all files and empties the ring buffer.
- [ ] App boots cleanly when `logs/` is missing, read-only, or contains a corrupted file.
- [ ] Existing 7 `debugPrint` sites are converted to `LogService` calls in the same change.
- [ ] A `ProviderObserver` logs provider failures and `StorageError` states.

## Notes

- Log level for release builds is `debug` by intent — see Goal/Core Principles. There is no network cost and the file is size-capped.
- A future feature may add a one-tap "share with developer" button, but it MUST go through the same user-initiated export flow. No background upload will ever be added under this feature.
