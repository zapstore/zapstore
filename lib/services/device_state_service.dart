import 'dart:async';
import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/settings_service.dart';

enum DeviceStatePhase { bootstrapping, ready, error }

class DeviceStateStatus {
  const DeviceStateStatus(this.phase, {this.error, this.startedAt});

  const DeviceStateStatus.bootstrapping()
    : phase = DeviceStatePhase.bootstrapping,
      error = null,
      startedAt = null;

  const DeviceStateStatus.ready()
    : phase = DeviceStatePhase.ready,
      error = null,
      startedAt = null;

  final DeviceStatePhase phase;
  final Object? error;
  final DateTime? startedAt;

  bool get isReady => phase == DeviceStatePhase.ready;
}

/// Owns the portable settings snapshot and queues its relay synchronization.
class DeviceStateNotifier extends StateNotifier<DeviceStateStatus> {
  DeviceStateNotifier(this.ref)
    : super(const DeviceStateStatus.bootstrapping());

  final Ref ref;
  Future<void>? _bootstrapFuture;
  Future<void> _publishQueue = Future.value();
  bool _disposed = false;

  Future<void> bootstrap() {
    final existing = _bootstrapFuture;
    if (existing != null) return existing;
    if (!_disposed) {
      state = DeviceStateStatus(
        DeviceStatePhase.bootstrapping,
        startedAt: DateTime.now(),
      );
    }
    return _bootstrapFuture = _bootstrap();
  }

  Future<void> _bootstrap() async {
    try {
      if (!_disposed) state = const DeviceStateStatus.ready();
    } catch (error, stack) {
      LogService.I.warn(
        'device-state bootstrap failed',
        tag: 'device-state',
        err: error,
        stack: stack,
      );
      if (!_disposed) {
        state = DeviceStateStatus(DeviceStatePhase.error, error: error);
      }
      rethrow;
    }
  }

  /// Persists first. Remote publishing never gates the local preference change.
  Future<void> updatePortable(
    PortableSettings Function(PortableSettings current) updater,
  ) async {
    if (!state.isReady) return;
    final settingsService = ref.read(settingsServiceProvider);
    final updated = updater(await settingsService.loadPortable());
    await settingsService.savePortable(updated);

    _publishQueue = _publishQueue.then((_) => _publish(settings: updated));
    unawaited(
      _publishQueue.catchError((error, stack) {
        LogService.I.warn(
          'device-state publish failed',
          tag: 'device-state',
          err: error,
          stack: stack,
        );
      }),
    );
  }

  Future<bool> restoreFromLocalEvent() async {
    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) return false;
    final models = await ref
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<CustomData>(
            authors: {devicePubkey},
            tags: {
              '#d': {kDeviceStateIdentifier},
            },
            limit: 1,
          ).toRequest(),
          source: const LocalSource(),
          subscriptionPrefix: 'app-device-state-local',
        );
    final event = models.firstOrNull;
    if (event == null || !verifySignedEvent(ref, event.event)) return false;
    try {
      final plaintext = await ref
          .read(devicePrivateEventServiceProvider)
          .decryptFromDevice(event.content);
      final decoded = jsonDecode(plaintext);
      if (decoded is! Map) return false;
      await ref
          .read(settingsServiceProvider)
          .savePortable(
            PortableSettings.fromJson(Map<String, dynamic>.from(decoded)),
          );
      ref.invalidate(localSettingsProvider);
      if (!_disposed) state = const DeviceStateStatus.ready();
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> _publish({required PortableSettings settings}) async {
    final privateEvents = ref.read(devicePrivateEventServiceProvider);
    final encrypted = await privateEvents.encryptToDevice(
      jsonEncode(settings.toJson()),
    );
    final partial = PartialCustomData(
      identifier: kDeviceStateIdentifier,
      content: encrypted,
    );
    await privateEvents.saveDraftAndQueue(partial);
  }

  @override
  void dispose() {
    _disposed = true;
    ref.read(devicePrivateEventServiceProvider).cancelMining();
    super.dispose();
  }
}

final deviceStateProvider =
    StateNotifierProvider<DeviceStateNotifier, DeviceStateStatus>(
      (ref) => DeviceStateNotifier(ref),
    );
