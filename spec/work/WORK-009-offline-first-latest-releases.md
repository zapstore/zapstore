# WORK-009 — Offline-First Home Screen

**Feature:** (bug fix — restores INVARIANTS.md: local-first + no UI gating on network)
**Status:** In Progress

## Context

The home screen (Latest Releases, App Stacks) stays on skeletons for 1+ minute when offline, even though all data is already in SQLite.

### First pass — fixed a real bug, but not the one the user saw

Initial diagnosis focused on `LatestReleasesNotifier` in `lib/widgets/latest_releases_container.dart`:

1. The first-page listener only reacted to `StorageData<Release>`. `RequestNotifier` delivers local-first data as `StorageLoading(localModels)` during the `awaitingRemote` phase (see `request_notifier.dart` `_emit`: `StorageLoading` during `initializing`/`awaitingRemote`, `StorageData` only after EOSE or `responseTimeout`). Local Releases sat unconsumed.
2. The listener then `await`ed `_resolveRelated(...)` which ran three imperative `storage.query(..., LocalAndRemoteSource(stream: false))` calls. Imperative `storage.query` with `LocalAndRemoteSource` blocks on `RemoteQueryOp` before returning any local data (see `purplebase_storage.dart` line ~254). Each call waited the absolute EOSE timeout.

Both real. Both fixed (see tasks below). **Neither was the dominant cause** of the user-visible hang.

### Second pass — actual root cause

The containers are hard-gated in `search_screen.dart`:

```dart
AppStackContainer(
  showSkeleton: !(initState.hasValue || initState.hasError),
),
LatestReleasesContainer(
  showSkeleton: !(initState.hasValue || initState.hasError),
  ...
),
```

where `initState = ref.watch(appInitializationProvider)`. That provider awaits the full init chain — including `_attemptAutoSignIn` → `onSignInSuccess`, which did:

```dart
await storage.query(
  RequestFilter<ContactList>(authors: {pubkey}).toRequest(),
  source: const RemoteSource(relays: 'social', stream: false),
  subscriptionPrefix: 'app-contact-list',
);
```

Offline, `RemoteSource(stream: false)` blocks on EOSE per relay: up to 5 s connect × per-relay retries × 3 social relays, plus the `eoseTimeoutSingleRelay` / `eoseTimeout` windows (30 s / 15 s) from purplebase's `PoolConfiguration`, plus reconnect backoff. That matches the observed "1+ minute" hang exactly.

While `showSkeleton` is true, the notifier providers aren't even watched — so the first-pass fix had no user-visible effect because the notifier never ran.

## Decision — no model/purplebase primitives needed

`LocalAndRemoteSource` + the `and:` callback on `query<T>(...)` already implement local-first with background relationship resolution (`NestedQueryManager._executeNestedQuery` fires relationships via `_queryBuffer.bufferQuery(...).then(...)`, never awaited on the emission path). The fix is to stop reinventing this at the widget layer and use the primitive as intended.

See `spec/knowledge/` (to be promoted after merge): guidance that multi-hop loads MUST be expressed via `and:` on an outer reactive query, not via imperative `storage.query` chains.

## Tasks

- [x] 1. Rewrite `LatestReleasesNotifier._subscribe` to:
  - Add `and:` on outer `query<Release>(...)` that pulls `release.app` (+ nested `app.author`) and `release.softwareAssets`.
  - React to every state emission (local data carried by both `StorageLoading(models)` and `StorageData(models)`). Only `StorageError` short-circuits.
  - Compute `appsByIdentifier` via `storage.querySync` — no `await` on any network path.
  - Delete `_resolveRelated` entirely.
- [x] 2. Rewrite `loadMore`:
  - Keep imperative `storage.query` for older Releases (user-initiated, acceptable to await).
  - Drop the imperative `_resolveRelated`. Fire a non-blocking relationship fetch for the older page (`unawaited`) — the first-page listener's general-update path will refresh `appsByIdentifier` as Apps land.
  - Resolve whatever is already local via `storage.querySync` before returning.
