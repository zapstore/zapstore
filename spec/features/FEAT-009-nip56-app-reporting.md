# FEAT-009 — NIP-56 App Reporting

## Goal

Let signed-in users publish a NIP-56 report when an app listing violates
Zapstore's reporting policy, so malicious or deceptive listings can be
identified without making reporting a prominent app-detail action.

## Non-Goals

- App reviews, dissatisfaction, or requests for support
- Automatic moderation, warning labels, or removal of reported apps
- Publishing reports outside the AppCatalog relay group
- Reporting individual APK blobs or releases

## User-Visible Behavior

- The app-detail overflow menu includes a de-emphasized **Report app** action.
- The report sheet explains that reports are public, signed Nostr events and
  are only for policy violations.
- The user must choose a violation category and describe the specific
  violation before publishing.
- A report targets the app listing event and its event author, and is
  published only to `AppCatalog`.
- The sheet shows a publishing state, a success confirmation, and a clear
  failure with a retry path that preserves the entered report.
- A signed-in Nostr identity is required. The report flow must not use the
  device key.

## Edge Cases

- Signing or relay publishing fails: retain the selected category and
  description, then show an actionable error and permit retry.
- No active signer: explain that Amber sign-in is required and do not create
  or publish an event.
- Unknown or malformed app event data: do not offer a report if the event ID
  or author pubkey is missing or invalid.
- The sheet can be dismissed while no publish is in progress; it cannot be
  dismissed through the submit action while publish is in progress.

## Acceptance Criteria

- [ ] Users can publish a valid NIP-56 kind `1984` report for an app listing.
- [ ] Every report has a supported violation type and non-empty description.
- [ ] Reports publish only through the `AppCatalog` relay group using the
  active user signer.
- [ ] Signing and publish failures are visible, recoverable, and do not
  discard form input.
- [ ] Valid NIP-56 three-element report tags parse their violation type.
