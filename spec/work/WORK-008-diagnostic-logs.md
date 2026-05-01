# WORK-008 — Local Diagnostic Logs

**Feature:** FEAT-005-diagnostic-logs.md
**Status:** Complete

## Tasks

- [x] 1. Add `LogService` (singleton, isolate-safe)
  - Files: `lib/services/log_service.dart`
  - NDJSON append writer with batched async flush, advisory file lock per batch (`FileLock.blockingExclusive`)
  - Levels: `trace`/`debug`/`info`/`warn`/`error`/`fatal`
  - Ring buffer of last 500 entries (`kLogRingBufferSize`)
  - Path: `getApplicationSupportDirectory()/logs/zapstore.log` + `.1`–`.5`
  - Rotation at 10 MB; max 5 historical files
  - `init({required String isolateName})` and `LogService.forTesting(...)` for use from any isolate or test
  - `flushSync()` for crash paths
  - Smoke tested in `test/services/log_service_test.dart` (14 tests passing)

- [x] 2. Implement redactor
  - Files: `lib/services/log_service.dart` (`LogRedactor`)
  - Patterns: `nsec1…`, `ncryptsec1…`, `nostr+walletconnect://…` — applied at write time to `msg`, `fields` (recursive), `err`, `stack`
  - `LogRedactor.redactPlaintext(kind: ...)` helper for call sites with kind-4/13/1059 plaintext
  - Truncates any single string to `kLogMaxFieldBytes` (4 KB) with `…(truncated)` marker
  - Tested: nsec / ncryptsec / NWC / nested fields / oversized strings

- [x] 3. Wire all four error sinks in `main()`
  - Files: `lib/main.dart`
  - `_installErrorHandlers()` sets all four BEFORE `runApp`
  - `FlutterError.onError` (calls `presentError` for console)
  - `PlatformDispatcher.instance.onError`
  - `runZonedGuarded` wraps `runApp`
  - `Isolate.current.addErrorListener` via `RawReceivePort` (held in top-level `_isolateErrorPort` so it isn't GC'd)
  - All sinks route through `_logUncaught` which calls `fatal()` then `flushSync()`

- [x] 4. Wire handlers in workmanager `callbackDispatcher`
  - Files: `lib/services/background_update_service.dart`
  - `LogService.init(isolateName: 'workmanager')` at start of dispatcher
  - Same four handlers, scoped to the isolate; entries tagged `isolate=workmanager`
  - `flushSync()` after each `fatal` and `flush()` in task `finally`

- [x] 5. Riverpod `ProviderObserver`
  - Files: `lib/services/log_service.dart` (`LoggingProviderObserver`), `lib/main.dart`
  - `providerDidFail` → `error`
  - `didUpdateProvider` with new value `is StorageError` → `warn`
  - Attached to the root `ProviderContainer` via `observers: [LoggingProviderObserver()]`

- [x] 6. Convert existing `debugPrint` sites
  - Files: `lib/main.dart`, `lib/services/package_manager/android_package_manager.dart`, `lib/services/updates_service.dart`, `lib/services/package_manager/installed_packages_snapshot.dart`, `lib/services/package_manager/package_manager.dart`, `lib/services/deep_link_resolver.dart`, `lib/services/package_manager/device_capabilities.dart`
  - All 23 `debugPrint` sites replaced with `LogService.I.<level>(...)` with structured `fields`
  - Removed now-unused `flutter/foundation` imports

- [x] 7. Audit silent `catch (_) {}` blocks flagged by INVARIANTS
  - `_maybeCopySeedDatabase` and `_attemptAutoSignIn` now log warn/debug respectively
  - Other silent catches are intentional cleanup paths (cancelling already-cancelled tasks, deleting temp files, the LogService swallowing its own write errors) — left as-is

- [x] 8. Settings: log level setting
  - `LocalSettings.logLevel: LogLevel` added (default `debug`); JSON-persisted only if non-default
  - Applied in `appInitializationProvider` after settings load
  - Diagnostics screen has a level dropdown that updates settings + applies live

- [x] 9. Diagnostics screen
  - Files: `lib/screens/diagnostics_screen.dart`, `lib/router.dart` (`/profile/diagnostics`), `lib/screens/profile_screen.dart` (Data Management entry)
  - Viewer merges ring buffer + disk tail (1000 lines), filter chips (All/Debug+/Info+/Warn+/Error+), free-text filter, copy-to-clipboard per entry
  - Export: snapshots all rotation files into cache dir, zips via `archive` package, shares via `share_plus` with `XFile`
  - Confirm-then-clear with explicit dialog; empty state explicit
  - `HookConsumerWidget` per INVARIANTS

