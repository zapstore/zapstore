# FEAT-006 - Device Key Architecture

## Goal

Provide one portable, encrypted device state that restores with either a copied
device nsec or Amber, while keeping private app stacks in Purplebase and all
device-only data local.

## Non-Goals

- Migrating prior `zapstore-settings`, `trusted-signers`, or capsule data
- Backing up NWC, operational state, or actual installed-package detection
- Continuous live sync or changing public community stacks
- A user-facing way to cancel bootstrap mining

## Data Model

- Secure storage keys:
  - `settings`: lower-camel-case portable JSON: `backgroundAutoUpdatesEnabled`,
    `installedAppsBackupEnabled`, and `trustedSigners`.
  - `temp_settings`: lower-camel-case device-only JSON, including `logLevel`,
    `lastAppOpened`, `seenUntil`, `deletionSyncedUntil`, and restore onboarding.
  - `nwc`: local-only NWC connection string.
  - `device_key`: local device nsec in hex.
  - `amber_pubkey`: local Amber reconnect identity.
- Device state: kind `30078`, `d=zapstore-device-state`, authored and signed by
  the device key; its content is the NIP-44 self-encrypted `settings` JSON.
- Amber key backup: kind `30078`, `d=zapstore-device-key-backup`, authored and
  signed by Amber; its content is NIP-44 self-encrypted JSON with
  `privateKeyHex`.
- Private Purplebase stacks remain kind `30267`, authored by the device key:
  `zapstore-bookmarks`, `zapstore-installed-apps`, and
  `zapstore-unmanaged-apps`.
- The device-owned AppCatalog relay list is kind `10067` and has no `d` tag.
- Purplebase stores the latest local-only, PoW-less draft for each pending
  device-key event. The drafts are never published to a relay.
- Secure storage records each pending event's kind and, when present, `d` tag
  in a list namespaced by the device pubkey derived from the active device key.
  This small marker survives process restart and identifies the latest local
  draft that must be mined and published.
- JSON uses lower camel case; Nostr tag values use kebab case.

## User-Visible Behavior

- A new device key does not publish an empty device-state event.
- A persistable device-state or private-stack change first saves its latest
  local-only PoW-less draft and records a secure-storage pending marker.
  Local persistence completes without waiting for mining or relay acceptance.
- The device-event queue rebuilds each marked draft as a 20-bit-PoW event in a
  background isolate. Private state and app stacks publish to AppCatalog;
  device relay lists publish to the bootstrap relay. Pending markers resume
  processing after process restart and when relay connectivity returns.
- A fresh install offers paste nsec, restore with Amber, or start fresh.
  If Amber is unavailable, its option opens the Amber install page.
- Pasted nsec or Amber recovery imports the device key, then fetches and
  decrypts device state and the three private stacks.
- On Amber sign-in during restore onboarding, a non-empty legacy
  `zapstore-installed-apps` stack is offered as “Restore apps from previous
  device.” The user selects apps to install; it is never mistaken for packages
  installed on this device or overwritten with an empty stack.
- SQLite remains a local-first cache. Offline restore and publishing show
  explicit retry/error states without preventing use of local data.

## PoW and Lifecycle

- Every device-key event submitted to a relay requires 20-bit NIP-13 proof of
  work, including device-state events, encrypted private app stacks, and the
  device-owned AppCatalog relay list.
  Purplebase-local drafts are the sole exception and must never be published.
- The Zapstore device-event queue uses secure-storage pending markers and
  Purplebase-local drafts. Relay failures retain the marker; replay is driven
  by app startup and connectivity/reconnection signals, never polling or
  artificial delays. A marker is cleared only after AppCatalog acceptance.
- Mining has no timeout or user cancellation. It runs outside the Flutter UI
  isolate and is cancelled when its owning service/provider is disposed to
  avoid a lifecycle leak. Cancellation leaves the local mutation intact and
  the operation eligible to be re-enqueued.
- The client verifies a restored event's signature and expected author; relay
  admission proof is not a client restore criterion.

## Upgrade from 1.0.6

- The release intentionally starts fresh: it does not migrate any prior secure
  storage, device key, Amber identity, private settings event, trusted signers,
  recovery capsules, or migration flags.
- Users lose NWC, both settings toggles, log level, update/notification and
  deletion cursors, trusted signers, old device nsec, and Amber reconnect state.
- Existing device-owned stacks are recoverable only when the user exported the
  old nsec before updating. Existing Amber-installed-app stacks remain
  discoverable after Amber sign-in during restore onboarding.
- Release notes and an in-app warning must explain the loss and tell users to
  copy their device nsec before updating.

## Acceptance Criteria

- [ ] A new device key publishes no empty bootstrap event
- [ ] Every device-key event submitted to a relay has at least 20 bits of valid
      NIP-13 proof of work; local drafts are never published
- [ ] Mining runs off the Flutter UI isolate and is lifecycle-safe
- [ ] Pending kind-and-identifier markers survive app restart and retry from
      their latest Purplebase-local drafts on startup or reconnection without
      polling
- [ ] Local state changes remain usable while proof mining or publishing is
      pending, cancelled, or failing
- [ ] Portable settings and trusted signers restore from `zapstore-device-state`
- [ ] NWC and all temp settings never appear in device-state
- [ ] Pasted-nsec and Amber restore both recover the same device state
- [ ] Amber backup contains only its self-encrypted `privateKeyHex`
- [ ] Legacy installed-app recovery offers selected installs without claiming
      those apps are locally installed
- [ ] New JSON and tag casing follows the defined convention
