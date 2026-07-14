# WORK-011 - Device Key (Phase A + B + D)

**Feature:** FEAT-006-device-key.md
**Status:** Complete

## Tasks

- [x] 1. Create DeviceKeyService
  - Files: `lib/services/device_key_service.dart`
  - Generates/loads key from FlutterSecureStorage
  - Exposes devicePubkeyProvider (StateProvider)
- [x] 2. Register device signer at boot
  - Files: `lib/main.dart`
  - In storageReadyProvider, after SQLite init
  - signIn(setAsActive: false), sets devicePubkeyProvider
- [x] 3. Device key UI in profile screen
  - Files: `lib/screens/profile_screen.dart`
  - Shows truncated npub, copy private key with warning dialog
- [x] 4. Rewrite bookmarks to use device key
  - Files: `lib/services/bookmarks_service.dart`, `lib/widgets/app_detail_widgets.dart`, `lib/widgets/floating_overflow_menu.dart`
  - Changed from FutureProvider to synchronous Provider
  - No manual nip44Decrypt calls (EncryptableModel auto-decrypts)
  - No sign-in gate; always available
- [x] 5. Rewrite unmanaged apps to use device key
  - Files: `lib/services/unmanaged_apps_service.dart`, `lib/services/updates_service.dart`
  - Changed from FutureProvider to synchronous Provider
  - No sign-in gate
- [x] 6. Remove sign-in gate from SaveAppDialog
  - Files: `lib/widgets/bookmark_widgets.dart`
  - Removed SignInPrompt, uses device signer directly
- [x] 7. Remove appStackEventFilter, use #h tag filtering
  - Files: `lib/constants/app_constants.dart`, `lib/screens/user_screen.dart`, `lib/widgets/app_stack_container.dart`, `lib/widgets/bookmark_widgets.dart`
  - Removed filter function entirely
  - Public stack queries now use '#h': {kZapstoreCommunityPubkey} tag
- [x] 8. Migrate Amber private stacks on sign-in
  - Files: `lib/services/device_backup_service.dart`, `lib/services/device_key_service.dart`
  - Offers restore before migration so the final device key is chosen first
  - Queries Amber-authored encrypted AppStacks after Amber connection
  - Merges them into device-authored encrypted stacks using the device signer
  - Normalizes legacy installed/unmanaged d-tags to current identifiers
  - Rechecks idempotently on every sign-in; failed decrypts remain retryable
- [x] 9. Store device key backups in encrypted settings
  - Files: `lib/services/device_backup_service.dart`
  - Uses device-signed `CustomData` with `d=zapstore-settings`
  - Stores NIP-44 Amber recovery capsules authorized by Amber signatures
  - Ignores legacy Amber-signed settings
- [x] 10. Self-review against INVARIANTS.md
- [x] 11. Centralize private 30267/30078 signing
  - Device signer only, encrypted-stack h-tag rejection, 16-bit NIP-13
  - Purplebase worker executor keeps mining off Flutter's main isolate
- [x] 12. Add boot-only private event synchronization
  - One cancellable stream=false request for device-authored 30267/30078
  - Private consumers use LocalSource only
- [x] 13. Replace Amber-owned settings backup
  - Device-signed `zapstore-settings` with Amber recovery capsules and p tags
  - Ignore all legacy Amber-signed settings
- [x] 14. Repair Amber migration
  - Explicitly decrypt Amber stacks before merge
  - Cover bookmarks, installed, unmanaged, and arbitrary encrypted stacks
  - Recheck idempotently on every Amber sign-in
- [x] 15. Move trusted-signers CustomData to device ownership
- [x] 16. Consolidate bookmark writes and private stack reads
- [x] 17. Add lifecycle, failure, migration, and PoW tests

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Device private write | Device author, valid PoW, local save | [x] |
| Invalid private policy | Unknown 30078 and encrypted h-tag rejected | [x] |
| PoW cancellation | Worker terminates and tags remain unchanged | [x] |
| Boot sync lifecycle | Single-flight and cancellable | [x] |
| Bookmark write overlap | Serialized monotonic replacements | [x] |
| Bookmark save failure | Optimistic state rolls back | [x] |
| Amber bookmark migration | Imperative result explicitly decrypts | [x] |
| Recovery injection | Amber authorization required | [x] |
| Recovery cancellation | No work restarts after cancellation | [x] |
| Full verification | App, models, and Purplebase suites pass | [x] |

