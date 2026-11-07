# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.3] - 2026-11-07

### Changed

- On Home, stack names in the shelf and in Recent activity break onto a second line before they are cut, and the shelf cards grow with the text size
- Recent activity starts with releases only (the Social chip turns on the rest), shows the time in smaller text, and loads the next page as you scroll instead of a Load more button
- On an app page, the release and the privacy and security read are one "Latest release" card, and a tap anywhere on it opens one sheet with the release details and the full report; the sheet icon is gone
- The "Latest release" sheet reads security first (chips, text, permissions), then the release notes cut at 300 dp with Read more, then one table of release, package and source details with short hashes and a tiny copy icon right after each value
- On an app page, the app name is 5% larger with the author line closer under it, and the "…" zapper circle sits behind the last face in the stack
- In the release sheet, the details table keys have normal letter spacing, and "Show all" permissions are one plate with a chip per permission, what it does and its Android name, then the unnamed ones as chips
- In the release sheet, permissions list only the ones that matter, each in plain words (Camera, Nearby devices, Always running); routine ones such as INTERNET and the app's own are left out. Up to three are listed in full, more show as chips with a "What they allow" link
- On an app page, the Latest release card ends in a "Full report and release details" button, and the sheet opens with a line on the C1 proof (verified, none, or indexed only); "Activity" is "Comments"
- The install sheet shows where the APK comes from as "Installs from": the logo and short name on GitHub, GitLab, Codeberg and F-Droid, the full address elsewhere, for every app, not only indexed ones
- In the install sheet, "See full report" is the same "Full report and release details" button as on the Latest release card
- On an indexed app's page, the head reads "From" and the host's logo and short name (owner/repo on GitHub, GitLab and Codeberg, the app id on F-Droid, otherwise the host) in place of "Published by the catalog", and tapping it opens the repository, as in the Flutter app. The line is smaller and gray, runs under the "..." menu's edge, and when it must be cut it shortens the owner and keeps "/repo"
- On Home, the icons and avatars in Recent activity are about 5% bigger
- The warning sign in warning chips is a little smaller
- The version pill has about 10% more padding (23 dp tall, 8.8 dp on the sides)
- In Recent activity, a release names its author only when the author has a verified C1 proof
- Dates older than 60 days in comments and Recent activity read "Jun 6, 2025" instead of "2025-06-06"
- The version pill is one size everywhere (small bold text, light padding) instead of scaled copies for lists, Activity and comments
- The app icon in the app page head is 64 dp, a step up
- On an app page, "Stacked in" sits closer under the zap row
- A failed check in the privacy and security report is a yellow warning chip with a warning sign, not a red one with an X
- The share icon is Android's three connected dots
- On an app page, the zap total shows a "…" circle when more zappers than faces are listed, and no "from" when there are no faces; "Activity" shows only when there are comments; screenshots have a little more room above and below
- On a profile, a comment reads "Commented on <app>" and a stack update "Updated <stack>"
- A `nostr:nevent…`, `note…` or `naddr…` in a comment or zap note reads as "note", "app", "stack" or "post" and opens its link, and a mentioned person reads as `@name`, never the raw token; Recent activity previews show the same
- In app lists, the installed tick (or update clock) is a round badge on the app icon's corner instead of a pill after the name
- In Recent activity, a comment from someone with no name reads "Someone on <app>"
- In My apps, a Zapstore update stays first in its list, on the same gray as the stack cards
- App rows and the version pill grow with the system font size, and the pill's status icon sits closer to the text. At a large size, the Update or Install button on a My apps row moves onto its own line under the name
- An install error stays on screen until it is closed and offers Copy. Warning and error toasts use an alert icon, and a toast with a button spans the screen

### Added

- When Android refuses an update because the installed copy is signed by another key, My apps and the app page offer Reinstall. Confirming warns that the old copy and its data are removed, then installs the listed build

### Fixed

- An APK the listed key did not sign is refused as not the listed file

## [2.0.2] - 2026-11-06

### Changed

- On Home, curated stacks have their own header with See all, and each stack is a filled card
- A Social chip on Recent activity mixes comments, zaps, and stack updates in with releases. Off, the list is releases only
- A release in Recent activity shows the app's tagline. A comment, zap, or stack update starts with who did it
- The zap total on an app page reads "21k sats from" and the faces of its biggest zappers, and a zapper with no picture stays off the stack
- The welcome screen logo sits on a clear tile, and the text says downloads over Tor are opt-in
- Swiping a row in My apps slides it over to uncover buttons instead of running an action. An install in progress offers Ignore updates and Cancel, and an installed app now also offers Uninstall
- Swipe buttons are solid color with bold white text, and span the row's full height
- The update button on each row is wider

### Added

- A cancel button on the app page and on each row while an install is in progress

### Fixed

