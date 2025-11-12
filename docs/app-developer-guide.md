# Zapstore App Developer Guide

This guide targets Android and CLI app developers who want to distribute software through Zapstore. It links the two key components of the ecosystem:

- **Zapstore mobile app** (`zapstore/`): the user-facing Android storefront built with Flutter.
- **Zapstore CLI publisher** (`zapstore-cli/`): the tool you use to package releases, upload assets, and publish signed events to relays.

If you are new to Zapstore, skim this document once, then keep it handy as a reference when preparing releases.

## 1. Conceptual overview

1. You prepare release assets (APK or archives) plus metadata in a `zapstore.yaml`.
2. The Zapstore CLI parses the assets, pulls optional remote metadata (GitHub, Play Store, etc.), uploads binaries/media to a Blossom server, signs Nostr events, and publishes them to `relay.zapstore.dev`.
3. The Zapstore Android app indexes those relays, verifies signatures and certificates, and exposes your app to end users with install/update flows, certificate mismatch warnings, and optional Vertex DVM trust checks.

This workflow is intentionally decentralized: you control signing keys, assets are hosted on Blossom/CDN endpoints, and relays propagate signed events.

## 2. Prerequisites

| Area | Requirements |
| ---- | ------------ |
| Flutter & Android | Flutter 3.3+ (`flutter doctor` clean), Android SDK/NDK, JDK 17, physical or virtual device for testing installs. |
| CLI tooling | Dart SDK (stable channel) when building CLI from source, or the published binaries from `zapstore.dev/download`. |
| Signing keys | Nostr key (nsec/hex) or access to a NIP-07 extension, NIP-46 bunker URL, or a custom signer that can consume unsigned events. |
| Blossom access | Default Blossom server `https://cdn.zapstore.dev` is public; custom servers require kind 24242 signing approval. |
| Relay access | Your pubkey must be whitelisted on `relay.zapstore.dev` to publish. Request access via nostr before running `zapstore publish`. |

## 3. Publishing workflow

### 3.1 Prepare artifacts and metadata

1. Build your Android APK or CLI binary/archives using your normal release pipeline.
2. Create (or update) `zapstore.yaml` in the release folder. At minimum you **must** declare an `assets` list with regex paths to the binaries you want to ship.
3. Fill in developer-facing details (name, summary, description, homepage) plus optional overrides such as `identifier`, `tags`, `license`, and image/icon paths. Remember that local asset paths are relative to the config file directory.

### 3.2 Configure signing

Zapstore requires every published event to be signed. Export your chosen method into the `SIGN_WITH` env var:

```bash
# Examples
SIGN_WITH=nsec1...
SIGN_WITH=176fa8c7a988df...
SIGN_WITH=NIP07
SIGN_WITH=bunker://...  # NIP-46
```

When using `.env`, ensure it is secured. If you prefer not to store secrets, prefix the command with a space so shells skip history logging:

```bash
 SIGN_WITH=176fa8c... zapstore publish
```

### 3.3 Run the CLI

Run the publisher from the folder that contains `zapstore.yaml`:

```bash
zapstore publish \
  --config ./zapstore.yaml \
  --overwrite-app \
  --overwrite-release
```

- `--overwrite-app` avoids refetching metadata once your store entry matches expectations.
- `--overwrite-release` lets you republish the latest release if assets changed.
- Use `--indexer-mode` for CI/CD (non-interactive, no spinners).

During publishing the CLI:

1. Resolves metadata (local/GitHub/Web parsers).
2. Uploads icons, screenshots, and binaries to Blossom (if they’re local files).
3. Emits signed Nostr events (app + release kinds).
4. Pushes events to `relay.zapstore.dev`.

### 3.4 Validate on devices

1. Install a developer build of the Zapstore Android app (`flutter build apk --split-per-abi --debug`).
2. Sign in with NIP-55 and ensure your developer pubkey appears on the Developer screen.
3. Locate your newly published app:
   - Confirm metadata renders correctly (name, summary, screenshots).
   - Install/update flows should succeed; certificate mismatch warnings indicate the uploaded APK signature hash differs from what the store previously saw.
4. Test zap receipts and Vertex DVM trust prompts by attempting a zap from a fresh user account.

## 4. Trust & safety expectations

- **Signers**: Every release ties to a signer pubkey. Keep your signing key secure; rotating keys means users must re-trust you.
- **Web of trust**: When users install from unknown signers, the mobile app can run a Vertex DVM check to surface social proofs. Developers can skip DVM lookups locally by omitting `SIGN_WITH` (not recommended for production).
- **Certificates**: Zapstore compares APK signing certificates between releases. If you upload an APK signed by a different keystore, users receive a “certificate mismatch” warning and installs are blocked until they uninstall and reinstall. Document any intentional key rotations in your release notes.
- **Blossom uploads**: Assets hosted on Blossom are referenced directly in the signed events. If you delete or move assets after publishing, users will see download failures. Always keep Blossom files immutable per release.

## 5. Troubleshooting

| Symptom | Resolution |
| ------- | ---------- |
| `zapstore publish` exits with “pubkey not allowed” | Ensure your signer npub is whitelisted on `relay.zapstore.dev`. Contact Zapstore via nostr to request approval. |
| Blossom upload fails with 403 | You must sign kind 24242 authorization events. Double-check `SIGN_WITH`, Blossom server URL, and that your account has upload permissions. |
| CLI can’t find assets | Verify regex paths in `zapstore.yaml` include folder separators for local assets. Remember the parser treats entries without `/` as remote GitHub assets. |
| Vertex DVM check blocks install | When testing, set `-t` or clear `SIGN_WITH` to skip trust checks. For production, encourage early adopters to follow your signer or share proofs. |
| Mobile app build fails | Confirm Flutter 3.3+ and run `flutter pub get` followed by `flutter doctor -v`. If issues persist, compare versions against `pubspec.yaml`. |

## 6. Additional resources

- `zapstore/README.md`: build, contribution, and testing notes for the Flutter app.
- `zapstore-cli/README.md`: installation, command reference, and `zapstore.yaml` schema.
- `CHANGELOG.md` in each repo: platform changes that might affect publishing or client behavior.

Need more help? Reach out via nostr (`npub.world/<your-npub>`) or file an issue in the relevant repository.
