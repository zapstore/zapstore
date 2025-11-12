# Zapstore Architecture

This document explains how the Zapstore ecosystem components work together so contributors and app developers can extend the platform safely.

## High-level components

| Component | Repository | Purpose |
|-----------|------------|---------|
| Zapstore Android app | `zapstore/` | Flutter app, storefront UX, installer, trust surfaces, downloads assets published to relays. |
| Zapstore CLI | `zapstore-cli/` | Publisher workflow: parses `zapstore.yaml`, gathers metadata, uploads assets to Blossom, signs/publishes Nostr events. |
| Indexer & relays | `indexer/` + `relay.zapstore.dev` | Translate published events into queryable feeds that the Android client consumes. |
| Blossom CDN | `cdn.zapstore.dev` | Stores icons, screenshots, APKs, tarballs referenced by Nostr events. |

## End-to-end flow

```
Developer workstation
    |
    | (build assets, craft zapstore.yaml)
    v
Zapstore CLI
    |
    | -- uploads binaries/media --> Blossom server
    | -- signs app & release events --> relay.zapstore.dev
    v
Zapstore indexer / relays
    |
    | (APIs queried via purplebase models)
    v
Zapstore Android app
    |
    | -- verifies signatures & APK certs
    | -- optional Vertex DVM trust check
    v
User device installs / updates app
```

### Publishing path

1. **Metadata preparation**: Developers define assets and overrides inside `zapstore.yaml`. The CLI infers as much as possible (Android manifests, remote metadata, GitHub releases).
2. **Blossom uploads**: Local icons, screenshots, and binaries are uploaded to the configured Blossom server so URLs embedded in events remain stable.
3. **Signing**: Events are signed using the configured method (`SIGN_WITH` env var). Additional Blossom authorization events (kind 24242) are signed during uploads.
4. **Relay publication**: Signed events are pushed to `relay.zapstore.dev`. Today publication requires whitelisted pubkeys; future versions will broadcast to additional relays.

### Consumption path

1. **Data access**: The Flutter client relies on `purplebase` models in `lib/models/` and `lib/main.data.dart` to pull Nostr events. The derived adapters (e.g., `AppAdapter`, `FileMetadataAdapter`) normalize tags and guard against malformed data.
2. **Verification**: Before exposing an install/update button the client checks:
   - The signer is present and trusted (or the user explicitly opts in).
   - APK hashes match signatures stored in events.
   - If the app is already installed, certificates across versions match (`App.hasCertificateMismatch`).
   - Optional Vertex DVM trust lookups surface social context for unknown signers.
3. **Download & install**: Artifacts are pulled via `background_downloader`, verified, and installed through `install_plugin` guarded by a mutex so only one install runs at a time.
4. **Receipts & zaps**: After installation users can send zaps (Lightning payments) to developers; receipts reference the developer pubkey embedded in the app metadata.

## Key extension points

- **Publishers**: Extend `zapstore-cli` by adding new `AssetParser` subclasses or additional remote metadata sources when integrating new ecosystems.
- **Clients**: Most trust and install logic lives in `lib/models/app.dart` and `lib/screens/app_detail_screen.dart`. UI widgets such as `AppCard`, `SignerContainer`, and `DeveloperScreen` display signer info and trust cues.
- **Indexer**: If you want to mirror or self-host relays, see `indexer/` for the ingestion pipeline that hydrates purplebase.
- **Blossom integration**: Custom Blossom servers can be configured via `blossom_server` in `zapstore.yaml`, but ensure they support signing authorization events.

## Operational considerations

- **Key rotation**: Rotating signing keys produces new developer pubkeys and requires users to re-trust you. Communicate rotations via changelog notes and consider cross-signing events.
- **Relay diversity**: Today the mobile app prioritizes `relay.zapstore.dev`. Future work will expand to multiple relays; plan for replication or bridging if you operate your own infrastructure.
- **Asset immutability**: Because event metadata embeds URLs, treat uploaded assets as immutable. Re-uploading under the same URL after publishing can lead to hash mismatches on user devices.
- **Testing**: Use BrowserStack or local devices for end-to-end install testing. For the CLI, rely on the test fixtures under `zapstore-cli/test/assets` when adding parsers or metadata behaviors.

Refer back to this document whenever you add new distribution flows, integrate additional signing methods, or reason about how the CLI and Android client exchange data over relays.
