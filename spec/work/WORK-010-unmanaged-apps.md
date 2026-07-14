# WORK-010: Unmanaged Apps

## Goal
Allow users to mark installed apps as "Unmanaged" so they are excluded from the
Updates screen and the "Update All" batch operation.

## Approach
Store the unmanaged set as an encrypted AppStack (kind 30267) with identifier
`zapstore-unmanaged-apps`, using the same pattern as the bookmarks stack. This
gives reactivity (SQLite + query<AppStack>), NIP-44 encryption, and
cross-device sync.

## Tasks
- [x] Add kUnmanagedAppsIdentifier constant
- [x] Update appStackEventFilter to reject the unmanaged-apps stack by d tag
- [x] Add flutter_slidable dependency
- [x] Create unmanagedAppsProvider (mirrors bookmarksProvider)
- [x] Create unmanaged_apps_service.dart with toggleUnmanagedApp write helper
- [x] Filter unmanaged apps out of all lists in categorizedUpdatesProvider
- [x] Add unmanagedApps: List<PackageInfo> to CategorizedUpdates
- [x] Wrap app cards in Slidable on updates screen (swipe-left -> Unmanage)
- [x] Add "Unmanaged Apps" section at bottom with swipe-left -> "Manage" action
- [x] Serialize optimistic writes so each replacement includes prior actions
- [x] Keep one Updates scroll controller while cards change sections
- [x] Close the slide action before reclassifying its card
- [x] Coalesce installed-package scans and suppress unchanged map emissions
- [x] Run native installed-package enumeration off Android's main thread
- [x] Cover overlapping installed-package scan coalescing
- [x] Include the platform tag and require explicit relay acceptance
- [x] Surface local-save and relay-publish failures to the user
- [x] Cover accumulation, overlapping writes, timestamps, tags, and failures
- [x] Load the decrypted local stack before the background AppCatalog refresh completes
- [x] Use a non-streaming background refresh for the unmanaged-apps query
- [ ] Preserve catalog metadata for cataloged unmanaged apps in the Unmanaged Apps section
- [ ] Extend native package scan with Android installer-source metadata
- [ ] Default apps installed by known third-party app stores to unmanaged
- [ ] Keep browser/manual/package-installer installs managed by default
- [ ] Add explicit override state so user Manage/Unmanage choices win over installer-source defaults
- [ ] Cover defaulting, override, restart, and unavailable-installer-source cases in tests

## Decisions
- Encrypted stack (not CustomData) reuses existing infrastructure and encryption
- Unmanaged app IDs stored as bare package IDs since uncataloged apps have no kind:pubkey prefix
- For cataloged apps the app.identifier (package ID) is used consistently
- Graceful degradation: unsigned-out users see no swipe actions
- Unmanaged apps are excluded from automaticUpdates, manualUpdates, upToDateApps, and uncatalogedApps
- Installer-source detection should be conservative: unknown or ambiguous source means managed
- Third-party app stores should default unmanaged; Zapstore, package installer, file manager, browser, shell, and unknown/manual flows should default managed
- Automatic defaults need a persistent "user has overridden this package/install" signal; otherwise tapping Manage would be undone by the next package scan
- Cataloged unmanaged apps should render from App metadata when local catalog data exists, and fall back to PackageInfo only when uncataloged
- Imperative storage queries return encrypted stacks before post-load decryption,
  so writes must use notifier-owned decrypted state rather than re-reading
  `privateAppIds` from that path.
- Parameterized replacement writes use strictly increasing whole-second
  timestamps to avoid same-second replacement collisions.
- An unmanaged-app action succeeds remotely only when an AppCatalog relay
  explicitly accepts the signed device-key event.
- Installed-package scans are single-flight and do not emit a fresh installed
  map when Android reports no changes.
- Android package enumeration runs on a lifecycle-owned worker so package,
  signature, and installer-source reads cannot block rendering.
- The unmanaged-apps query uses `LocalAndRemoteSource(stream: false)`: local
  SQLite state is emitted immediately, while the one-shot AppCatalog query
  refreshes it in the background without leaving a live subscription.

## Implementation Notes
- Android can expose source through `PackageManager.getInstallSourceInfo(packageName)` on API 30+ and `getInstallerPackageName(packageName)` on older APIs.
- Add installer-source fields to `PackageInfo`, `AndroidPackageManager.syncInstalledPackages`, `BackgroundPackageManager.syncInstalledPackages`, and `InstalledPackagesSnapshot`.
- Store policy separately from the unmanaged AppStack or extend the private data model carefully: the unmanaged stack alone cannot represent both "auto-unmanaged by source" and "user explicitly managed this package".
- The updates categorizer currently removes unmanaged app IDs before querying App metadata. To preserve metadata, it needs a separate local query for unmanaged cataloged IDs with `latestAsset` / `latestRelease.latestMetadata`, then render those with `AppCard` and a Manage action.
