# WORK-005 — Updates Screen & Global Polling

**Feature:** FEAT-003-updates-screen.md
**Status:** In Progress (refactored architecture)

## Problem

The previous implementation mixed concerns: a single provider both watched local data AND performed remote fetches. This led to:
- Skeleton showing incorrectly during refreshes
- Timer-based invalidation causing network requests on every rebuild
- Pull-to-refresh not visually working (invisible RefreshIndicator)

## Implementation Notes

### Provider Architecture

- `updatePollerProvider` - Owns polling timer, performs `RemoteSource` queries, tracks `isChecking`/`lastCheckTime`
- `categorizedUpdatesProvider` - Watches `LocalSource()` only, reactive to local DB; watches poller to keep it alive
- `isCheckingUpdatesProvider` - Unified loading state for badge: `showSkeleton || poller.isChecking`
- `updateCountProvider` - Derives count from categorized state

### Keep-Alive Chain

`MainScaffold` → watches `categorizedUpdatesProvider` → watches `updatePollerProvider` → owns Timer

### Skeleton State

`showSkeleton = installedIds.isNotEmpty && !hasAnyMatch` where `hasAnyMatch` checks if any installed app ID exists in local DB query results.

## Architecture Diagram

```
┌────────────────────────────────────────────────────────────────┐
│                      Updates Screen (UI)                        │
│                                                                 │
│   ref.watch(categorizedUpdatesProvider)                        │
│        ↑                                                        │
│   LocalSource only - instant, reactive                         │
└───────────────────────────┬────────────────────────────────────┘
                            │
                    ┌───────┴────────┐
                    │   Local DB     │
                    └───────┬────────┘
                            ↑ writes
           ┌────────────────┼────────────────┐
           │                │                │
     Latest Releases    UpdatePoller     Install flow
     (stream: true)     (Timer-based)    (on install)
                        RemoteSource
                        imperative query
```

## Tasks

### Refactored Implementation (2026-02-04)

- [x] 1. Create `UpdatePollerNotifier`
  - Owns Timer.periodic (5 minutes)
  - Fetches apps, releases, metadata, assets from `RemoteSource`
  - Tracks `isChecking` and `lastCheckTime`
  - Pattern from `background_update_service.dart`

- [x] 2. Simplify `CategorizedUpdatesNotifier`
  - Changed to `LocalSource()` only
  - Watch `updatePollerProvider` to keep alive
  - Derive `showSkeleton` from data state

- [x] 3. Fix skeleton logic
  - `showSkeleton = installedIds.isNotEmpty && !hasAnyMatch`
  - Once any app matches, never show skeleton

- [x] 4. Fix pull-to-refresh
  - Call `updatePollerProvider.notifier.checkNow()`
  - Hide RefreshIndicator spinner (transparent + elevation 0) since `_LastCheckedIndicator` shows "Checking for updates..."
  - Fake fetch when throttled: show "Checking..." for 2s without network request

- [x] 5. Update MainScaffold
  - Use `isCheckingUpdatesProvider` for badge loading
  - Watch `categorizedUpdatesProvider` to keep poller alive

- [ ] 6. Test all scenarios

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Cold start (no local data) | Skeleton until ANY installed app matches | [ ] |
| Cold start (has local data) | Show widgets immediately | [ ] |
| Timer fires (5 min) | Remote fetch, no skeleton | [ ] |
| Pull-to-refresh | Animation plays, spinner in Last Checked | [ ] |
| Pull-to-refresh throttled | Animation plays briefly, no network | [ ] |
| Update from latest releases | Reflects immediately (local reactive) | [ ] |
| Network failure on poll | Graceful handling, retry next cycle | [ ] |
| Badge shows loading | When skeleton OR poller.isChecking | [ ] |
| Badge shows count | After data loaded, when updates exist | [ ] |

## Decisions

### 2026-02-04 — Separate UpdatePollerNotifier

**Context:** Previous implementation mixed local watching and remote fetching in one notifier, causing skeleton/loading state confusion.
**Decision:** Split into two providers: `UpdatePollerNotifier` (remote) and `CategorizedUpdatesNotifier` (local).
**Rationale:** Clear separation of concerns. Local provider is purely reactive. Remote provider handles network + throttling.

### 2026-02-04 — Skeleton state logic

**Context:** User clarified: skeleton should show only when NO installed app matches local DB.
**Decision:** `showSkeleton = installedIds.isNotEmpty && !hasAnyMatch`
**Rationale:** Once any single app is matched, we have enough to show a useful UI. "Other installed" section handles unmatched apps.

## Files Modified

| File | Change |
|------|--------|
| `lib/services/updates_service.dart` | Split into `UpdatePollerNotifier` + `CategorizedUpdatesNotifier`; added `isCheckingUpdatesProvider` |
| `lib/screens/updates_screen.dart` | Use `showSkeleton`; fix `_LastCheckedIndicator` to use poller state; fix RefreshIndicator |
| `lib/screens/main_scaffold.dart` | Use `isCheckingUpdatesProvider` for badge |

## Spec Issues

~~- FEAT-003 mentions `updatePollerProvider` which now exists (previously was merged into categorized).~~
~~- FEAT-003 should specify skeleton behavior: show only until first match, not during refreshes.~~

All resolved - spec updated with Cold Start Behavior and Reactivity sections.

## Progress Notes

**2026-02-04 (Refactor):** Complete architecture overhaul.
- Separated remote polling from local watching
- Fixed skeleton to only show on cold start (no matches)
- Fixed pull-to-refresh to be visible and call poller
- Added `isCheckingUpdatesProvider` for unified loading state
