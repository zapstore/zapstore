# WORK-017 — Makefile Release Workflow

**Feature:** None
**Status:** Complete

## Tasks

- [x] 1. Move the release workflow from `tool/release.sh` into `Makefile`.
- [x] 2. Make `deploy` depend on `release` and `deploy-debug` depend on `debug`.
- [x] 3. Remove the superseded release script.
- [x] 4. Self-review against `spec/guidelines/INVARIANTS.md`.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| `make -n release` | Makefile parses the release recipe | [x] |
| `make -n deploy-debug` | Debug build is a prerequisite | [x] |
| Dependency inspection | `deploy: release`, `deploy-debug: debug` | [x] |
| Release failure before commit | Version and generated seed data are restored | [x] |

## Decisions

### 2026-07-12 — Keep release preparation under `make release`

**Context:** `tool/release.sh` bundled versioning, validation, seed generation, APK creation, commit, and tagging.
**Decision:** Preserve that workflow in the `release` target so the script has a complete Makefile replacement.
**Rationale:** Existing release behavior remains available through one documented command, while deploy targets can declare their build prerequisites.

## Spec Issues

_None_

## Progress Notes

**2026-07-12:** Migrated the release workflow, wired deploy dependencies, removed `tool/release.sh`, and validated execution with macOS BSD `make`.

## On Merge

Delete this work packet.