## Decisions

### 2026-05-07 - Migrate Amber-authored private stacks

**Context:** Users may have bookmarks encrypted to their Amber key.
**Decision:** On Amber connection, migrate encrypted AppStacks authored by the Amber pubkey to equivalent device-key stacks.
**Rationale:** Private data should follow the new device-key ownership model without losing existing saved apps, installed-app backups, or unmanaged app state.

### 2026-05-07 - Synchronous providers for bookmarks/unmanaged apps

**Context:** Previously FutureProvider because of manual decrypt. Now EncryptableModel auto-decrypts.
**Decision:** Changed to synchronous Provider<Set<String>>.
**Rationale:** EncryptableModel.prepareAfterLoading runs in RequestNotifier before emission. By the time the provider reads the stack, privateAppIds is already decrypted.

### 2026-05-07 - #h tag replaces schemaFilter

**Context:** appStackEventFilter rejected encrypted stacks and stacks without app refs.
**Decision:** Removed entirely. Public stacks are identified by having '#h': {communityPubkey} tag.
**Rationale:** Per invariant "Encrypted stacks MUST NOT include a community h tag", filtering by #h naturally excludes all private stacks.

### 2026-05-07 - Device backups live inside settings

**Status:** Superseded by the 2026-07-13 device-settings decision below.
**Context:** Device key backup needs to be tied to the user's encrypted settings event, not a separate replaceable event.
**Decision:** Store backup entries inside the Amber-signed `CustomData` event with `d=zapstore-settings`, under the `deviceBackups` JSON key.
**Rationale:** Keeps backup state with settings and avoids a standalone `zapstore-device-backup` event. The entire settings JSON object is NIP-44 encrypted before signing.

### 2026-07-13 - All private events are device-owned

**Context:** Amber ownership made private state depend on the current identity
and allowed private write paths to drift.
**Decision:** Every private kind 30267/30078 event is device-signed. Public
30267 stacks remain Amber-signed and carry the community h tag.
**Rationale:** The device key is always available and is the stable owner of
per-device state.

### 2026-07-13 - Device settings contain recovery capsules

**Context:** Amber must recover a device nsec without authoring the settings
event or owning device settings.
**Decision:** `zapstore-settings` is signed by the device key. Its versioned
envelope stores device-private data plus NIP-44 capsules addressed to Amber
keys and indexed by p tags. Capsules include an embedded Amber-signed
authorization binding the Amber identity to the device pubkey.
**Rationale:** Settings remain per-device while Amber remains an orthogonal
recovery recipient, while copied or attacker-created capsules cannot inject a
device key that Amber never authorized.

### 2026-07-13 - Boot-only relay ingestion

**Context:** Private state changes originate on the device and live
subscriptions waste relay and lifecycle resources.
**Decision:** Fetch device-authored private events once at boot with
`stream:false`; UI providers are local-only. Amber recovery/migration is the
only sign-in-time one-shot exception.
**Rationale:** Preserves local-first rendering and avoids duplicate or leaked
subscriptions.

### 2026-07-13 - 16-bit off-isolate proof of work

**Context:** Private events need NIP-13 without running CPU-bound mining on
Flutter's main isolate.
**Decision:** Apply 16-bit PoW through a cancellable Purplebase worker executor.
**Rationale:** 16 bits is a practical mobile spam cost; worker execution keeps
rendering responsive.

## Spec Issues

_None_

## Progress Notes

**2026-05-07:** Phase A, B, and D complete. Migration added for Amber-authored private stacks. Analysis could not be rerun in this sandbox because `fvm` and pub-cache reads are blocked.

**2026-05-07:** Restore ordering hardened: if an Amber backup contains other device keys, the restore/keep-current choice happens before migration and backup. Migration completion is keyed by both Amber pubkey and final device pubkey, and empty migration results are left retryable.

**2026-05-07:** Device key backup moved into the encrypted `zapstore-settings` CustomData event. No `zapstore-device-backup` event is written.

**2026-07-13:** FEAT-006 was updated by explicit product authorization. The new
contract supersedes the earlier Amber-signed settings decision and ignores
legacy Amber settings even when that loses old backups.
