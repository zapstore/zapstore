# WORK-027 — CDN Image Variants

**Feature:** (utility parity with webapp `image-url.js`; no FEAT required)
**Status:** Complete

## Tasks

- [x] 1. Add `getCdnImageUrl` matching webapp logic
  - Files: `lib/utils/image_url.dart`, `test/utils/image_url_test.dart`
- [x] 2. Apply variants at image load sites
  - App icons → `icon` / stack tiles → `iconsm`
  - Screenshot thumbs → `thumbsm`, lightbox → `thumblg`
  - Profile/avatar widgets apply icon/iconsm when on CDN (no-op otherwise)
- [x] 3. Self-review against INVARIANTS.md

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| CDN URL + variant | `?class=<variant>` appended | [x] |
| Existing query params | Preserved, `class` set | [x] |
| Non-CDN / relative / null | Unchanged | [x] |

## Decisions

### 2026-07-16 — Port webapp CDN class param

**Context:** Webapp requests sized variants via `?class=` on `cdn.zapstore.dev`.
**Decision:** Same helper and call-site mapping in the Flutter app.
**Rationale:** Smaller transfers for list icons and screenshot thumbs; no-op for other hosts.

## Progress Notes

Mirroring `webapp/src/lib/utils/image-url.js`.
