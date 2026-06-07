# WORK-012 - Latest Releases Asset-First Pagination

**Feature:** FEAT-004 asset-first queries
**Status:** Implemented

## Context

The home screen "Latest Releases" section can miss newly published apps/releases
and pagination appears not to fire.

Diagnosis:

- `LatestReleasesNotifier` queries `Release` kind `30063`, but current app-card
  freshness is driven by `SoftwareAsset` kind `3063`.
- The query has no platform filter, then resolves Apps after the fact.
- The UI dedupes by App after fetching only five raw Release rows, so duplicate
  releases for the same app can hide other apps until pagination runs.
- The viewport-fill pagination check is tied to a one-frame scroll extent check
  and should use the actual scroll extent after each render.

## Tasks

- [x] Switch Latest Releases first page to `SoftwareAsset` (`3063`) with platform filtering.
- [x] Keep local-first relationship resolution and avoid awaiting network on render.
- [x] Page older SoftwareAssets by asset `createdAt`.
- [x] Make initial/fill pagination trigger from actual scroll extent.
- [x] Attempt analyzer and focused tests where available.

## Verification

- [ ] `fvm flutter analyze`
- [x] Manual/code review: first page uses `SoftwareAsset`, `#f` platform filter,
  and no awaited network call in the render/listener path.

Analyzer could not run in this environment:

- `fvm dart format lib/widgets/latest_releases_container.dart` failed with
  `Operation not permitted` from the fvm shim.
- `dart format ...` failed trying to create/read files under the user pub/cache
  paths.
- `flutter analyze` failed with `operation not permitted: flutter`.

## Spec Issues

_None_