- [x] 3. Audit other notifiers for the same `if (next is StorageData<T>)` footgun.
  - `PagedSubscriptionNotifier.updateFirstPage` — already OK (copies `firstPage: next` in the else branch, widgets use `.combined`/`.models`).
  - `app_stacks_screen.dart`, `profile_screen.dart`, `app_detail_screen.dart` — reviewed, use `StorageLoading && models.isEmpty` idiom correctly.
  - `updates_service.dart` imperative `storage.query` is on `LocalSource`, so not a network-blocking path.
- [x] 4. Self-review against INVARIANTS.md — clean (UI safety, async discipline, local-first guarantees all upheld).
- [x] 5. `fvm flutter analyze` clean.
- [x] 6. `fvm flutter test` — existing suite still passes.
- [x] 7. **Split initialization** to decouple local-first UI from network warm-ups.
  - Added `storageReadyProvider` (`lib/main.dart`): returns as soon as SQLite + worker isolate are ready. Zero network dependencies.
  - `appInitializationProvider` now `await ref.read(storageReadyProvider.future)` first, then continues with device capabilities, package sync, deep links, auto-sign-in.
  - `search_screen.dart` now gates the skeleton on `storageReadyProvider` instead of `appInitializationProvider`. Other consumers of `appInitializationProvider` (updates polling in `updates_service.dart`; error overlay in `main.dart`) keep the original gate — they legitimately want the full init done.
- [x] 8. **De-block `onSignInSuccess`** — the contact-list fetch is now `unawaited` + `catchError`. It was a cache warm-up, never a gate. Consumers of `ContactList` read it reactively via storage queries and re-render when it lands.
  - Signature changed from `Future<void>` → `void`. Call sites updated (`main.dart`, `widgets/sign_in_button.dart`).
- [x] 9. **Updates screen: decouple display from polling** (`lib/services/updates_service.dart`).
  - Added `UpdatePollerState.hasHydrated`. The categorizer now gates on `hasHydrated` instead of `lastCheckTime == null`.
  - `_init` switched from `appInitializationProvider` to `storageReadyProvider`. On storage-ready, the poller runs `refreshFromLocal()` first (local-only, offline-safe) which flips `hasHydrated: true`. UI unblocks immediately.
  - `_startPolling` now fires the remote `checkNow()` as `unawaited` — polling is purely background; it no longer gates the render. Offline, the "Checking for updates..." indicator spins at the top, but the list of apps renders from local data.
  - `refreshFromLocal()` now always sets `hasHydrated: true`, even when installed set is empty or a local read fails, so the UI never gets stuck on skeleton.
- [x] 10. **Infinite scroll offline** for Latest Releases and App Stacks.
  - `LatestReleasesNotifier.loadMore` and `StacksNotifier.fetchOlderPage` now read local via `storage.querySync` first. If local has results, those are used immediately and a background `LocalAndRemoteSource(stream: false)` fetch warms the cache for the next page. If cold-cache, the remote fetch is tried with a 5 s timeout and falls back to empty — so offline scroll fails fast instead of hanging indefinitely.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Offline, local Releases present | First page renders within one frame; no await on network | [ ] manual |
| Offline, no local Releases | Skeleton stays until relationship fetch resolves/fails; no hard error | [ ] manual |
| Online, cold cache | Same render timing as before (no regression) | [ ] manual |
| `loadMore` while offline | Older page attempt fails fast; first page remains rendered | [ ] manual |

**Automated regression test:** deferred. A meaningful test requires simulating a blocking relay that never sends EOSE. `DummyStorageNotifier` does not model relay-blocking — its `LocalAndRemoteSource` returns immediately, which makes the old code appear to work offline in tests. Proper coverage belongs in `purplebase`'s integration tests (against its stub relay) or in a Patrol integration test that toggles airplane mode. Tracked here so it isn't silently skipped.

