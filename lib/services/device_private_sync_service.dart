import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/device_state_service.dart';
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
      // LocalAndRemoteSource ensures remote events are saved, then re-read from
      // SQLite before we restore portable settings or notify stack consumers.
      final models = await _queryStorage(
        request,
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-device-private-boot',
      );
      if (_cancelled) return;

      // Remote query notifications carry the remote request identity. Re-saving
      // on the main isolate emits req:null so LocalSource stack watchers refresh
      // under the restored device pubkey and decrypt with its signer.
      if (models.isNotEmpty) {
        await ref.read(storageNotifierProvider.notifier).save(models.toSet());
      }
      LogService.I.info(
        'device private sync completed',
        tag: 'private-sync',
        fields: {
          'models': models.length,
          'stacks': models.whereType<AppStack>().length,
        },
      );

      await ref.read(deviceStateProvider.notifier).restoreFromLocalEvent();
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
