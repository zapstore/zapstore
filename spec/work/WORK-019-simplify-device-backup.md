# WORK-019 - Simplify Device Backup

**Feature:** FEAT-006-device-key.md
**Status:** In Progress

## Tasks

- [x] 1. Replace legacy secure-storage layouts
  - Files: `lib/services/settings_service.dart`,
    `lib/services/device_key_service.dart`
  - Create the `settings`, `temp_settings`, `nwc`, `device_key`, and
    `amber_pubkey` layout; intentionally do not migrate existing values.
- [x] 2. Create portable device-state persistence
  - Files: `lib/services/device_private_event_service.dart`,
    `lib/services/device_private_sync_service.dart`, new state service
  - Persist portable state locally, self-encrypt and publish
    `30078/zapstore-device-state`, and fold in trusted signers.
- [x] 3. Implement bootstrap admission
  - Files: `lib/main.dart`, device-state/private-event services, device-key UI
  - Mine the first empty device-state event at 28-bit PoW in the background
    isolate; queue later private publications, disable settings until accepted,
    and cancel only on lifecycle disposal.
- [x] 4. Replace capsule recovery with Amber key backup
  - Files: `lib/services/device_backup_service.dart`,
    `lib/widgets/device_backup_dialog.dart`, `lib/screens/profile_screen.dart`
  - Publish Amber-authored `30078/zapstore-device-key-backup`, restore via
    Amber or pasted nsec, and route absent Amber to its install page.
- [x] 5. Add legacy installed-app recovery
  - Files: app restore UI, package/install orchestration, `temp_settings`
  - During incomplete restore onboarding and Amber sign-in, offer apps from a
    non-empty legacy Amber `zapstore-installed-apps` stack without treating
    them as installed or overwriting them with an empty device stack.
- [x] 6. Remove obsolete paths
  - Delete capsule authorization, recovery-candidate, trusted-signers event,
    old settings-event, and migration-marker logic.
- [x] 7. Add release warning and tests
  - Warn current users that all old secure storage is discarded and nsec must be
    copied before upgrade. Cover schema, restore, bootstrap, offline/error,
    lifecycle cancellation, Amber-install routing, and recovery-install UI.
- [ ] 8. Self-review against INVARIANTS.md

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| New key bootstrap | Empty state is first accepted private event | [ ] |
| Bootstrap pending | Settings disabled; local UI remains responsive | [ ] |
| Bootstrap lifecycle disposal | Mining isolate is cancelled without leaking | [ ] |
| Settings publish failure | Local portable state remains available; retry is explicit | [ ] |
| Pasted nsec restore | Signature-verified state and stacks restore | [ ] |
| Amber restore | Verified Amber backup imports the same nsec | [ ] |
| Amber absent | Restore UI opens Amber install page | [ ] |
| Legacy installed backup | User selects apps to install; none are falsely installed | [ ] |
| NWC/temp exclusion | Neither is serialized to device state | [ ] |
| 1.0.6 update | Old values are discarded after explicit warning | [ ] |

## Decisions

### 2026-07-14 - Fixed bootstrap proof

**Context:** Per-update proof mining makes normal settings changes expensive.
**Options:** Mine every replacement; await a relay proof challenge; bootstrap once.
**Decision:** Mine an empty device-state event at fixed 28-bit difficulty before
any remote private write, then rely on relay admission and rate limiting.
**Rationale:** It gives the relay a simple first-event cost while ordinary
settings writes remain local-first and cheap.

### 2026-07-14 - No user cancellation or timeout

**Context:** Bootstrap is setup work, not an interruptible user operation.
**Decision:** Show indefinite progress without timeout or a cancel button.
**Rationale:** Avoids retry and partial-state UI complexity. The owning
service/provider still cancels the isolate on disposal, as required for
lifecycle safety.

## Spec Issues

_None_

## Progress Notes

**2026-07-14:** Contract rewritten for a clean-break migration. Relay changes
are tracked separately; this work assumes it admits the first private event
only when its NIP-13 difficulty is at least 28 bits.

**2026-07-14:** Implemented separate secure-storage entries, portable
device-state persistence, 28-bit bootstrap admission, nsec parsing, and the
Amber-authored key-backup record. Verification is blocked because the checked
out workspace has no `../purplebase` path dependency.
