# Zapstore — Agent Instructions

Local-first, Nostr-native app store for Android.

All behavioral authority lives in `spec/guidelines/`. If this file conflicts, guidelines win.

## Quick Reference

| What | Where |
|------|-------|
| Architecture & patterns | `spec/guidelines/ARCHITECTURE.md` |
| Non-negotiable rules | `spec/guidelines/INVARIANTS.md` |
| Quality standards | `spec/guidelines/QUALITY_BAR.md` |
| Product vision | `spec/guidelines/VISION.md` |
| Feature specs | `spec/features/` |
| Active work | `spec/work/` |
| Decisions & learnings | `spec/knowledge/` |

Guidelines are symlinked into `.cursor/rules/` and auto-load.

## File Ownership

| Path | Owner | AI May Modify |
|------|-------|---------------|
| `spec/guidelines/*` | Human | No |
| `spec/features/*` | Human | No (unless asked) |
| `spec/work/*.md` | AI | Yes |
| `spec/knowledge/*.md` | AI | Yes |
| `lib/**`, `test/**` | Shared | Yes |

## Key Commands

```bash
fvm flutter pub get      # Dependencies
fvm flutter analyze      # Lint
fvm flutter test         # Tests
```

## Project Rules

- Assume Android as default target unless instructed otherwise.
