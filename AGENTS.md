# AI Agents

This document is the entry point for AI assistants.
All behavioral authority lives in the project spec, not here.

If anything in this file conflicts with files under `spec/guidelines/`,
this file is wrong.

## What This Repository Is

Zapstore is a local-first, Nostr-native app store for Android.
It discovers, downloads, verifies, and installs APKs signed by developers the user trusts.

Users can support developers directly via Lightning zaps.

## Quick Reference

| What                    | Where                             |
| ----------------------- | --------------------------------- |
| Architecture & patterns | `spec/guidelines/ARCHITECTURE.md` |
| Non-negotiable rules    | `spec/guidelines/INVARIANTS.md`   |
| Quality standards       | `spec/guidelines/QUALITY_BAR.md`  |
| Product vision          | `spec/guidelines/VISION.md`       |
| Feature specs           | `spec/features/`                  |
| Active work             | `work/`                           |
| E2E testing workflow    | `test/TESTING.md`                 |
| Test specs              | `test/specs/`                     |
| Maestro flows           | `maestro/`                        |

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

| Path                | Owner  | AI May Modify                |
| ------------------- | ------ | ---------------------------- |
| `spec/guidelines/*` | Human  | No                           |
| `spec/features/*`   | Human  | No (unless explicitly asked) |
| `work/*.md`         | AI     | Yes                          |
| `lib/**`            | Shared | Yes                          |
| `test/**`           | Shared | Yes                          |
| `AGENTS.md`         | Human  | No                           |
| `test/TESTING.md`   | Human  | No                           |
| `test/specs/*`      | Human  | No (propose changes)         |
| `test/runs/*`       | Agent  | Yes                          |
| `test/reports/*`    | Agent  | Yes                          |
| `maestro/**`        | Shared | Yes                          |

## Working Rules

- Prefer small, localized changes. Avoid unrelated refactors.
- After dependency changes, run: `fvm flutter pub get`
- Fix any analyze/lint errors introduced by your changes.
- Assume Android as default target unless instructed otherwise.

## E2E Testing

Tests are agent-orchestrated: the agent handles setup, stateful checks, and
reporting while Maestro handles UI automation. Read `test/TESTING.md` first.

Key concept: **Agent-Verified Criteria** — Maestro is stateless, so cross-flow
checks (e.g., "badge count incremented by 1") are captured before/after by the
agent and reported alongside Maestro's own assertions. See the test spec template
at `test/specs/_TEMPLATE.md` for the format.
