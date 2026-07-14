import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/log_service.dart';

enum DevicePrivateSyncPhase { idle, syncing, success, error, cancelled }

class DevicePrivateSyncState {
  const DevicePrivateSyncState(this.phase, {this.error});

  const DevicePrivateSyncState.idle() : this(DevicePrivateSyncPhase.idle);

  final DevicePrivateSyncPhase phase;
  final Object? error;
}

typedef DevicePrivateQuery =
    Future<List<Model<dynamic>>> Function(
      Request<Model<dynamic>> request,
      Source source,
      String subscriptionPrefix,
    );

class DevicePrivateSyncNotifier extends StateNotifier<DevicePrivateSyncState> {
  DevicePrivateSyncNotifier(this.ref, {DevicePrivateQuery? query})
    : _query = query,
      super(const DevicePrivateSyncState.idle());

  final Ref ref;
  final DevicePrivateQuery? _query;
  Request<Model<dynamic>>? _activeRequest;
  Future<void>? _runFuture;
  bool _started = false;
  bool _cancelled = false;

  Future<void> start() async {
    if (_started) return;
    _started = true;
    _runFuture = _runSync();
    await _runFuture;
  }

  /// Explicit recovery exception for a device key restored after app boot.
  Future<void> syncRestoredKey() async {
    cancel();
    await _runFuture;
    _runFuture = _runSync();
    await _runFuture;
  }

  Future<void> _runSync() async {
    _cancelled = false;
    state = const DevicePrivateSyncState(DevicePrivateSyncPhase.syncing);

    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) {
      state = const DevicePrivateSyncState(
        DevicePrivateSyncPhase.error,
        error: 'Device key is unavailable.',
      );
      return;
    }

    final request = RequestFilter<Model<dynamic>>(
      kinds: const {30267, 30078},
      authors: {devicePubkey},
    ).toRequest();
    _activeRequest = request;

    try {
      await _queryStorage(
        request,
        source: const RemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-device-private-boot',
      );
      if (_cancelled) return;
      await _upgradeLegacyProofs(request);
      if (_cancelled) return;
      state = const DevicePrivateSyncState(DevicePrivateSyncPhase.success);
    } catch (error, stack) {
      if (_cancelled) return;
      LogService.I.warn(
        'device private boot sync failed',
        tag: 'private-sync',
        err: error,
        stack: stack,
      );
      state = DevicePrivateSyncState(
        DevicePrivateSyncPhase.error,
        error: error,
      );
    } finally {
      _activeRequest = null;
    }
  }

  Future<void> _upgradeLegacyProofs(Request<Model<dynamic>> request) async {
    final privateEvents = ref.read(devicePrivateEventServiceProvider);
    final models = await _queryStorage(
      request,
      source: const LocalSource(),
      subscriptionPrefix: 'app-device-private-upgrade',
    );

    for (final model in models) {
      if (_cancelled ||
          Nip13.isValid(
            model.event,
            minimumDifficulty: kPrivateEventPowDifficulty,
          )) {
        continue;
      }

      switch (model) {
        case AppStack stack when stack.content.isNotEmpty:
          await stack.prepareAfterLoading(ref);
          if (!stack.isDecrypted) {
            LogService.I.warn(
              'legacy private stack could not be decrypted for PoW upgrade',
              tag: 'private-sync',
              fields: {'identifier': stack.identifier},
            );
            continue;
          }
          final partial = PartialAppStack.withEncryptedApps(
            name: stack.name ?? stack.identifier,
            identifier: stack.identifier,
            description: stack.description,
            apps: stack.privateAppIds,
            platform: stack.platform,
          );
          partial.event.createdAt = privateEvents.nextReplaceableTimestamp(
            stack.createdAt,
          );
          await privateEvents.signAndSave(partial);
        case CustomData data
            when data.identifier == kSettingsIdentifier ||
                data.identifier == kTrustedSignersIdentifier:
          final partial = PartialCustomData(
            identifier: data.identifier,
            content: data.content,
          );
          for (final tag in data.event.tags) {
            if (tag.first == 'd' || tag.first == 'nonce') continue;
            partial.event.tags.add(List<String>.of(tag));
          }
          partial.event.createdAt = privateEvents.nextReplaceableTimestamp(
            data.createdAt,
          );
          await privateEvents.signAndSave(
            partial,
            publish: data.identifier != kTrustedSignersIdentifier,
          );
        default:
          break;
      }
    }
  }

  Future<List<Model<dynamic>>> _queryStorage(
    Request<Model<dynamic>> request, {
    required Source source,
    required String subscriptionPrefix,
  }) {
    final query = _query;
    if (query != null) {
      return query(request, source, subscriptionPrefix);
    }
    return ref
        .read(storageNotifierProvider.notifier)
        .query(request, source: source, subscriptionPrefix: subscriptionPrefix);
  }

  void cancel() {
    _cancelled = true;
    final request = _activeRequest;
    _activeRequest = null;
    if (request != null) {
      unawaited(ref.read(storageNotifierProvider.notifier).cancel(request));
    }
    ref.read(devicePrivateEventServiceProvider).cancelMining();
    if (state.phase == DevicePrivateSyncPhase.syncing) {
      state = const DevicePrivateSyncState(DevicePrivateSyncPhase.cancelled);
    }
  }

  @override
  void dispose() {
    cancel();
    super.dispose();
  }
}

final devicePrivateSyncProvider =
    StateNotifierProvider<DevicePrivateSyncNotifier, DevicePrivateSyncState>(
      (ref) => DevicePrivateSyncNotifier(ref),
    );
