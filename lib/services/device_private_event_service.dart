import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:zapstore/services/log_service.dart';

const kDeviceEventPowDifficulty = 20;
const _pendingDeviceEventsKeyPrefix = 'pending_device_events';

final class PendingDeviceEvent {
  const PendingDeviceEvent({required this.kind, this.identifier});

  final int kind;
  final String? identifier;

  Map<String, dynamic> toJson() => {
    'kind': kind,
    if (identifier != null) 'identifier': identifier,
  };

  static PendingDeviceEvent? fromJson(Object? value) {
    if (value is! Map) return null;
    final kind = value['kind'];
    final identifier = value['identifier'];
    if (kind is! int ||
        (identifier != null && (identifier is! String || identifier.isEmpty))) {
      return null;
    }
    return PendingDeviceEvent(kind: kind, identifier: identifier as String?);
  }

  @override
  bool operator ==(Object other) =>
      other is PendingDeviceEvent &&
      other.kind == kind &&
      other.identifier == identifier;

  @override
  int get hashCode => Object.hash(kind, identifier);
}

abstract interface class PendingDeviceEventsStore {
  Future<Set<PendingDeviceEvent>> load(String devicePubkey);

  Future<void> save(String devicePubkey, Set<PendingDeviceEvent> events);
}

class SecureStoragePendingDeviceEventsStore
    implements PendingDeviceEventsStore {
  SecureStoragePendingDeviceEventsStore({FlutterSecureStorage? storage})
    : _storage =
          storage ??
          const FlutterSecureStorage(
            aOptions: AndroidOptions(encryptedSharedPreferences: true),
            iOptions: IOSOptions(
              accessibility: KeychainAccessibility.first_unlock,
            ),
          );

  final FlutterSecureStorage _storage;

  String _keyFor(String devicePubkey) =>
      '$_pendingDeviceEventsKeyPrefix:$devicePubkey';

  @override
  Future<Set<PendingDeviceEvent>> load(String devicePubkey) async {
    final raw = await _storage.read(key: _keyFor(devicePubkey));
    if (raw == null || raw.isEmpty) return {};
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return {};
      return {
        for (final value in decoded)
          if (PendingDeviceEvent.fromJson(value) case final event?) event,
      };
    } catch (_) {
      return {};
    }
  }

  @override
  Future<void> save(String devicePubkey, Set<PendingDeviceEvent> events) async {
    final key = _keyFor(devicePubkey);
    if (events.isEmpty) {
      await _storage.delete(key: key);
      return;
    }
    final encoded = events.map((event) => event.toJson()).toList()
      ..sort((a, b) {
        final kind = (a['kind'] as int).compareTo(b['kind'] as int);
        return kind != 0
            ? kind
            : (a['identifier'] as String? ?? '').compareTo(
                b['identifier'] as String? ?? '',
              );
      });
    await _storage.write(key: key, value: jsonEncode(encoded));
  }
}

final privateEventPowExecutorProvider = Provider<IsolateProofOfWorkExecutor>((
  ref,
) {
  final executor = IsolateProofOfWorkExecutor();
  ref.onDispose(executor.dispose);
  return executor;
});

final devicePrivateEventServiceProvider = Provider<DevicePrivateEventService>((
  ref,
) {
  return DevicePrivateEventService(
    ref,
    executor: ref.watch(privateEventPowExecutorProvider),
    pendingEventsStore: SecureStoragePendingDeviceEventsStore(),
  );
});

/// Enforces the signing and publication contract for device-private events.
class DevicePrivateEventService {
  DevicePrivateEventService(
    this.ref, {
    required ProofOfWorkExecutor executor,
    PendingDeviceEventsStore? pendingEventsStore,
    this.startProcessing = true,
    this.difficulty = kDeviceEventPowDifficulty,
    this.timeout = const Duration(days: 3650),
    this.maxAttempts = 1 << 62,
    this.batchSize = 512,
  }) : _executor = executor,
       _pendingEventsStore =
           pendingEventsStore ?? SecureStoragePendingDeviceEventsStore() {
    ref.onDispose(() {
      _disposed = true;
      cancelMining();
    });
  }