Code review evidence that the invariant now holds:
- No `await` on any `storage.query(..., RemoteSource)` inside the state-update path of the first-page listener (`_applyFirstPage` is synchronous).
- All relationship loading goes through `and:` → `NestedQueryManager._executeNestedQuery` which uses `_queryBuffer.bufferQuery(...).then(...)` — non-blocking.
- `loadMore`'s imperative remote `storage.query` remains, but is user-initiated and wrapped in try/catch; failure resets `isLoadingMore` without affecting `firstPage`.

## Decisions

### 2026-04-30 — Keep Release-first (don't restore asset-first)

**Context:** WORK-007 (FEAT-004) specified asset-first queries for the home screen; subsequent commits ("Refactored latest releases container, basic again" → 6bb9c7f) reverted `LatestReleasesNotifier` to Release-first. Restoring asset-first is a larger change and out of scope for this fix.
**Decision:** Keep current Release-first query shape; fix only the blocking/guard bugs.
**Rationale:** Smallest change that restores the invariant. FEAT-004 can be revisited separately.

### 2026-04-30 — Don't change models/purplebase

**Context:** We considered whether `LocalAndRemoteSource` needs new semantics (e.g., imperative `storage.query` returning local-first). There are two separate improvements worth considering, but neither is required for this fix:
1. Rename `StorageLoading(models)` to something like `StorageFresh`/`StoragePartial` so consumers can't accidentally gate on `is StorageData`.
2. Make imperative `storage.query(..., LocalAndRemoteSource)` return local immediately and fire remote in the background.

**Decision:** Defer. File as follow-ups.
**Rationale:** The reactive path (`ref.watch(query<T>(and:))`) already implements local-first correctly. The app-layer fix restores the invariant without touching model semantics.

## Spec Issues

_None_

## Progress Notes

**2026-04-30 (1):** Diagnosed, then rewrote `LatestReleasesNotifier` to use `and:` + local-synchronous app resolution. Dropped `_resolveRelated`. Missed the dominant root cause — the skeleton gate was on the full init chain, so the notifier was never constructed until the contact-list remote query timed out.

**2026-04-30 (2):** Second pass. Split `appInitializationProvider` into `storageReadyProvider` (pre-network, used by UI skeletons) and `appInitializationProvider` (full chain). Made `onSignInSuccess` non-blocking. Home screen now renders local data as soon as SQLite is open, regardless of network state.

**2026-04-30 (3):** Third pass, same class of bug in two more places:
- **Updates screen** was gated on `pollerState.lastCheckTime == null`, which only flips after a successful remote poll. Offline, that never happens for 1+ minute (three social relays + AppCatalog timeouts). Replaced with `hasHydrated` flag flipped by a local-only `refreshFromLocal()` called on `storageReadyProvider`. Remote polling is now `unawaited` — purely a background cache refresh.
- **Infinite scroll** on Latest Releases / App Stacks used imperative `storage.query(..., LocalAndRemoteSource(stream: false))` which blocks on `RemoteQueryOp` before returning local data. Offline this hung the spinner forever. Changed to local-first via `querySync` with a background hydrate, or — for cold cache — a remote fetch with a 5 s timeout and empty fallback.

## Lesson

Three passes, same pattern: **an awaited remote call was sitting in the render path**. In each case the "gate" had a sensible justification at the time (don't show stale data, don't render before storage is open, don't paginate without a relay answer), but each gate violated the offline-first invariant by making the UI wait on the network.

Generalizable rules:
- If a widget uses `showSkeleton: !(someProvider.hasValue || someProvider.hasError)`, audit *every awaited call* in that provider. Any remote `storage.query` — even `LocalAndRemoteSource(stream: false)` — is a hang offline.
- Imperative `storage.query(..., LocalAndRemoteSource(stream: false))` is not local-first. It awaits remote first, then reads local. Use `storage.querySync` up front when rendering, and fire the `LocalAndRemoteSource` version as a background hydrate.
- "First successful remote poll" is not a safe precondition for rendering. If the UI needs to categorize or paginate, derive from local state and let the remote enhance asynchronously.

## On Merge

Delete this work packet. Promote the "multi-hop loads MUST use `and:`" guidance to `spec/knowledge/DEC-XXX-relationship-queries.md` if no existing knowledge entry covers it.
