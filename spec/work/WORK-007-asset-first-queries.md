# WORK-007 — Asset-First Query Optimization

**Feature:** FEAT-004-asset-first-queries.md
**Status:** In Progress

## Tasks

- [x] 1. Rename `latestFileMetadata` → `latestAsset` in AppExt
  - Files: `lib/utils/extensions.dart`, all call sites
  - Returns `Installable?` (shared interface for FileMetadata + SoftwareAsset)

- [x] 2. Make SoftwareAsset self-referential (`RegularModel<SoftwareAsset>`)
  - Files: `purplebase/models/lib/src/models/asset.dart`
  - No longer extends FileMetadata — duplicates shared getters
  - Added `Installable` interface implemented by both FileMetadata and SoftwareAsset
  - Updated entire install pipeline (InstallOperation, PackageManager, platform impls) to use `Installable`

- [x] 3. Create centralized app query helper function
  - Files: `lib/utils/app_query.dart` (new)
  - `appAssetsQuery()`: reactive `query<SoftwareAsset>` with `asset.app.query()`
  - `legacyAppQuery()`: reactive `query<App>` with old chain — delete when 1063 removed
  - `fetchAppsByAsset()`: imperative one-shot for pagination

- [x] 4. Rewrite LatestReleasesNotifier to use centralized helper
  - Files: `lib/widgets/latest_releases_container.dart`
  - 3063-only, no legacy fallback
  - Live head: `appAssetsQuery()` with `stream: true`, derives Apps from assets
  - Pagination: `fetchAppsByAsset()` imperative call

- [x] 5. Update VersionPillWidget to not re-fetch from remote
  - Files: `lib/widgets/version_pill_widget.dart`
  - Switched to `LocalSource` only — parent screens load data

- [x] 6. Self-review against INVARIANTS.md — all clean

## Decisions

### 2026-03-26 — Query direction: asset-first

**Context:** Current queries go App→Release→FileMetadata/SoftwareAsset (3-4 hops).
SoftwareAsset (3063) has a direct `BelongsTo<App>` relationship.
**Options:** (A) Keep App-first, optimize relay-side. (B) Query 3063 first, resolve App via relationship.
**Decision:** Option B.
**Rationale:** Eliminates Release from the card-display path. 2 hops instead of 4.
When 1063 is removed, the fallback function is simply deleted.

### 2026-03-26 — VersionPillWidget remote query

**Context:** VersionPillWidget independently re-fetches App→Release→Metadata from remote.
**Decision:** Switch to LocalSource or remove the query. Listing screens are responsible for loading data.
**Rationale:** Eliminates redundant network requests per card. Data is already loaded by the parent.

### 2026-03-26 — No legacy fallback in Latest Releases

**Context:** Latest Releases is the hot path on the home screen.
**Decision:** 3063-only, no 1063 fallback. Other screens can opt in via `includeLegacy: true`.
**Rationale:** Optimizes for fastest retrieval. Legacy apps appear elsewhere (search, updates, stacks).

## Spec Issues

_None_

## Progress Notes

**2026-03-26:** Completed rename of `latestFileMetadata` → `latestAsset` across all call sites.
**2026-03-26:** Made SoftwareAsset self-referential. Introduced `Installable` interface. Updated entire install pipeline.
**2026-03-26:** Created centralized query helpers. Rewrote LatestReleasesNotifier (3063-only). VersionPillWidget now local-only.
**2026-03-26:** `dart analyze lib/` — no issues found.