- [x] 10. Tests
  - `test/services/log_service_test.dart` — 17 tests, all passing
    - Basics: ring buffer, level filter, bounded ring, readTail, clear
    - Redaction: nsec / ncryptsec / NWC across `msg`, nested `fields`, `err`, `stack`; >4 KB truncation
    - Rotation: triggers at threshold; rotation cap enforced
    - Resilience: corrupted line skipped; flushSync writes pending entries
    - Crash sinks (contract): all four sources tagged correctly and durable after `flushSync`
    - Stress: 1000 entries non-blocking; 1000 lines reach disk
    - Concurrency: 50 overlapping `flush()` callers — every line on disk fully formed (caught and fixed a real race in `flush()` / `_runFlush` coordination)
    - Codec: `LogEntry` JSON round-trip; malformed input rejected

- [x] 11. Self-review against INVARIANTS.md
  - UI never blocks on I/O — `log()` is synchronous to caller, all writes async on microtask
  - No polling / artificial delays — flush is event-driven; `_lastDiskFullWarn` only rate-limits stderr noise
  - Lifecycle: `_isolateErrorPort` and `_workmanagerErrorPort` held at top-level so listeners aren't GC'd; `flush()` called on `AppLifecycleState.paused`, `flushSync()` on `detached`
  - Secrets: `LogRedactor` strips nsec / ncryptsec / NWC URIs at write time, recursively across all field shapes; tested
  - No silent failures: ad-hoc `catch (_) {}` audited; logger itself swallows on purpose (logging cannot crash the app)
  - Hooks: `DiagnosticsScreen` is `HookConsumerWidget`, no `StatefulWidget`
  - Reproducible builds: no build-config changes; `archive` promoted from transitive to direct, no nondeterministic plugins added

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| All four error sinks (contract) | `fatal` entry tagged with source, durable after `flushSync` | [x] unit |
| 1000 entries non-blocking | `log()` returns in <500 ms total; all 1000 reach disk | [x] unit |
| 50 overlapping `flush()` callers | Every line on disk fully formed JSON | [x] unit |
| Log file reaches 10 MB | Rotates to `.1` | [x] unit |
| 6th rotation | Oldest file deleted, cap enforced | [x] unit |
| `nsec1…` / NWC URI / `ncryptsec1…` in `msg` / `fields` (nested) / `err` / `stack` | Replaced with `[REDACTED:*]` | [x] unit |
| `>4 KB` field string | Truncated with `…(truncated)` | [x] unit |
| Corrupted log line | Reader skips it; subsequent reads keep working | [x] unit |
| `LogEntry` JSON round-trip | Lossless | [x] unit |
| Workmanager task throws | `error` entry tagged `isolate=workmanager`, persists across restart | [ ] manual |
| Logs directory read-only | Ring buffer still works, single warn surfaced | [ ] manual |
| Export with no logs | Toast "No logs to export"; share sheet not opened | [ ] manual |
| Export with logs | `.zip` produced; share sheet opens | [ ] manual |
| Clear logs | All files deleted, ring buffer empty | [x] unit + [ ] manual UI |
| `StorageError` from a `query<T>` | `warn` entry with provider name | [ ] manual |
| App restart after `paused` lifecycle | Logs readable; entries from before pause survive | [ ] manual |

## Decisions

### 2026-04-28 — Default log level in release is `debug`

**Context:** Standard practice is `info` or higher in release. We have no network cost, no analytics, and a hard size cap.
**Options:** A) `info` default, B) `debug` default, C) per-build flag.
**Decision:** B — `debug`.
**Rationale:** Field bugs are the whole reason this exists. The 60 MB ceiling and rotation guarantee bounded disk use. Users can lower the level in Diagnostics.

### 2026-04-28 — Single shared log file across isolates

**Context:** Workmanager and (future) purplebase background isolates need to log too.
**Options:** A) Per-isolate files merged at read, B) Single file with file lock.
**Decision:** B — single file with `flock` per batch.
**Rationale:** Simpler reader, simpler export, simpler in-app viewer. Lock contention is low because writes are batched.

### 2026-04-28 — Redact only secrets, not public Nostr content

**Context:** Public Nostr events are useful when debugging relay/parse issues; redacting them removes most of the diagnostic value.
**Options:** A) Redact all event JSON, B) Redact only kinds 4/13/1059 + nsec/NWC/secure-storage values.
**Decision:** B.
**Rationale:** Matches "only secrets" intent; preserves debuggability of public data.

### 2026-04-28 — Export bundles a zip via `share_plus`

**Context:** Multiple rotated files; users want a single attachment.
**Options:** A) Share latest file only, B) Zip all rotations.
**Decision:** B.
**Rationale:** One artifact, smaller transfer, complete history.

## Spec Issues

_None_

## Progress Notes

_None yet_

## On Merge

Delete this work packet. Promote any non-obvious decisions above to `spec/knowledge/DEC-XXX-*.md` (likely candidates: shared-log-file decision, default-debug-in-release decision).
