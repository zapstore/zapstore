# FEAT-006 - Device Key Architecture

## Goal

Decouple private data (bookmarks, unmanaged apps, installed backup, settings) from
Amber sign-in by generating a local device key (nsec) that owns all private
encrypted events. Every private kind 30267 and 30078 event is signed by the
device key and carries NIP-13 proof of work. Amber is only the identity and
recovery layer for public actions and encrypted device-key backup capsules.

## Non-Goals

- Continuous or multi-device live sync
- Migrating legacy Amber-signed `zapstore-settings` events
- Changing how public stacks work (still Amber-signed with h tag)
- Implementing the publish queue (handled in purplebase)

## User-Visible Behavior

- On first launch, a device key is silently generated and stored in secure storage
- Bookmarks, unmanaged apps, and settings work immediately without sign-in
- Private data renders from SQLite; one non-streaming relay sync runs at app boot
- Profile screen shows device key section with ability to copy nsec
- On every Amber sign-in, the app performs one-shot recovery and legacy queries:
  - Restore is offered when device-signed settings events contain a recovery
    capsule for the Amber key
  - The final device key is backed up in its device-signed settings event
  - Amber-authored encrypted AppStacks are merged into device-owned stacks
- Clearing app data (SQLite) does NOT delete device key or NWC string
- Background sync, migration, and proof-of-work jobs are bounded and cancellable

## Data Model

- Device nsec: secure storage only (`zapstore_secure_prefs` JSON blob, field `nsec`, hex)
- NWC string: secure storage only (existing key)
- Bookmarks: encrypted AppStack (30267), d=zapstore-bookmarks, signed by device key
- Unmanaged apps: encrypted AppStack (30267), d=zapstore-unmanaged-apps, signed by device key
- Installed backup: encrypted AppStack (30267), d=zapstore-installed-apps, signed by device key
- App settings: versioned private CustomData (30078), d=zapstore-settings,
  signed by the device key
- Device backup: NIP-44 recovery capsules encrypted from the device key to Amber
  identities inside `zapstore-settings`; matching `p` tags allow discovery.
  Each capsule includes an Amber-signed authorization binding that identity to
  the device pubkey, preventing third-party recovery-candidate injection.
- Trusted signers: encrypted, local-only CustomData (30078), d=trusted-signers,
  signed by the device key
- Every private event commits to at least 16 bits of NIP-13 proof of work

## Signer Roles

- Device signer (Bip340PrivateKeySigner): always available, never null. Signs all
  private events. Registered on boot, NOT set as active.
- Amber signer (AmberSigner): optional. When present, is the active signer. Used
  for public stacks, zaps, WoT queries, and recovery-capsule encryption/decryption.
- NIP-13 mining runs in a Purplebase-owned worker isolate after encryption and
  before signing; no mining loop runs on Flutter's main isolate.

## Filtering Strategy

- Public stacks: filtered by #h tag (community pubkey) naturally excludes device stacks
- Device private stacks: queried by authors: {devicePubkey} + specific #d tag
- appStackEventFilter schema filter removed; #h tag filtering is sufficient
- Device private 30267/30078 events are fetched once with stream=false at boot.
  Private UI consumers use LocalSource only.
- Amber sign-in recovery and migration are explicit one-shot exceptions; they
  never open streaming subscriptions.

## Edge Cases

- Device key lost (app uninstalled without backup): data unrecoverable, fresh start
- Amber uninstalled while backup dialog pending: backup deferred to next sign-in
- Restore on device that already has data: ask user to confirm (replace or keep current)
- Offline: events save locally, purplebase publish queue syncs when online
- Multiple devices with same Amber key: each has own device key; backup stores device name
- Existing device-authored events without PoW are re-signed in the background;
  invalid or undecryptable events are never overwritten.
- Legacy Amber-authored settings are ignored even if that loses old backups.
- Migration is idempotent and rechecks on every Amber sign-in; failed decrypts
  remain retryable.

## Acceptance Criteria

- [ ] Device key generated on first launch and persisted in secure storage
- [ ] Device key survives SQLite clear / app restart
- [ ] Bookmarks work without Amber sign-in
- [ ] Unmanaged apps work without Amber sign-in
- [ ] User can copy device nsec from profile screen
- [ ] First Amber sign-in triggers backup/restore dialog
- [ ] Every Amber sign-in upserts a recovery capsule in device-signed settings
- [ ] Restore discovers device-signed settings by Amber `p` tag and imports nsec
- [ ] Legacy Amber-signed settings are ignored
- [ ] Amber private bookmarks, installed backups, unmanaged apps, and other
      encrypted AppStacks migrate safely to the final device key
- [ ] All new private 30267/30078 events have valid 16-bit NIP-13 proof of work
- [ ] PoW mining does not run on Flutter's main isolate
- [ ] Private relay reads happen only at boot and explicit Amber sign-in recovery
- [ ] appStackEventFilter removed; queries use #h tag filtering
- [ ] EncryptableModel auto-decrypts device-key stacks (no manual decrypt calls)

## Phases

- A: Device key generation + service + registration at boot + copy nsec UI
- B: Migrate bookmarks/unmanaged/backup to device key (drop Amber requirement)
- C: Amber backup/restore dialog + CustomData events
- D: Remove appStackEventFilter, clean up sign-in gating in UI
