# 1.0.0-rc2

- Feature: Connection status indicator in updates screen showing relay connectivity
- Feature: Improved debug info widget with enhanced diagnostics and information display
- Improvement: Major refactor using nested queries for better data loading patterns
- Improvement: Enhanced loading states across the entire app for better user feedback
- Improvement: Improved app stacks sorting algorithm for better content organization
- Improvement: App detail screen enhancements with additional information display
- Improvement: Download progress and status display improvements
- Improvement: Fallback to Zapstore CDN when APK files are not found on primary sources
- Improvement: Profile screen fixes and optimizations
- Improvement: Navigation back parameter support for better Android integration
- Improvement: Updated dependencies to latest versions
- Bugfix: Fixed app stack request issues
- Bugfix: Fixed profile display and author container issues

# 1.0.0-rc1

- Feature: Refreshed UI across the entire app with cleaner layouts, better spacing, and modern design patterns
- Feature: Complete package manager rewrite with improved error handling and reliable installation flow
- Feature: App Stacks - discover and browse curated app collections with dedicated screen and swipe animations
- Feature: Completely redesigned user/developer profile screen with improved layout and app pack displays
- Feature: Background update checker - automatic periodic service to check for app updates
- Feature: Support for new NIP event format for relay migration and future protocol upgrades
- Feature: Infinite scrolling in latest releases section for better content discovery
- Feature: Updates screen with tabs separating available updates, disabled, and latest releases
- Feature: Handle market intents and deeplinks for external app installation requests
- Improvement: Enhanced trust dialog with zap receipts showing community support levels
- Improvement: Certificate mismatch detection with clear user prompts before installation
- Improvement: NWC secrets now stored in secure storage instead of shared preferences
- Improvement: Revamped download service with proper queue management and concurrent download handling
- Improvement: Enhanced error messages with detailed descriptions from native Android package manager
- Improvement: Floating overflow menu for cleaner app detail screen navigation
- Improvement: Toast notifications with actionable buttons for follow-up actions
- Improvement: Pin Zapstore updates in releases section when new version is available
- Performance: Hash verification runs in background thread
- Performance: Optimized data loading and relay connection lifecycle
- Bugfix: Fixed back navigation and swipe gesture
- Bugfix: Fixed zaps display showing incorrect amounts and missing zappers
- Bugfix: Fixed comments not loading or displaying in app detail screens
- Bugfix: Fixed text overflow and sizing issues on large display devices

# 0.2.7

- Support new event format, prepare for relay migration and future NIP upgrade
- Increase default zap amounts (#219)

# 0.2.6

- Bugfix: Always refresh app when entering detail screen (#207)
- Bugfix: Use verify reputation as DVM (#210)
- Feature: Basic developer screen

# 0.2.5

- Bugfix: Improve version comparison and add guard
- Bugfix: Refresh local app status when not fetching from remote
- Bugfix: Remove Zapstore update toast and pin it in latest releases instead

# 0.2.4

- Bugfix: Fix bad version comparison
- Bugfix: Fix links in release notes
- Feature: Add a warning for old latest release

# 0.2.3

- Bugfix: Remove user caching

# 0.2.2

- Bugfix: Zap loading fix, zap sender fallback fix
- Feature: Replace buggy WoT API-based service with Vertex DVM-based service (alpha)

# 0.2.1

- Bugfix: Fix zaps caching, loading, display and order by amount
- Other minor bugfixes

# 0.2.0

- Feature: Sign in with NIP-55 (Amber)
- Feature: Ability to zap via NWC and view zap receipts

# 0.1.8

- Bugfix: Prevent unnecessary relay initialization
- Bugfix: [purplebase] Block until enough relay EOSEs
- Bugfix: Fix regression, offline-first models

# 0.1.7

- Performance: Faster, background downloads (#172)
- Feature: Full screen app images (#181)
- Feature: Remember trusted signers (#141)
- Feature: Show certificate mismatch before installing (#179)
- Bugfix: Swipe left should not close the app in nested screens (#175)
- Bugfix: App curation set related issues
- Bugfix: Prevent install in bad state: better UI and error toast

# 0.1.6

- Performance: Background loading, remove deprecated
- Bugfix: Themed icons (#152)
- Feature: Allow sending system info and error report (#149)
- Bugfix: Revert to previous installer plugin (#170)
- Bugfix: Success toast displays longer descriptions (#159)
- Bugfix: Duplicate success toast (#166)
- Feature: Deeplink signer param support (#151)

# 0.1.5

- Migration: zap.store to Zapstore/zapstore.dev
- Bugfix: Update screens to reflect uninstalled apps
- Bugfix: Toasts no longer trim text
- Bugfix: Check all APK signature hashes, not just first
- Feature: Show toast for available zapstore updates
- Feature: Show toast for successful app installation (#154)
- Feature: Ability to disable updates, show disabled updates app in updates screen (#157)
- Improvement: Bigger toasts and improved explanations, add toast actions (#159)
- Improvement: Better installation feedback messages (new plugin)
- Improvement: Install alert dialog (WoT display, show download domain)
- Improvement: Remove default "Signed by zapstore" (indexer)
- Improvement: Closed source notice
- Performance: refreshUpdateStatus optimization for single app

## 0.1.4

- Feature: Curated app sets
- Feature: Load more releases (show all)
- Feature: Better app cards and version/install state
- Performance: Complete rework of internals, preloading, caching, background work
- Bugfix: Wrong version checking
- Bugfix: Hash mismatch error
- Bugfix: Add context to web of trust container
- Bugfix: Support for missing repository (closed source)
- Many other bugfixes