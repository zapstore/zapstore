import 'dart:async';

import 'package:collection/collection.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

final _persistedBookmarksProvider = Provider<AppStack?>((ref) {
  final devicePubkey = ref.watch(devicePubkeyProvider);
  if (devicePubkey == null) return null;

  final stackState = ref.watch(
    query<AppStack>(
      authors: {devicePubkey},
      tags: {
        '#d': {kAppBookmarksIdentifier},
      },
      source: const LocalSource(),
      subscriptionPrefix: 'app-user-saved-apps',
    ),
  );

  return switch (stackState) {
    StorageLoading(:final models) => models.firstOrNull,
    StorageError() => null,
    StorageData(:final models) => models.firstOrNull,
  };
});

typedef BookmarksWriter =
    Future<void> Function(Set<String> appIds, DateTime createdAt);

/// Owns bookmarked IDs and serializes replacement writes.
class BookmarksNotifier extends StateNotifier<Set<String>> {
  BookmarksNotifier(this._write, {DateTime Function()? clock})
    : _clock = clock ?? DateTime.now,
      super(const {});

  static const _equality = SetEquality<String>();
  final BookmarksWriter _write;
  final DateTime Function() _clock;
  Future<void> _writeQueue = Future.value();
  Set<String> _lastPersisted = const {};
  DateTime? _lastPersistedAt;
  DateTime? _lastIssuedAt;
  int _pendingWrites = 0;

  void acceptPersisted(AppStack? stack) {
    if (stack == null || !stack.isDecrypted) return;
    if (_lastPersistedAt == null ||
        !stack.createdAt.isBefore(_lastPersistedAt!)) {
      _lastPersisted = Set.unmodifiable(stack.privateAppIds);
      _lastPersistedAt = stack.createdAt;
    }
    if (_pendingWrites > 0 ||
        (_lastIssuedAt != null && stack.createdAt.isBefore(_lastIssuedAt!))) {
      return;
    }
    _setState(stack.privateAppIds.toSet());
  }

  Future<bool> toggle(String appId) {
    final updated = {...state};
    final removed = updated.remove(appId);
    if (!removed) updated.add(appId);
    _setState(updated);

    final createdAt = _nextCreatedAt();
    final snapshot = Set<String>.unmodifiable(updated);
    final completer = Completer<bool>();
    _pendingWrites++;
    _writeQueue = _writeQueue.then((_) async {
      try {
        await _write(snapshot, createdAt);
        completer.complete(!removed);
      } catch (error, stackTrace) {
        if (error is DevicePrivateSaveException && _pendingWrites == 1) {
          _lastIssuedAt = _lastPersistedAt;
          _setState(_lastPersisted);
        }
        completer.completeError(error, stackTrace);
      } finally {
        _pendingWrites--;
      }
    });
    return completer.future;
  }

  DateTime _nextCreatedAt() {
    final now = _clock();
    final candidate = DateTime.fromMillisecondsSinceEpoch(
      (now.millisecondsSinceEpoch ~/ 1000) * 1000,
      isUtc: now.isUtc,
    );
    final baseline = switch ((_lastIssuedAt, _lastPersistedAt)) {
      (final issued?, final persisted?) =>
        issued.isAfter(persisted) ? issued : persisted,
      (final issued?, null) => issued,
      (null, final persisted?) => persisted,
      (null, null) => null,
    };
    final next = baseline != null && !candidate.isAfter(baseline)
        ? baseline.add(const Duration(seconds: 1))
        : candidate;
    _lastIssuedAt = next;
    return next;
  }

  void _setState(Set<String> value) {
    if (!_equality.equals(state, value)) {
      state = Set.unmodifiable(value);
    }
  }
}

/// Reactive set of bookmarked app addressable IDs.
final bookmarksProvider = StateNotifierProvider<BookmarksNotifier, Set<String>>(
  (ref) {
    final notifier = BookmarksNotifier(
      (ids, createdAt) => _writeBookmarks(ref, ids, createdAt),
    );
    ref.listen<AppStack?>(
      _persistedBookmarksProvider,
      (_, stack) => notifier.acceptPersisted(stack),
      fireImmediately: true,
    );
    return notifier;
  },
);

Future<void> _writeBookmarks(
  Ref ref,
  Set<String> appIds,
  DateTime createdAt,
) async {
  final platform = ref.read(packageManagerProvider.notifier).platform;
  final partial = PartialAppStack.withEncryptedApps(
    name: 'Saved Apps',
    identifier: kAppBookmarksIdentifier,
    apps: appIds.toList(growable: false),
    platform: platform,
  );
  partial.event.createdAt = createdAt;
  await ref.read(devicePrivateEventServiceProvider).signAndSave(partial);
}

Future<bool> toggleBookmark(WidgetRef ref, App app) {
  final appId = '${app.event.kind}:${app.pubkey}:${app.identifier}';
  return ref.read(bookmarksProvider.notifier).toggle(appId);
}

/// Extension to check if an app is saved
extension SavedAppsChecker on WidgetRef {
  bool isAppSaved(App app) {
    final savedApps = watch(bookmarksProvider);
    final appAddressableId =
        '${app.event.kind}:${app.pubkey}:${app.identifier}';
    return savedApps.contains(appAddressableId);
  }

  Set<String> getSavedAppIds() {
    return watch(bookmarksProvider);
  }
}
