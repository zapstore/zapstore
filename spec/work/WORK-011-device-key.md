# WORK-011 - Device Key (Phase A + B + D)

**Feature:** FEAT-006-device-key.md
**Status:** In Progress

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
- [x] 5. Rewrite ignored apps to use device key
  - Files: `lib/services/ignored_apps_service.dart`, `lib/services/updates_service.dart`
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
  - Normalizes legacy installed/ignored d-tags to current identifiers
  - Marks migration complete per Amber pubkey + device pubkey; empty results retry
- [x] 9. Store device key backups in encrypted settings
  - Files: `lib/services/device_backup_service.dart`
  - Uses Amber-signed `CustomData` with `d=zapstore-settings`
  - Encrypts the full JSON settings object to the Amber key before signing
  - Stores device backup entries under `deviceBackups`
- [ ] 10. Self-review against INVARIANTS.md

## Decisions

### 2026-05-07 - Migrate Amber-authored private stacks

**Context:** Users may have bookmarks encrypted to their Amber key.
**Decision:** On Amber connection, migrate encrypted AppStacks authored by the Amber pubkey to equivalent device-key stacks.
**Rationale:** Private data should follow the new device-key ownership model without losing existing saved apps, installed-app backups, or ignored/unmanaged app state.

### 2026-05-07 - Synchronous providers for bookmarks/ignored

**Context:** Previously FutureProvider because of manual decrypt. Now EncryptableModel auto-decrypts.
**Decision:** Changed to synchronous Provider<Set<String>>.
**Rationale:** EncryptableModel.prepareAfterLoading runs in RequestNotifier before emission. By the time the provider reads the stack, privateAppIds is already decrypted.

### 2026-05-07 - #h tag replaces schemaFilter

**Context:** appStackEventFilter rejected encrypted stacks and stacks without app refs.
**Decision:** Removed entirely. Public stacks are identified by having '#h': {communityPubkey} tag.
**Rationale:** Per invariant "Encrypted stacks MUST NOT include a community h tag", filtering by #h naturally excludes all private stacks.

### 2026-05-07 - Device backups live inside settings

**Context:** Device key backup needs to be tied to the user's encrypted settings event, not a separate replaceable event.
**Decision:** Store backup entries inside the Amber-signed `CustomData` event with `d=zapstore-settings`, under the `deviceBackups` JSON key.
**Rationale:** Keeps backup state with settings and avoids a standalone `zapstore-device-backup` event. The entire settings JSON object is NIP-44 encrypted before signing.

## Spec Issues

- `spec/features/FEAT-006-device-key.md` still lists migration as a non-goal and uses legacy d-tags for installed/ignored apps. Implementation now follows the product direction from this work session: migrate private stacks to the device pubkey on Amber connection.

## Progress Notes

**2026-05-07:** Phase A, B, and D complete. Migration added for Amber-authored private stacks. Analysis could not be rerun in this sandbox because `fvm` and pub-cache reads are blocked.

**2026-05-07:** Restore ordering hardened: if an Amber backup contains other device keys, the restore/keep-current choice happens before migration and backup. Migration completion is keyed by both Amber pubkey and final device pubkey, and empty migration results are left retryable.

**2026-05-07:** Device key backup moved into the encrypted `zapstore-settings` CustomData event. No `zapstore-device-backup` event is written.
