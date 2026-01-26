# WORK-002 — NIP-88 Polls

**Feature:** FEAT-002-nip88-polls.md
**Status:** Complete

## Tasks

- [x] 1. Create Poll and PollResponse models in models package
  - Files: `models/lib/src/models/poll.dart`, `models/lib/src/models/poll_response.dart`
  - Notes: Kind 1068 for polls, kind 1018 for responses per NIP-88
- [x] 2. Register models in storage
  - Files: `models/lib/src/storage/storage.dart`
- [x] 3. Create PollsSection widget
  - Files: `lib/widgets/polls_section.dart`
  - Notes: Follows CommentsSection pattern
- [x] 4. Integrate into AppDetailScreen
  - Files: `lib/screens/app_detail_screen.dart`
- [x] 5. Add author filtering (app dev + zapstore team)
  - Files: `lib/widgets/polls_section.dart`
  - Notes: Convert npubs to hex for query filter
- [x] 6. Add poll creation for authorized users
  - Files: `lib/widgets/polls_section.dart`
  - Notes: Modal with question, options, poll type, expiration
- [x] 7. Fix hooks order mismatch in poll creation
  - Notes: Replace useListenable loop with useEffect listener pattern
- [x] 8. Fix duplicate author field in Poll model
  - Files: `models/lib/src/models/poll.dart`
  - Notes: Author field inherited from Model base class
- [x] 9. Self-review against INVARIANTS.md
- [x] 10. Add automated tests
  - Files: `test/widgets/polls_section_test.dart`
  - Notes: 15 unit tests for authorization, deduplication, vote counting, expiration

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Display polls for app | Polls from authorized authors shown | [x] Manual |
| Vote on single-choice poll | Selection replaces previous, vote submitted | [x] Manual |
| Vote on multi-choice poll | Can toggle multiple selections | [x] Manual |
| Expired poll | Shows "Ended" badge, voting disabled | [x] Manual + Unit |
| User already voted | Their selection highlighted | [x] Manual |
| Create poll as app dev | Modal opens, poll created and displayed | [x] Manual + Unit |
| Create poll as non-dev | Create button not shown | [x] Manual + Unit |
| Duplicate votes per pubkey | Only latest vote counted | [x] Manual + Unit |
| Poll with no votes | Shows "0 votes" | [x] Manual + Unit |
| Network failure on vote | Error shown, selection preserved | [ ] |
| Unsigned user tries to vote | Sign-in prompt shown | [x] Manual + Unit |
| Npub to hex conversion | Zapstore team npubs convert correctly | [x] Unit |

## Decisions

### 2026-01-26 — Poll author filtering

**Context:** Anyone could spam polls on app pages.
**Options:** No filtering, WoT filtering, explicit allowlist.
**Decision:** Explicit allowlist (app developer + zapstore team npubs).
**Rationale:** Simple, effective spam prevention. WoT can be added later.

### 2026-01-26 — Poll creation scope

**Context:** Initially planned as view/vote only.
**Options:** No creation, creation for anyone, creation for authorized users.
**Decision:** Creation for app developers on their apps + zapstore team on any app.
**Rationale:** Developers should be able to gather feedback on their own apps.

### 2026-01-26 — Models package location

**Context:** Poll/PollResponse models needed, models is external package.
**Options:** Add to zapstore directly, fork models, contribute to models.
**Decision:** Create feature branch in cloned models repo.
**Rationale:** Models belong in models package per architecture guidelines.

## Spec Issues

_None_

## Progress Notes

**2026-01-26:** Initial implementation complete with poll display, voting, and creation.
**2026-01-26:** Fixed hooks mismatch and author field double-initialization bugs.
**2026-01-26:** Fixed npub to hex conversion for query filter.
