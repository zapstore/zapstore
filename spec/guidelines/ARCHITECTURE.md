# Zapstore — Architecture

## Core Principle

Architecture exists to prevent accidental coupling and hidden ownership.
Each layer has clear responsibilities and must not exceed them.

## Layers

### zapstore (this Flutter app)

- Flutter UI and application orchestration
- Navigation, presentation, and user interaction
- Coordinates use cases across dependencies
- Must not contain domain rules or persistence logic

### Dart dependencies

#### models

- Domain models for Nostr events, kinds, zaps, releases, and NWC
- Parsing, validation, signing, encryption, and verification
- Pure domain logic only
- Must not depend on storage, networking, isolates, or UI

#### purplebase

- Local-first storage and indexing (SQLite)
- Relay synchronization and subscription lifecycle management
- Background work and isolate execution
- Must not depend on UI or presentation logic

## Dependency Rules

- zapstore → purplebase → models
- Reverse dependencies are forbidden
- UI widgets must not manage relay connections, storage, or background jobs

## Ownership & Orchestration

- Relay pools and subscriptions are owned by purplebase
- Background work lifecycle is explicit and cancellable
- zapstore orchestrates flows but does not own low-level resources

## Common Patterns

### Widget watching data

```dart
// Watch a query provider — reactive, auto-disposes
final state = ref.watch(
  query<Profile>(
    authors: {pubkey},
    source: const LocalAndRemoteSource(relays: {'social'}),
  ),
);

return switch (state) {
  StorageLoading() => CircularProgressIndicator(),
  StorageError(:final exception) => Text('Error: $exception'),
  StorageData(:final models) => ProfileWidget(models.first),
};

// Nested queries with `and` — loads relationships
final appState = ref.watch(
  query<App>(
    tags: {'#d': {identifier}},
    and: (app) => {
      app.latestRelease.query(
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        and: (release) => {release.latestMetadata.query()},
      ),
    },
    source: const LocalAndRemoteSource(relays: 'AppCatalog'),
    subscriptionPrefix: 'app-detail',
  ),
);
```

### Imperative queries (notifiers/services)

```dart
// One-shot query via storage extension
final apps = await ref.storage.query(
  RequestFilter<App>(authors: {pubkey}, limit: 20).toRequest(),
);
```

### Saving and publishing (from callbacks)

```dart
onPressed: () async {
  await ref.storage.save({signedModel});
  await ref.storage.publish({signedModel});
}
```

### Subscription prefix naming

All queries using the `AppCatalog` relay group MUST prefix their `subscriptionPrefix` with `app-`. This is used in the backend.

For detailed API, see models/purplebase READMEs in pub cache.
