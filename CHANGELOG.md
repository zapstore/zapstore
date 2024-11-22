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