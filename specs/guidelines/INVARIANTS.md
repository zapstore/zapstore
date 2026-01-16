# Zapstore — Invariants

The following guarantees are non-negotiable.
If any invariant is violated, the implementation is incorrect.

## UI Safety
- The UI thread must never block on network, file I/O, cryptography, or relay operations.
- UI rendering must remain responsive under partial or total network failure.

## Async Discipline
- No polling or artificial delays (e.g., Future.delayed for timing).
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
