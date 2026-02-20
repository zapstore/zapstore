# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
