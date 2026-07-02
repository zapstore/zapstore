import 'dart:async';

import 'package:collection/collection.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

class _UnmanagedAppsSnapshot {
  const _UnmanagedAppsSnapshot(this.ids, this.createdAt);

  final Set<String> ids;
  final DateTime? createdAt;
}

/// Returns `null` while an encrypted stack is still being decrypted. Keeping
/// that state distinct from an empty stack prevents the UI from briefly
/// forgetting every unmanaged app after each write.
final _persistedUnmanagedAppsProvider = Provider<_UnmanagedAppsSnapshot?>((
  ref,
) {
  final devicePubkey = ref.watch(devicePubkeyProvider);
  if (devicePubkey == null) {
    return const _UnmanagedAppsSnapshot({}, null);
  }

  final stackState = ref.watch(
    query<AppStack>(
      authors: {devicePubkey},
      tags: {
        '#d': {kUnmanagedAppsIdentifier},
      },
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
      subscriptionPrefix: 'app-unmanaged-apps',
    ),
  );

  final stack = switch (stackState) {
    StorageLoading() || StorageError() => null,
    StorageData(:final models) => models.firstOrNull,
  };

  if (stack == null) {
    return stackState is StorageData
        ? const _UnmanagedAppsSnapshot({}, null)
        : null;
  }
  if (!stack.isDecrypted) return null;

  return _UnmanagedAppsSnapshot(stack.privateAppIds.toSet(), stack.createdAt);
});

typedef UnmanagedAppsWriter =
    Future<void> Function(Set<String> appIds, DateTime createdAt);

/// Owns the decrypted unmanaged set and serializes writes.
///
/// State changes optimistically so moving a card between sections does not
/// wait for SQLite, encryption, or relay I/O. Writes are queued to ensure a
/// second action includes the first action even while its publish is pending.
class UnmanagedAppsNotifier extends StateNotifier<Set<String>> {
  UnmanagedAppsNotifier(this._write, {DateTime Function()? clock})
    : _clock = clock ?? DateTime.now,
      super(const {});

  static const _setEquality = SetEquality<String>();

  final UnmanagedAppsWriter _write;
  final DateTime Function() _clock;
  Future<void> _writeQueue = Future.value();
  Set<String> _lastPersisted = const {};
  DateTime? _lastPersistedAt;
  DateTime? _lastIssuedAt;
  int _pendingWrites = 0;

  void acceptPersisted(Set<String> appIds, DateTime? createdAt) {
    if (createdAt == null ||
        _lastPersistedAt == null ||
        !createdAt.isBefore(_lastPersistedAt!)) {
      _lastPersisted = Set.unmodifiable(appIds);
      _lastPersistedAt = createdAt ?? _lastPersistedAt;
    }

    if (_pendingWrites > 0) return;
    if (createdAt != null &&
        _lastIssuedAt != null &&
        createdAt.isBefore(_lastIssuedAt!)) {
      return;
    }
    _setState(appIds);
  }

  Future<void> toggle(String appId, {required bool unmanage}) {
    final updated = {...state};
    if (unmanage) {
      updated.add(appId);
    } else {
      updated.remove(appId);
    }
    if (_setEquality.equals(updated, state)) return Future.value();

    _setState(updated);
    final snapshot = Set<String>.unmodifiable(updated);
    final createdAt = _nextCreatedAt();
    final result = Completer<void>();
    _pendingWrites++;

    _writeQueue = _writeQueue.then((_) async {
      try {
        await _write(snapshot, createdAt);
        result.complete();
      } catch (error, stackTrace) {
        LogService.I.warn(
          'failed to persist unmanaged-apps stack',
          tag: 'unmanaged-apps',
          err: error,
          stack: stackTrace,
        );
        if (error is UnmanagedAppsSaveException && _pendingWrites == 1) {
          _lastIssuedAt = _lastPersistedAt;
          _setState(_lastPersisted);
        }
        result.completeError(error, stackTrace);
      } finally {
        _pendingWrites--;
      }
    });

    return result.future;
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

  void _setState(Set<String> appIds) {
    if (!_setEquality.equals(state, appIds)) {
      state = Set.unmodifiable(appIds);
    }
  }
}

/// Reactive set of package IDs the user has chosen as unmanaged.
///
/// Backed by an encrypted AppStack signed by the device key.
final unmanagedAppsProvider =
    StateNotifierProvider<UnmanagedAppsNotifier, Set<String>>((ref) {
      final notifier = UnmanagedAppsNotifier(
        (appIds, createdAt) =>
            _writeUnmanagedApps(ref, appIds, createdAt: createdAt),
      );
      ref.listen<_UnmanagedAppsSnapshot?>(_persistedUnmanagedAppsProvider, (
        _,
        snapshot,
      ) {
        if (snapshot != null) {
          notifier.acceptPersisted(snapshot.ids, snapshot.createdAt);
        }
      }, fireImmediately: true);
      return notifier;
    });

class UnmanagedAppsSaveException implements Exception {
  const UnmanagedAppsSaveException(this.message);

  final String message;

  @override
  String toString() => message;
}

class UnmanagedAppsPublishException implements Exception {
  const UnmanagedAppsPublishException(this.message);

  final String message;

  @override
  String toString() => message;
}

bool wasUnmanagedStackAccepted(PublishResponse response, String eventId) {
  return response.results[eventId]?.any((result) => result.accepted) ?? false;
}

PartialAppStack createUnmanagedAppsStack({
  required Set<String> appIds,
  required String platform,
  required DateTime createdAt,
}) {
  final stack = PartialAppStack.withEncryptedApps(
    name: 'Unmanaged Apps',
    identifier: kUnmanagedAppsIdentifier,
    apps: appIds.toList(),
    platform: platform,
  );
  stack.event.createdAt = createdAt;
  return stack;
}

Future<void> _writeUnmanagedApps(
  Ref ref,
  Set<String> appIds, {
  required DateTime createdAt,
}) async {
  final devicePubkey = ref.read(devicePubkeyProvider);
  if (devicePubkey == null) {
    throw const UnmanagedAppsSaveException('Device key is unavailable.');
  }

  final signer = ref.read(Signer.signerProvider(devicePubkey));
  if (signer == null) {
    throw const UnmanagedAppsSaveException('Device signer is unavailable.');
  }

  final storage = ref.read(storageNotifierProvider.notifier);
  final platform = ref.read(packageManagerProvider.notifier).platform;
  final partial = createUnmanagedAppsStack(
    appIds: appIds,
    platform: platform,
    createdAt: createdAt,
  );

  final signed = await partial.signWith(signer);
  final saved = await storage.save({signed});
  if (!saved) {
    throw const UnmanagedAppsSaveException(
      'Could not save the unmanaged apps list locally.',
    );
  }

  final response = await storage.publish({signed}, relays: 'AppCatalog');
  if (!wasUnmanagedStackAccepted(response, signed.id)) {
    throw const UnmanagedAppsPublishException(
      'Saved locally, but no AppCatalog relay accepted the event.',
    );
  }
}

/// Toggles [appId] in the unmanaged encrypted stack.
/// Pass [unmanage: true] to add, [unmanage: false] to remove.
Future<void> toggleUnmanagedApp(
  WidgetRef ref,
  String appId, {
  required bool unmanage,
}) {
  return ref
      .read(unmanagedAppsProvider.notifier)
      .toggle(appId, unmanage: unmanage);
}
