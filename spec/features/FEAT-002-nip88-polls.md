# FEAT-002 — NIP-88 Polls

## Goal

Allow app developers to create polls and gather feedback from users directly on app detail pages, enabling community-driven feature prioritization.

## Non-Goals

- Displaying polls outside of app detail screens
- Poll moderation beyond author filtering (app developer + zapstore team only)
- Weighted voting or WoT-based result curation (can be added later)
- Real-time vote count updates (local-first, refresh-based)

## User-Visible Behavior

### Poll Display

- Polls section appears on app detail screen (between existing sections)
- Each poll shows: question, options with vote counts, total votes, end date (if set)
- Expired polls show results but disable voting
- Active polls allow user to vote (if signed in)
- User's existing vote is highlighted

### Voting Flow

- Tapping an option (when signed in) casts vote
- Single-choice polls: selecting new option replaces previous vote
- Multi-choice polls: can select multiple options, can toggle selections
- Vote submission shows loading state, then success/error feedback
- User must be signed in to vote (prompt sign-in if not)

### Poll Creation

- App developers can create polls on their own app pages
- Zapstore team members can create polls on any app page
- Create button opens modal with:
  - Question input
  - Dynamic option list (2-10 options)
  - Poll type selector (single/multiple choice)
  - Optional expiration (1/3/7/14/30 days)
- Poll signed via Amber and published to social relays

### States

- **Loading**: Skeleton while fetching polls
- **Empty**: "No polls yet" message (minimal, non-intrusive)
- **Success**: Polls displayed with vote counts
- **Error**: Brief error message with retry option
- **Offline**: Show cached polls (if any), voting disabled with explanation

## Edge Cases

- Poll has no votes yet → show "0 votes" per option
- Poll expired → show "Ended" badge, disable voting, still show results
- User already voted → highlight their selection, allow changing vote (new event)
- Multiple votes from same pubkey → display only latest vote per pubkey
- Poll missing required tags → skip gracefully, don't crash
- Invalid option IDs in response → ignore invalid responses
- Network failure during vote → show error, allow retry, don't lose selection state

## Acceptance Criteria

- [x] Polls section visible on app detail screen when polls exist
- [x] Poll question and options display correctly
- [x] Vote counts show correctly (one vote per pubkey)
- [x] User can vote on active polls when signed in
- [x] User's existing vote is visually indicated
- [x] Expired polls show results but disable voting
- [x] Vote submission shows loading → success/error feedback
- [x] Unsigned users see prompt to sign in when attempting to vote
- [x] Empty state shown gracefully when no polls exist
- [x] Only polls from app developer or zapstore team are displayed
- [x] App developers can create polls on their own app pages
- [x] Zapstore team can create polls on any app page
- [ ] Offline: cached polls display, voting disabled with explanation

## Technical Notes

### Nostr Event Kinds

- **kind:1068** — Poll event
  - `content`: poll question
  - `option` tags: `["option", "<id>", "<label>"]`
  - `polltype` tag: `singlechoice` (default) or `multiplechoice`
  - `endsAt` tag: optional unix timestamp
  - Tagged to app via `#a` or `#A` tag (TBD based on convention)

- **kind:1018** — Poll response event
  - `e` tag: references poll event ID
  - `response` tags: `["response", "<option_id>"]`

### Query Pattern

```dart
// Query polls for an app
ref.watch(
  query<Poll>(
    tags: {'#a': {app.aTag}},  // or '#A' depending on convention
    source: LocalAndRemoteSource(relays: 'social', stream: true),
  ),
);

// Query responses for a poll
ref.watch(
  query<PollResponse>(
    tags: {'#e': {poll.id}},
    source: LocalAndRemoteSource(relays: 'social', stream: true),
  ),
);
```

### Dependencies

- May require Poll/PollResponse models in `models` package
- Uses existing Amber signing flow
- Uses `social` relay group

## Files (Expected)

- `lib/widgets/polls_section.dart` — Main polls UI widget
- `lib/widgets/poll_card.dart` — Individual poll display (optional, could be inline)
- Models in external `models` package (if not already present)
