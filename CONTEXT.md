# Zapstore — Project Context

This document is the entry point for AI assistants.
All behavioral authority lives in the project spec, not here.

If anything in this file conflicts with files under `spec/guidelines/`,
this file is wrong.

## What This Repository Is

Zapstore is a local-first, Nostr-native app store for Android.
It discovers, downloads, verifies, and installs APKs signed by developers the user trusts.

Users can support developers directly via Lightning zaps.

## Project Spec Structure

    spec/
      guidelines/           # Permanent rules (human-owned, never AI-modified)
        ARCHITECTURE.md     # Package boundaries, dependencies, key patterns
        INVARIANTS.md       # Non-negotiable behavioral guarantees
        QUALITY_BAR.md      # Standards, when to create specs
        VISION.md           # Product goals and non-goals

      features/             # Feature specs (behavioral contracts)
        _TEMPLATE.md        # Template with example
        FEAT-001-*.md       # Actual feature specs

    work/                   # Active work packets (temporary, delete after merge)
      _TEMPLATE.md          # Template with example
      WORK-001-*.md         # Actual work packets

## How to Work

1. Before implementing, check `spec/features/` for a relevant feature spec
2. For non-trivial work, create a work packet in `work/`
3. Every code change must trace to a task in the work packet
4. If a spec is unclear or incorrect, report a Spec Issue—do not guess

See `spec/guidelines/QUALITY_BAR.md` for what qualifies as "non-trivial."

## File Ownership

**Never modify** files in `spec/guidelines/`.
If a guideline seems wrong or incomplete, report it as a Spec Issue.

| Path | Owner | AI May Modify |
|------|-------|---------------|
| `spec/guidelines/*` | Human | No |
| `spec/features/*` | Human | No (unless explicitly asked) |
| `work/*.md` | AI | Yes |
| `lib/**` | Shared | Yes |
| `test/**` | Shared | Yes |
| `CONTEXT.md` | Human | No |

## Key Dependencies

- **models** / **purplebase**: Nostr SDK (local-first storage, relay sync, domain models).
  See package README in pub cache. Basic usage patterns in `spec/guidelines/ARCHITECTURE.md`.
- **amber_signer**: NIP-55 Android signer integration.
- **background_downloader**: Download management with pause/resume.

## Working Rules

- Prefer small, localized changes. Avoid unrelated refactors.
- After dependency changes, run: `fvm flutter pub get`
- Fix any analyze/lint errors introduced by your changes.
- Do not use polling or artificial `Future.delayed`; await Futures/Streams correctly.
- Keep `lib/widgets/common` generic and reusable.
- Assume Android as default target unless instructed otherwise.
