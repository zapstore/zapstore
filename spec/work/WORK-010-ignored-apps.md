# WORK-010: Ignored Apps

## Goal
Allow users to mark installed apps as "Ignored" so they are excluded from the
Updates screen and the "Update All" batch operation.

## Approach
Store the ignored set as an encrypted AppStack (kind 30267) with identifier
`zapstore-ignored-apps`, using the same pattern as the bookmarks stack. This
gives reactivity (SQLite + query<AppStack>), NIP-44 encryption, and
cross-device sync for free.

## Tasks
- [x] Add kIgnoredAppsIdentifier constant
- [x] Update appStackEventFilter to reject the ignored-apps stack by d tag
- [x] Add flutter_slidable dependency
- [x] Create ignoredAppsProvider (mirrors bookmarksProvider)
- [x] Create ignored_apps_service.dart with toggleIgnoredApp write helper
- [x] Filter ignored apps out of all lists in categorizedUpdatesProvider
- [x] Add ignoredApps: List<PackageInfo> to CategorizedUpdates
- [x] Wrap app cards in Slidable on updates screen (swipe-left -> Ignore)
- [x] Add "Ignored" section at bottom with swipe-left -> "Manage again" action

## Decisions
- Encrypted stack (not CustomData) reuses existing infrastructure and encryption
- Ignored app IDs stored as bare package IDs since uncataloged apps have no kind:pubkey prefix
- For cataloged apps the app.identifier (package ID) is used consistently
- Graceful degradation: unsigned-out users see no swipe actions
- Ignored apps excluded from all lists: automaticUpdates, manualUpdates, upToDateApps, uncatalogedApps
