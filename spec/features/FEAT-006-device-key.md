# FEAT-006 - Device Key Architecture

## Goal

Decouple private data (bookmarks, ignored apps, installed backup, settings) from
Amber sign-in by generating a local device key (nsec) that owns all private
encrypted events. Amber becomes purely the identity layer for public actions
(sharing stacks, zaps, web of trust).

## Non-Goals

- Multi-device sync without Amber (backup/restore requires one Amber sign-in)
- Migrating existing Amber-signed private stacks (users start fresh or restore)
- Changing how public stacks work (still Amber-signed with h tag)
- Implementing the publish queue (handled in purplebase)

## User-Visible Behavior

- On first launch, a device key is silently generated and stored in secure storage
- Bookmarks, ignored apps, and settings work immediately without sign-in
- Profile screen shows device key section with ability to copy nsec
- On first Amber sign-in, a dialog offers to:
  - Back up this device (stores device nsec inside encrypted `zapstore-settings`)
  - Restore from another device (if settings backups exist for this Amber key)
- Clearing app data (SQLite) does NOT delete device key or NWC string

## Data Model

- Device nsec: secure storage only (key: device_nsec)
- NWC string: secure storage only (existing key)
- Bookmarks: encrypted AppStack (30267), d=zapstore-bookmarks, signed by device key
- Ignored apps: encrypted AppStack (30267), d=zapstore-ignored-apps, signed by device key
- Installed backup: encrypted AppStack (30267), d=zapstore-installed-backup, signed by device key
- App settings: encrypted CustomData (30078), d=zapstore-settings
- Device backup: entries inside encrypted `zapstore-settings`, signed by Amber key

## Signer Roles

- Device signer (Bip340PrivateKeySigner): always available, never null. Signs all
  private events. Registered on boot, NOT set as active.
- Amber signer (AmberSigner): optional. When present, is the active signer. Used
  for public stacks, zaps, WoT queries.

## Filtering Strategy

- Public stacks: filtered by #h tag (community pubkey) naturally excludes device stacks
- Device private stacks: queried by authors: {devicePubkey} + specific #d tag
- appStackEventFilter schema filter removed; #h tag filtering is sufficient

## Edge Cases

- Device key lost (app uninstalled without backup): data unrecoverable, fresh start
- Amber uninstalled while backup dialog pending: backup deferred to next sign-in
- Restore on device that already has data: ask user to confirm (replace or keep current)
- Offline: events save locally, purplebase publish queue syncs when online
- Multiple devices with same Amber key: each has own device key; backup stores device name

## Acceptance Criteria

- [ ] Device key generated on first launch and persisted in secure storage
- [ ] Device key survives SQLite clear / app restart
- [ ] Bookmarks work without Amber sign-in
- [ ] Ignored apps work without Amber sign-in
- [ ] User can copy device nsec from profile screen
- [ ] First Amber sign-in triggers backup/restore dialog
- [ ] Backup encrypts device nsec inside `zapstore-settings` to Amber key
- [ ] Restore decrypts `zapstore-settings` and imports device nsec
- [ ] appStackEventFilter removed; queries use #h tag filtering
- [ ] EncryptableModel auto-decrypts device-key stacks (no manual decrypt calls)

## Phases

- A: Device key generation + service + registration at boot + copy nsec UI
- B: Migrate bookmarks/ignored/backup to device key (drop Amber requirement)
- C: Amber backup/restore dialog + CustomData events
- D: Remove appStackEventFilter, clean up sign-in gating in UI
