# Zapstore — Architecture

## Core Principle
Architecture exists to prevent accidental coupling and hidden ownership.
Each package has clear responsibilities and must not exceed them.

## Package Responsibilities

### models
- Domain models for Nostr events, kinds, zaps, releases, and NWC
- Parsing, validation, signing, encryption, and verification
- Pure domain logic only
- Must not depend on storage, networking, isolates, or UI

### purplebase
- Local-first storage and indexing (SQLite)
- Relay synchronization and subscription lifecycle management
- Background work and isolate execution
- Must not depend on UI or presentation logic

### zapstore
- Flutter UI and application orchestration
- Navigation, presentation, and user interaction
- Coordinates use cases across packages
- Must not contain domain rules or persistence logic

## Dependency Rules
- zapstore → purplebase → models
- Reverse dependencies are forbidden
- UI widgets must not manage relay connections, storage, or background jobs

## Ownership & Orchestration
- Relay pools and subscriptions are owned by purplebase
- Background work lifecycle is explicit and cancellable
- zapstore orchestrates flows but does not own low-level resources