- Cancel works at every stage of an install and no longer leaves the row stuck
- Ignoring an app's updates cancels its install in progress, and it no longer appears under Installing
- Downloads keep going after leaving Zapstore, over Tor too
- Background updates run as a foreground job, so Android no longer stops long downloads
- An app that runs in the background, like a messenger or VPN, no longer waits to update until it is closed. The update closes it and installs right away

## [2.0.1] - 2026-11-05

### Changed

- Type across the app is one step larger
- The "..." menu on an app is a dropdown. Saving leaves it open, and Repository is listed when the listing has one
- Home remembers which activity filter is selected
- On the Home shelf, stack icons line up with the page edge, and a stack no longer shows its count
- An app in Recent activity uses a larger icon
- The stacks line says "User curated stacks"
- A generated summary says it comes from the catalog

### Fixed

- An APK larger than 300 MiB downloads

## [2.0.0] - 2026-11-05

### Added

- A new Android app, written in Kotlin. It installs over 1.1.3, keeps the device key, and uses the same signing key
- Search on the phone for apps, stacks, and developers
- Comments and zaps on Home, on a profile, and on a stack
- More than one catalog, in an order the person sets
- An npub or nprofile in text shown as @name
- Direct or Tor for the app's own connections

### Changed

- Version code 6000, so the update replaces every Flutter 1.1.3 split
- An app installed from another store with the same signing key counts as Zapstore's
- Update checks run every hour
- The app page shows the zap total
- Bug reports stay off until the person turns them on

### Fixed

- A Keystore failure no longer replaces the device key
- A backup encrypted with NIP-04 can be decrypted
- An APK is hashed as it downloads, and a slow transfer is not stopped at ten minutes

## [1.1.3] - 2026-10-20

### Changed

- Comments, zaps, stacks, and saved apps still match an app after the catalog signing key changes
- An installed file is found by its hash, which the next relay answers for
- Comments, replies, and reports on an app name its app id, which the next relay requires

### Fixed

- Opening an app the relay no longer has leaves the screen and keeps the local copy
- A replaced catalog listing is removed locally only after the new listing and its installable file are stored

## [1.1.2] - 2026-08-27

### Added

- App info shows the Android installer package ID when the app was not installed by Zapstore

### Fixed

- Reject Nostr events whose claimed id no longer matches the serialized event (tampered kind/content)

## [1.1.1] - 2026-07-25

### Changed

- Clearer welcome copy on the search screen
- Store listing metadata (short/full description)

### Fixed