  final Ref ref;
  final ProofOfWorkExecutor _executor;
  final PendingDeviceEventsStore _pendingEventsStore;
  final bool startProcessing;
  final int difficulty;
  final Duration timeout;
  final int maxAttempts;
  final int batchSize;
  Future<void>? _draftWriteQueue;
  Future<void>? _processingQueue;
  bool _disposed = false;

  String get devicePubkey {
    final pubkey = ref.read(devicePubkeyProvider);
    if (pubkey == null) {
      throw const DevicePrivateEventException('Device key is unavailable.');
    }
    return pubkey;
  }

  Signer get deviceSigner {
    final signer = ref.read(Signer.signerProvider(devicePubkey));
    if (signer == null) {
      throw const DevicePrivateEventException('Device signer is unavailable.');
    }
    return signer;
  }

  ProofOfWorkOptions get proofOfWork => ProofOfWorkOptions(
    difficulty: difficulty,
    timeout: timeout,
    maxAttempts: maxAttempts,
    batchSize: batchSize,
    executor: _executor,
  );

  Future<String> encryptToDevice(String plaintext) =>
      deviceSigner.nip44Encrypt(plaintext, devicePubkey);

  Future<String> decryptFromDevice(String ciphertext) =>
      deviceSigner.nip44Decrypt(ciphertext, devicePubkey);

  Future<String> encryptFor(String plaintext, String recipientPubkey) =>
      deviceSigner.nip44Encrypt(plaintext, recipientPubkey);

  /// Save a local-only draft first, then asynchronously queue its relay copy.
  ///
  /// The draft deliberately has no PoW and is never published. It survives
  /// mining cancellation so [processPendingEvents] can rebuild it later.
  Future<Model<dynamic>> saveDraftAndQueue(PartialModel<dynamic> partial) {
    final previous = _draftWriteQueue ?? Future<void>.value();
    final operation = previous.then((_) => _saveDraftAndQueue(partial));
    _draftWriteQueue = operation.then<void>(
      (_) {},
      onError: (Object _, StackTrace __) {},
    );
    return operation;
  }

  Future<Model<dynamic>> _saveDraftAndQueue(
    PartialModel<dynamic> partial,
  ) async {
    _validatePartial(partial);
    final pending = _pendingFor(partial);
    final existing = await _loadLatestDraft(pending);
    partial.event.createdAt = nextReplaceableTimestamp(
      existing?.event.createdAt,
    );
    final draft = await _signAndSave(partial, publish: false);
    await _updatePendingEvents((events) => {...events, pending});
    _logPendingEvent('local draft saved and queued', pending);
    if (startProcessing) unawaited(processPendingEvents());
    return draft;
  }

  /// Queue an already-saved local draft, such as a relay-list handoff restored
  /// after an application restart.
  Future<void> queueExistingDraft(Model<dynamic> draft) async {
    if (draft.pubkey != devicePubkey || !verifySignedEvent(ref, draft.event)) {
      throw const DevicePrivateEventException(
        'Pending device event draft is invalid.',
      );
    }
    final pending = PendingDeviceEvent(
      kind: draft.event.kind,
      identifier: draft.event.getFirstTagValue('d'),
    );
    await _updatePendingEvents((events) => {...events, pending});
    _logPendingEvent('restored local draft queued', pending);
    unawaited(processPendingEvents());
  }

  /// Start the persistent device-event queue. Repeated calls share a drain.
  Future<void> processPendingEvents() {
    if (_disposed) {
      LogService.I.debug(
        'queue trigger ignored after disposal',
        tag: 'device-event-queue',
      );
      return Future<void>.value();
    }
    if (ref.read(devicePubkeyProvider) == null) {
      LogService.I.debug(
        'queue trigger ignored without device key',
        tag: 'device-event-queue',
      );
      return Future<void>.value();
    }
    final existing = _processingQueue;
    if (existing != null) {
      LogService.I.debug(
        'queue drain already active',
        tag: 'device-event-queue',
      );
      return existing;
    }
    LogService.I.info('queue drain started', tag: 'device-event-queue');
    late final Future<void> processing;
    processing = _processPendingEvents().whenComplete(() {
      if (identical(_processingQueue, processing)) {
        _processingQueue = null;
      }
    });
    _processingQueue = processing;
    return processing;
  }

