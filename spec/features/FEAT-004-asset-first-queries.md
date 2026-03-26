# FEAT-004 — Asset-First Query Optimization

## Goal

Speed up app listing screens by querying SoftwareAsset (kind 3063) first,
resolving the App (32267) via its direct relationship, and skipping the
intermediate Release (30063) for card-level display.

## Non-Goals

- Removing Release from the data model or detail screen
- Changing the models package
- Migrating all screens at once (phased rollout starting with Latest Releases)

## User-Visible Behavior

- App cards on the home screen load faster (2 hops instead of 4)
- No change to what the user sees: same name, icon, version pill, description
- Legacy 1063-only apps still appear, loaded via a separate fallback query
- Offline: local-first behavior unchanged — cached assets and apps render immediately

## Edge Cases

- App has only legacy FileMetadata (1063), no SoftwareAsset (3063) → fallback query picks it up (when `includeLegacy: true`; Latest Releases skips these)
- App has both 3063 and 1063 → 3063 wins (already the existing preference)
- SoftwareAsset exists but its App relationship fails to resolve → card skipped, no crash
- Network failure → local cache used, graceful degradation unchanged

## Acceptance Criteria

- [ ] `latestFileMetadata` renamed to `latestAsset` across codebase, with 3063→1063 fallback
- [ ] Centralized query helper with `includeLegacy` flag, reusable across screens
- [ ] LatestReleasesNotifier uses centralized query (3063-only, no legacy fallback)
- [ ] VersionPillWidget reads locally instead of re-fetching the full chain from remote
- [ ] `flutter analyze` clean
- [ ] Legacy 1063-only apps still appear in listings

## Notes

- The detail screen continues using Release for release notes, commit info, etc.
  Release is loaded via `latestRelease` relationship when needed, not as part of the card query.
- When legacy 1063 is fully removed, the fallback query function is deleted — single removal point.
