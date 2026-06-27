# FEAT-008 — Device Relay List

## Goal

Store the app-catalog relay list as a device-signed kind 10067 event while
keeping `wss://relay.zapstore.dev` as the hardcoded offline-safe default.

## Non-Goals

- Migrating relay settings or legacy Amber-signed kind 10067 events
- Private or encrypted relay entries
- Discovering kind 10067 events from any relay except the hardcoded default
- Applying relay changes without an app restart

## User-Visible Behavior

- The app starts with the latest accepted device-authored kind 10067 event from
  local storage, or the hardcoded default when no event exists.
- The app checks the hardcoded default relay for a newer device-authored event
  in the background without blocking local UI.
- A changed remote relay list is shown to the user and requires confirmation
  before the app clears cached data and restarts.
- Declining keeps the current relay list. The same change may be offered again
  after a later background check.
- Manual relay changes create a public kind 10067 event, signed by the device
  key, and use the same confirm-and-restart flow.
- Network errors leave the current/default relay list usable.

## Event Contract

- Kind: `10067` (replaceable)
- Author: the local device key
- Public relays: one `r` tag per normalized WebSocket URL
- Content: empty in this phase
- Bootstrap/query/publish relay: `wss://relay.zapstore.dev` only

## Edge Cases

- Missing, empty, malformed, or non-device-authored events are ignored.
- An empty relay list is invalid; the hardcoded default remains active.
- A fetched event is not applied before confirmation.
- Restart clears SQLite, so an accepted signed event is held temporarily in
  secure storage, restored into the fresh database, then removed.
- If the app is paused during a background check, the request is cancelled.
- A local event newer than the relay copy is republished on a later
  connectivity or lifecycle trigger; no polling is used.

## Acceptance Criteria

- [ ] Relay configuration no longer persists in `LocalSettings`.
- [ ] The hardcoded default renders and queries successfully offline.
- [ ] Only device-authored kind 10067 events can become active.
- [ ] Remote changes require confirmation before restart.
- [ ] Declining a change preserves the current relay list.
- [ ] Accepted events survive the clear-and-restart cycle without remaining in
      secure storage.
- [ ] Manual changes publish a device-signed event with public `r` tags.
- [ ] Background failure and cancellation are explicit and do not block UI.
