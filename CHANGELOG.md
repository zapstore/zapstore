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