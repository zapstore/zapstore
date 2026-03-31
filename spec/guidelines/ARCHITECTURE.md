---
description: Architecture — layers, dependency rules, ownership, common Dart/Flutter patterns
alwaysApply: true
---

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

### Paged lists with live subscription (infinite scroll)

Use `PagedSubscriptionNotifier<T>` (`lib/utils/paged_subscription_notifier.dart`)
for any list that is sorted descending by date, supports infinite scroll, and
should reflect new items without user action.

**Rules:**

- The **first page only** uses `stream: true`. It serves local cache immediately
  and merges relay events in the background (local-first).
- **All older pages** use `stream: false` with an `until` cursor pointing 1 ms
  before the oldest loaded item. Never subscribe to older pages.
- Scroll listeners call `notifier.loadMore()` — the base class handles
  deduplication, the `isLoadingMore` guard, and `hasMore` detection.

**How to implement:**

```dart
class MyNotifier extends PagedSubscriptionNotifier<MyModel> {
  MyNotifier(super.ref);

  ProviderSubscription<StorageState<MyModel>>? _sub;

  @override int get pageSize => 10;

  @override
  void startSubscription() {
    _sub?.close();
    _sub = ref.listen(
      query<MyModel>(
        limit: pageSize,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'app-my-list',
      ),
      (_, next) => updateFirstPage(next),
      fireImmediately: true,
    );
  }

  @override
  Future<({List<MyModel> items, int count})> fetchOlderPage(DateTime until) async {
    final items = await ref.storage.query(
      RequestFilter<MyModel>(until: until, limit: pageSize).toRequest(),
      source: const LocalAndRemoteSource(stream: false),
      subscriptionPrefix: 'app-my-list-older',
    );
    return (items: items, count: items.length);
  }

  @override String getId(MyModel item) => item.id;
  @override DateTime getCreatedAt(MyModel item) => item.event.createdAt;

  @override void dispose() { _sub?.close(); super.dispose(); }
}

final myListProvider = StateNotifierProvider<MyNotifier, PagedState<MyModel>>(
  (ref) => MyNotifier(ref),
);
```

**In the widget:**

```dart
// Infinite scroll trigger
useEffect(() {
  void onScroll() {
    final s = ref.read(myListProvider);
    if (s.isLoadingMore || !s.hasMore) return;
    if (scrollController.position.pixels >=
        scrollController.position.maxScrollExtent - 300) {
      ref.read(myListProvider.notifier).loadMore();
    }
  }
  scrollController.addListener(onScroll);
  return () => scrollController.removeListener(onScroll);
}, [scrollController]);

// Consume state
final state = ref.watch(myListProvider);
final items = state.combined; // first page + all older pages

// Loading / error / content
if (state.firstPage is StorageLoading && items.isEmpty) { /* skeleton */ }
else if (state.firstPage is StorageError) { /* error */ }
else { /* list */ }
```

**Examples:** `LatestReleasesNotifier` (apps via assets), `StacksNotifier` (stacks directly).

### Subscription prefix naming

All queries using the `AppCatalog` relay group MUST prefix their `subscriptionPrefix` with `app-`. This is used in the backend.

For detailed API, see models/purplebase READMEs in pub cache.
