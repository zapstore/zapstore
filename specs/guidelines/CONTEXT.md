# Zapstore — Project Context

This document provides **orientation only**.
It does **not** define rules, behavior, or constraints.

If anything in this file conflicts with files under `specs/00_foundation/`,
this file is wrong.

---

## What This Repository Is

Zapstore is a Flutter-based, local-first application store built on top of
the Nostr protocol and Bitcoin Lightning payments.

It allows users to:
- discover apps published via Nostr,
- verify and install applications safely,
- and directly support developers through zaps.

Zapstore prioritizes trust, verification, and user sovereignty over scale,
growth, or engagement metrics.

---

## Core Stack (High Level)

- **Frontend**: Flutter (Android-first)
- **Protocols**: Nostr, Lightning Network
- **Payments**: Zaps, NWC (NIP-47)
- **Data Model**: Nostr event kinds (apps, releases, files, zaps)
- **Storage**: Local-first (SQLite), relay-backed sync
- **Execution Model**: Async + isolates (non-blocking UI)

---

## Repository Structure (Conceptual)

This repository is organized into three main packages:

- **zapstore**
  - Flutter UI and application orchestration
  - Navigation, presentation, and user interaction

- **purplebase**
  - Local-first storage, indexing, and relay synchronization
  - Background work and isolate execution

- **models**
  - Pure domain models and utilities
  - Nostr kinds, parsing, signing, encryption, verification

Details and dependency rules are defined in `ARCHITECTURE.md`.

---

## How to Read the Specs (Important)

The following files define the actual guardrails of the project:

1. **VISION.md**
   - What Zapstore is and is not
2. **ARCHITECTURE.md**
   - Package responsibilities and dependency boundaries
3. **INVARIANTS.md**
   - Non-negotiable behavioral guarantees
4. **QUALITY_BAR.md**
   - Definition of acceptable work and anti-patterns

These files are human-owned and change slowly.

---

## What This File Is Not

- This file does **not** define invariants or rules
- This file does **not** describe UI flows or behavior
- This file does **not** override any foundation spec
- This file should remain small and stable

Its sole purpose is to provide initial context for humans and AI agents
before reading the foundation specifications.