  Future<void> _processPendingEvents() async {
    final attempted = <PendingDeviceEvent>{};
    while (true) {
      if (_disposed) return;
      final pending = await _loadPendingEvents();
      if (_disposed) return;
      final next = pending
          .where((event) => !attempted.contains(event))
          .firstOrNull;
      if (next == null) {
        LogService.I.info(
          'queue drain finished',
          tag: 'device-event-queue',
          fields: {'attempted': attempted.length},
        );
        return;
      }
      attempted.add(next);
      try {
        await _processPendingEvent(next);
      } catch (error, stack) {
        // The marker remains. A future mutation, connectivity restoration, app
        // resume, or startup will retry without polling.
        LogService.I.warn(
          'device event queue failed',
          tag: 'device-event-queue',
          fields: _pendingEventFields(next),
          err: error,
          stack: stack,
        );
      }
    }
  }

  Future<void> _processPendingEvent(PendingDeviceEvent pending) async {
    final draft = await _loadLatestDraft(pending);
    if (draft == null) {
      await _updatePendingEvents((events) => events..remove(pending));
      _logPendingEvent('marker removed because local draft is absent', pending);
      return;
    }
    if (draft.pubkey != devicePubkey || !verifySignedEvent(ref, draft.event)) {
      throw const DevicePrivateEventException(
        'Pending device event draft is invalid.',
      );
    }

    _logPendingEvent('mining or publishing queued draft', pending);
    final signed = Nip13.isValid(draft.event, minimumDifficulty: difficulty)
        ? draft
        : await _signAndSave(
            switch (pending.kind) {
              10067 => PartialAppCatalogRelayList.fromMap(draft.toMap()),
              30078 => PartialCustomData.fromMap(draft.toMap()),
              30267 => PartialAppStack.fromMap(draft.toMap()),
              _ => throw DevicePrivateEventException(
                'Unsupported pending device event kind: ${pending.kind}',
              ),
            },
            proofOfWork: proofOfWork,
            publish: false,
          );
    _logPendingEvent('publishing proof-of-work event', pending);
    final response = await ref.read(storageNotifierProvider.notifier).publish({
      signed,
    }, relays: _relayTargetFor(pending.kind));
    final accepted =
        response.results[signed.event.id]?.any((result) => result.accepted) ??
        false;
    if (!accepted) {
      throw const DevicePrivatePublishException(
        'Saved locally, but no AppCatalog relay accepted the event.',
      );
    }

    final latest = await _loadLatestDraft(pending);
    if (latest != null && !latest.createdAt.isAfter(draft.createdAt)) {
      await _updatePendingEvents((events) => events..remove(pending));
      _logPendingEvent('relay accepted event and marker removed', pending);
    } else {
      _logPendingEvent(
        'relay accepted older event; newer draft remains queued',
        pending,
      );
    }
  }

  Future<Set<PendingDeviceEvent>> _loadPendingEvents() =>
      _pendingEventsStore.load(devicePubkey);

  Future<void> _updatePendingEvents(
    Set<PendingDeviceEvent> Function(Set<PendingDeviceEvent>) update,
  ) async {
    final current = await _loadPendingEvents();
    await _pendingEventsStore.save(devicePubkey, update({...current}));
  }

  PendingDeviceEvent _pendingFor(PartialModel<dynamic> partial) {
    final requiresIdentifier = partial.event.kind != 10067;
    final identifier = partial.event.identifier;
    if (requiresIdentifier && (identifier == null || identifier.isEmpty)) {
      throw const DevicePrivateEventException(
        'Private device event requires a d identifier.',
      );
    }
    return PendingDeviceEvent(kind: partial.event.kind, identifier: identifier);
  }