- Amber session restore no longer decrypts the device-key backup on every cold start (#385)
- Trust dialog no longer hangs when the publisher has no kind-0 profile (#386)
- Paused download button shows a distinct paused state

## [1.1.0] - 2026-07-17

### Added

- Device key: portable encrypted device state, Amber key backup, and restart-safe PoW publishing for private device events
- Opt-in background auto-updates with Wi-Fi-only downloads and silent or staged installs
- App catalog relays as device-signed kind 10067 events
- NIP-56 app reporting from the overflow menu
- Local diagnostic logs with export/share
- Unmanaged apps on the Updates screen (#315)
- C1 proof verification before install
- CDN image variants (including profile pictures via CDN pubkey path)

### Changed

- Latest releases are asset-first / offline-first again
- Private app data moves to the device key; remove the broken installed-apps backup feature
- Narrower deep link handling
- Drop Linux and macOS platform targets

### Fixed

- Amber restore from backup
- Unmanaged apps persistence and AppCatalog publish acceptance for reports/updates
- Resume and navigation responsiveness (log flush races, incremental catalog resolution, coalesced package scans)

## [1.0.6] - 2026-04-11

### Fixed

- Only block on actual signature mismatch

## [1.0.5] - 2026-04-11

### Changed

- Certificate verification errors now include Android version/API level and clearer expected vs actual hash details for troubleshooting
- Profile: refreshed icon and styling for the "Back up installed apps" setting

### Fixed

- APK signing certificate verification reads the current signer in a way that matches publisher-declared hashes across more Android versions and OEM builds
- Catalog authority deletions (NIP-09): kind-5 events that reference replaceable coordinates via `a` tags now remove matching local events, not only explicit event IDs
- Stack cards: title area no longer reserves fixed two-line height when the name is shorter
- Latest releases: load the next page when the first page does not fill the viewport (short list / search context)

## [1.0.4] - 2026-04-10

### Added

- Backup and restore functionality for app collections (#20)

### Changed

- Better latest release fetching with improved query performance
- Catalog seeding now only occurs for default catalog (#357)

### Fixed

- Latest releases query issues
- APK signature verification now checks all hashes correctly

## [1.0.3]

### Added

- Floating overflow menu for apps and stacks with shared, reusable component
- In-app navigation from comment links (Nostr references resolve inside the app)
- Search supports `naddr`, `npub`, and Zapstore URLs directly in the search input

### Changed

- Improvements on deletion checks
- Relay configuration tuned for communities
- Stacks query apps with full pubkey + app ID for correctness

### Fixed

- Deep link handling regression

## [1.0.2] - 2026-04-06

### Added

- Deep link resolver (#341)
- Relay hints in NIP-82 app `naddr` encoding so other clients resolve app events against the catalog relay (#343, #344)
- NIP-09 deletion handling
- Script to build the seed database for default stacks and apps
- Indexer pubkey and consistent `subscriptionPrefix` usage for App Catalog subscriptions

### Changed

- New stacks screen with stack migration; faster home loading via asset-first queries and query tuning
- Updates: more efficient polling and change detection; latest-releases area refactored with clearer loading (spinner) and initialization ordering (no storage queries until init completes)
- Navigation bar: reduced perceived tap lag and consistent pill height (#333); SafeArea fixes for cut-off bottom bar
- Install flow: read package name from APK manifest; retry download via CDN on hash mismatch; certificate mismatch UI improved; removed force-update option
- Humanized relay error messages; improved search results; better app detail skeletons; removed confusing installation progress banner on updates
- CDN fetch behavior uses redirect where appropriate; social events published to community relay as intended
- Simplified download watchdog (more reliance on Android); optimized GitHub and F-Droid icons (#352)

### Fixed

- Deep link handling
- "Can't zap" regression
- Version pill out of sync with installed/available state
- APK signature detection; system install dialog dismissed without clear UX
- Stacks: infinite loading, load-more pagination, display bugs, and migration edge cases
- Latest releases and catalog loading (including fetcher and parallel release load)

## [1.0.1] - 2026-02-20

### Changed

- Disabled page transitions to reduce navigation glitches on lower-performance devices (#310)

### Fixed

- Install queue getting stuck with multiple concurrent downloads (#319)
- "Always trust" toggle now hidden when user is not signed in
- Install retry not showing immediate feedback until native callbacks arrived
- Install confirmation staying stuck in pending state
- Auto sign-out not triggering when Amber is uninstalled while user is still signed in
- Not all apps showing in an app stack
- Back gesture handling on screenshot viewer

## [1.0.0] - 2026-02-10

### Added

- New and improved updates experience: background update checking, dedicated updates UI, and batch progress tracking
- App Stacks improvements, including curated discovery and relay management controls

### Changed

- Final stable release of all `1.0.0-rc*` improvements; no additional features beyond the release candidates
- Major UX refresh across core screens, including redesigned profile and app detail experiences
- Complete package manager/install flow rewrite with more reliable state transitions and clearer install feedback
- Improved download and verification pipeline with better concurrency, watchdog handling, CDN fallback, and reduced memory pressure
- Stronger trust and safety flows, including better certificate mismatch handling and more secure NWC secret storage

### Fixed

- Broad stability fixes across updates, app detail, dialogs, zaps, navigation, and background sync behavior

## [1.0.0-rc5] - 2026-02-07

### Added

- User-friendly error messages with expandable technical details for bug reports

### Changed

- Improved install button state handling (install → uninstall → reinstall works reliably)
- Better device performance heuristics for download concurrency
- More frequent sync of installed packages
- Improved version comparison logic

### Fixed

- Download watchdog timeout not triggering correctly

## [1.0.0-rc4] - 2026-02-05

### Added

- New updates screen with polling and batch tracking
- Batch progress display in UI with completed state indication

### Changed

- Improved watchdog for stalled downloads
- More reasonable update notification logic with better throttling
- Explicit download queue tracking with dynamic maxConcurrentDownloads
- App stacks queries now platform-specific only

### Fixed

- Package manager edge case handling and state transitions
- Notification icon display issues

## [1.0.0-rc3] - 2026-01-30

### Added

- App catalog relay management - add, edit, and remove relays from profile screen (#205)

### Changed

- Better install button labels with clearer status indication
- Improved zap flow UX - dialogs close immediately, zaps run in background with toast feedback
- Better placeholder display for profiles that haven't loaded yet
- Single-pass APK verification running entirely in background thread
- Removed getPackageArchiveInfo() call to prevent potential out-of-memory issues
- Memory improvements and concurrency fixes (#280)

### Fixed

- App detail screen freezing in skeleton loading state
- Saved apps section stuck in loading or disappearing (#283)
- Multiple dialog issues (#296)
- Zap dialogs crash when tapping outside and async toast issues (#275)
- NWC button crash
- Clear local storage overflow issue (#284)
- Jumping bug in updates screen
- Race condition in background update service
- Sync installed packages when navigating to updates screen

## [1.0.0-rc2]

### Added

- Connection status indicator in updates screen showing relay connectivity
- Improved debug info widget with enhanced diagnostics and information display

### Changed

- Major refactor using nested queries for better data loading patterns
- Enhanced loading states across the entire app for better user feedback
- Improved app stacks sorting algorithm for better content organization
- App detail screen enhancements with additional information display
- Download progress and status display improvements
- Fallback to Zapstore CDN when APK files are not found on primary sources
- Profile screen fixes and optimizations
- Navigation back parameter support for better Android integration
- Updated dependencies to latest versions

### Fixed

- App stack request issues
- Profile display and author container issues

## [1.0.0-rc1]

### Added

- Refreshed UI across the entire app with cleaner layouts, better spacing, and modern design patterns
- Complete package manager rewrite with improved error handling and reliable installation flow
- App Stacks - discover and browse curated app collections with dedicated screen and swipe animations
- Completely redesigned user/developer profile screen with improved layout and app pack displays
- Background update checker - automatic periodic service to check for app updates
- Support for new NIP event format for relay migration and future protocol upgrades
- Infinite scrolling in latest releases section for better content discovery
- Updates screen with tabs separating available updates, disabled, and latest releases
- Handle market intents and deeplinks for external app installation requests

### Changed

- Enhanced trust dialog with zap receipts showing community support levels
- Certificate mismatch detection with clear user prompts before installation
- NWC secrets now stored in secure storage instead of shared preferences
- Revamped download service with proper queue management and concurrent download handling
- Enhanced error messages with detailed descriptions from native Android package manager
- Floating overflow menu for cleaner app detail screen navigation
- Toast notifications with actionable buttons for follow-up actions
- Pin Zapstore updates in releases section when new version is available
- Hash verification runs in background thread
- Optimized data loading and relay connection lifecycle

### Fixed

- Back navigation and swipe gesture
- Zaps display showing incorrect amounts and missing zappers
- Comments not loading or displaying in app detail screens
- Text overflow and sizing issues on large display devices

## [0.2.7]

### Added

- Support new event format, prepare for relay migration and future NIP upgrade

### Changed

- Increase default zap amounts (#219)

## [0.2.6]

### Added

- Basic developer screen

### Fixed

- Always refresh app when entering detail screen (#207)
- Use verify reputation as DVM (#210)

## [0.2.5]

### Fixed

- Improve version comparison and add guard
- Refresh local app status when not fetching from remote
- Remove Zapstore update toast and pin it in latest releases instead

## [0.2.4]

### Added

- Warning for old latest release

### Fixed

- Bad version comparison
- Links in release notes

## [0.2.3]

### Fixed

- Remove user caching

## [0.2.2]

### Added

- Replace buggy WoT API-based service with Vertex DVM-based service (alpha)

### Fixed

- Zap loading fix, zap sender fallback fix

## [0.2.1]

### Fixed

- Zaps caching, loading, display and order by amount
- Other minor bugfixes

## [0.2.0]

### Added

- Sign in with NIP-55 (Amber)
- Ability to zap via NWC and view zap receipts

## [0.1.8]

### Fixed

- Prevent unnecessary relay initialization
- [purplebase] Block until enough relay EOSEs
- Fix regression, offline-first models

## [0.1.7]

### Added

- Full screen app images (#181)
- Remember trusted signers (#141)
- Show certificate mismatch before installing (#179)

### Changed

- Faster, background downloads (#172)

### Fixed

- Swipe left should not close the app in nested screens (#175)
- App curation set related issues
- Prevent install in bad state: better UI and error toast

## [0.1.6]

### Added

- Allow sending system info and error report (#149)
- Deeplink signer param support (#151)

### Changed

- Background loading, remove deprecated

### Fixed

- Themed icons (#152)
- Revert to previous installer plugin (#170)
- Success toast displays longer descriptions (#159)
- Duplicate success toast (#166)

## [0.1.5]

### Added

- Show toast for available zapstore updates
- Show toast for successful app installation (#154)
- Ability to disable updates, show disabled updates app in updates screen (#157)

### Changed

- Migration: zap.store to Zapstore/zapstore.dev
- Bigger toasts and improved explanations, add toast actions (#159)
- Better installation feedback messages (new plugin)
- Install alert dialog (WoT display, show download domain)
- Remove default "Signed by zapstore" (indexer)
- Closed source notice
- refreshUpdateStatus optimization for single app

### Fixed

- Update screens to reflect uninstalled apps
- Toasts no longer trim text
- Check all APK signature hashes, not just first

## [0.1.4]

### Added

- Curated app sets
- Load more releases (show all)
- Better app cards and version/install state

### Changed

- Complete rework of internals, preloading, caching, background work

### Fixed

- Wrong version checking
- Hash mismatch error
- Add context to web of trust container
- Support for missing repository (closed source)
- Many other bugfixes
