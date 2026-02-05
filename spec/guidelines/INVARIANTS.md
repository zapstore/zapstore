# Zapstore — Invariants

The following guarantees are non-negotiable.
If any invariant is violated, the implementation is incorrect.

## UI Safety

- UI rendering must remain responsive under partial or total network failure.
- The UI must never block on I/O, cryptography, disk access, or network/relay operations.
- All background or asynchronous work must be cancellable and lifecycle-safe.
- Local data must be sufficient to render meaningful UI state; network access must enhance UX, not gate it.
- No operation may assume continuous network availability.

## Async Discipline

- No polling or artificial delays (e.g., Future.delayed for timing), except where explicitly designed
- Background work must surface results asynchronously and non-blockingly.
- All async work must be cancellable.

## Local-First Guarantees

- Cached data must be preferred over network data when available.
- Installed apps and metadata must be accessible offline.
- Network failures must degrade gracefully.

## Security & Verification

- APKs must never be installed unless their hash matches the expected value.
- Signed Nostr events must be verified before use.
- NWC secrets must be stored securely and must never be logged or exposed.

## Data Robustness

- Parsing unknown, missing, or future tags must not crash the app.
- Partial or invalid data must degrade gracefully.

## Lifecycle Safety

- Subscriptions must always be cancellable.
- Isolates and background jobs must not leak resources.
- Reconnection or retries must not duplicate events or actions.

## UX Safety

- All user-visible processes must have explicit states (loading, empty, success, error).
- Silent failures are unacceptable.


## Reproducible Android builds

Zapstore Android release artifacts MUST be bit-for-bit reproducible from the same git commit.

### Toolchain inputs MUST be pinned
- Flutter SDK version MUST be pinned via FVM (repo-controlled).
- Gradle wrapper / Android Gradle Plugin versions MUST remain pinned.
- Android compileSdk / buildTools / NDK versions MUST remain pinned in Gradle configuration.


### Determinism-critical build configuration MUST remain intact

- Java and Kotlin compilation MUST target Java 17.
- Gradle archive tasks MUST use reproducible file order and MUST NOT preserve file timestamps.
- Android release builds intended for verification MUST remain unsigned by default.
- The Android build MUST avoid experimental or unstable DSL modes that affect Flutter compatibility or output stability.

### Build outputs MUST be deterministic
- Builds MUST NOT depend on wall-clock time (honor `SOURCE_DATE_EPOCH` where applicable).
- Builds MUST NOT depend on host-specific paths or machine state (no absolute-path embedding).

### MUST NOT change (without explicit discussion)
- Do not introduce build flags/plugins that make outputs nondeterministic (timestamps, file order, random seeds).
- Do not modify the reproducible-build path to include signing or environment-specific steps.