  Future<Model<dynamic>?> _loadLatestDraft(PendingDeviceEvent pending) async {
    final events = await ref
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<Model<dynamic>>(
            kinds: {pending.kind},
            authors: {devicePubkey},
            tags: pending.identifier == null
                ? const {}
                : {
                    '#d': {pending.identifier!},
                  },
            limit: 1,
          ).toRequest(),
          source: const LocalSource(),
          subscriptionPrefix: 'app-device-event-draft',
        );
    return events.firstOrNull;
  }

  dynamic _relayTargetFor(int kind) => switch (kind) {
    10067 => const {kDefaultRelay},
    _ => 'AppCatalog',
  };

  void _logPendingEvent(String message, PendingDeviceEvent event) {
    LogService.I.info(
      message,
      tag: 'device-event-queue',
      fields: _pendingEventFields(event),
    );
  }

  Map<String, Object?> _pendingEventFields(PendingDeviceEvent event) => {
    'kind': event.kind,
    if (event.identifier != null) 'identifier': event.identifier,
  };

  Future<Model<dynamic>> _signAndSave(
    PartialModel<dynamic> partial, {
    bool publish = true,
    ProofOfWorkOptions? proofOfWork,
  }) async {
    _validatePartial(partial);
    final signer = deviceSigner;
    final signed =
        await partial.signWith(signer, proofOfWork: proofOfWork)
            as Model<dynamic>;
    _validateSigned(signed);

    final storage = ref.read(storageNotifierProvider.notifier);
    final saved = await storage.save({signed});
    if (!saved) {
      throw const DevicePrivateSaveException(
        'Could not save private data locally.',
      );
    }

    if (publish) {
      final response = await storage.publish({signed}, relays: 'AppCatalog');
      final accepted =
          response.results[signed.event.id]?.any((result) => result.accepted) ??
          false;
      if (!accepted) {
        throw const DevicePrivatePublishException(
          'Saved locally, but no AppCatalog relay accepted the event.',
        );
      }
    }
    return signed;
  }

  DateTime nextReplaceableTimestamp(
    DateTime? existing, {
    DateTime Function()? clock,
  }) {
    final now = (clock ?? DateTime.now)();
    final candidate = DateTime.fromMillisecondsSinceEpoch(
      (now.millisecondsSinceEpoch ~/ 1000) * 1000,
      isUtc: now.isUtc,
    );
    if (existing == null || candidate.isAfter(existing)) return candidate;
    return existing.add(const Duration(seconds: 1));
  }

  void cancelMining() {
    final executor = _executor;
    if (executor is IsolateProofOfWorkExecutor) {
      executor.cancelAll();
    }
  }

  void _validatePartial(PartialModel<dynamic> partial) {
    final event = partial.event;
    switch (event.kind) {
      case 30267:
        if (event.content.isEmpty) {
          throw const DevicePrivateEventException(
            'Public app stacks must not use the private event signer.',
          );
        }
        if (event.containsTag('h')) {
          throw const DevicePrivateEventException(
            'Encrypted app stacks must not carry a community h tag.',
          );
        }
      case 30078:
        final identifier = event.identifier;
        if (identifier != kDeviceStateIdentifier) {
          throw DevicePrivateEventException(
            'Unsupported private CustomData identifier: $identifier',
          );
        }
      case 10067:
        if (event.content.isNotEmpty) {
          throw const DevicePrivateEventException(
            'App Catalog relay lists must not have content.',
          );
        }
      default:
        throw DevicePrivateEventException(
          'Unsupported private event kind: ${event.kind}',
        );
    }
  }

  void _validateSigned(Model<dynamic> signed) {
    if (signed.pubkey != devicePubkey) {
      throw const DevicePrivateEventException(
        'Private event was not signed by the device key.',
      );
    }
    if (!verifySignedEvent(ref, signed.event)) {
      throw const DevicePrivateEventException(
        'Private event signature or event ID is invalid.',
      );
    }
  }
}

/// Verifies both the canonical Nostr ID and its BIP-340 signature.
bool verifySignedEvent(Ref ref, EventBase event) {
  try {
    final map = event.toMap();
    final pubkey = map['pubkey'];
    if (pubkey is! String) return false;
    final partial = PartialEvent<Model<dynamic>>(map, event.kind)
      ..tags = [for (final tag in event.tags) List<String>.of(tag)];
    return map['id'] == Utils.getEventId(partial, pubkey) &&
        ref.read(verifierProvider).verify(map);
  } catch (_) {
    return false;
  }
}

class DevicePrivateEventException implements Exception {
  const DevicePrivateEventException(this.message);

  final String message;

  @override
  String toString() => message;
}

final class DevicePrivateSaveException extends DevicePrivateEventException {
  const DevicePrivateSaveException(super.message);
}

final class DevicePrivatePublishException extends DevicePrivateEventException {
  const DevicePrivatePublishException(super.message);
}
