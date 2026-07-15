import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/utils/debug_utils.dart';

class _MemoryPendingDeviceEventsStore implements PendingDeviceEventsStore {
  final Map<String, Set<PendingDeviceEvent>> values = {};

  @override
  Future<Set<PendingDeviceEvent>> load(String devicePubkey) async => {
    ...?values[devicePubkey],
  };

  @override
  Future<void> save(String devicePubkey, Set<PendingDeviceEvent> events) async {
    if (events.isEmpty) {
      values.remove(devicePubkey);
    } else {
      values[devicePubkey] = {...events};
    }
  }
}

class _AcceptingStorageNotifier extends DummyStorageNotifier {
  _AcceptingStorageNotifier(super.ref);

  @override
  Future<PublishResponse> publish(
    Set<Model<dynamic>> models, {
    dynamic relays,
  }) async {
    await save(models);
    final response = PublishResponse();
    for (final model in models) {
      response.addEvent(
        model.event.id,
        relayUrl: 'wss://relay.example',
        accepted: true,
      );
    }
    return response;
  }
}

class _CancelledProofOfWorkExecutor implements ProofOfWorkExecutor {
  @override
  Future<ProofOfWorkResult> mine<E extends Model<dynamic>>(
    PartialEvent<E> event, {
    required String pubkey,
    required ProofOfWorkOptions options,
  }) => Future<ProofOfWorkResult>.error(const ProofOfWorkCancelled());
}

void main() {
  late ProviderContainer container;
  late Bip340PrivateKeySigner signer;
  late IsolateProofOfWorkExecutor executor;
  late DevicePrivateEventService service;
  late _MemoryPendingDeviceEventsStore pendingEvents;

  setUp(() async {
    container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(_AcceptingStorageNotifier.new),
      ],
    );
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(
          StorageConfiguration(
            keepSignatures: true,
            defaultRelays: {
              'AppCatalog': {'wss://relay.example'},
            },
          ),
        );

    signer = Bip340PrivateKeySigner('1' * 64, container.read(refProvider));
    await signer.signIn(setAsActive: false);
    container.read(devicePubkeyProvider.notifier).state = signer.pubkey;
    executor = IsolateProofOfWorkExecutor();
    pendingEvents = _MemoryPendingDeviceEventsStore();
    service = DevicePrivateEventService(
      container.read(refProvider),
      executor: executor,
      pendingEventsStore: pendingEvents,
      difficulty: 4,
      maxAttempts: 100000,
    );
  });

  tearDown(() {
    executor.dispose();
    container.dispose();
  });

  test('production device-event policy requires 20 proof-of-work bits', () {
    expect(kDeviceEventPowDifficulty, 20);
  });

  test('does not process pending events without a device key', () async {
    container.read(devicePubkeyProvider.notifier).state = null;

    await expectLater(service.processPendingEvents(), completes);
  });

  test('queues a local device-state draft by kind and identifier', () async {
    final draftOnly = DevicePrivateEventService(
      container.read(refProvider),
      executor: executor,
      pendingEventsStore: pendingEvents,
      startProcessing: false,
    );
    final ciphertext = await service.encryptToDevice('{}');
    await draftOnly.saveDraftAndQueue(
      PartialCustomData(
        identifier: kDeviceStateIdentifier,
        content: ciphertext,
      ),
    );

    expect(await pendingEvents.load(signer.pubkey), {
      const PendingDeviceEvent(kind: 30078, identifier: kDeviceStateIdentifier),
    });
    final stored = await container
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<CustomData>(
            authors: {signer.pubkey},
            tags: {
              '#d': {kDeviceStateIdentifier},
            },
          ).toRequest(),
          source: const LocalSource(),
        );
    expect(stored.single.pubkey, signer.pubkey);
  });

  test('mines and publishes queued device-state drafts', () async {
    final ciphertext = await service.encryptToDevice('{}');
    await service.saveDraftAndQueue(
      PartialCustomData(
        identifier: kDeviceStateIdentifier,
        content: ciphertext,
      ),
    );
    await service.processPendingEvents();

    final stored = await container
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<CustomData>(
            authors: {signer.pubkey},
            tags: {
              '#d': {kDeviceStateIdentifier},
            },
            limit: 1,
          ).toRequest(),
          source: const LocalSource(),
        );
    expect(Nip13.isValid(stored.single.event, minimumDifficulty: 4), isTrue);
    expect(await pendingEvents.load(signer.pubkey), isEmpty);
  });

  test('keeps a pending marker when proof-of-work is cancelled', () async {
    final cancelled = DevicePrivateEventService(
      container.read(refProvider),
      executor: _CancelledProofOfWorkExecutor(),
      pendingEventsStore: pendingEvents,
      difficulty: 4,
    );
    final ciphertext = await cancelled.encryptToDevice('{}');
    await cancelled.saveDraftAndQueue(
      PartialCustomData(
        identifier: kDeviceStateIdentifier,
        content: ciphertext,
      ),
    );
    await cancelled.processPendingEvents();

    expect(await pendingEvents.load(signer.pubkey), {
      const PendingDeviceEvent(kind: 30078, identifier: kDeviceStateIdentifier),
    });
  });

  test('queues a relay-list draft using a marker without d', () async {
    final draftOnly = DevicePrivateEventService(
      container.read(refProvider),
      executor: executor,
      pendingEventsStore: pendingEvents,
      startProcessing: false,
    );
    await draftOnly.saveDraftAndQueue(
      PartialAppCatalogRelayList(relays: {'wss://relay.example'}),
    );

    expect(await pendingEvents.load(signer.pubkey), {
      const PendingDeviceEvent(kind: 10067),
    });
  });

  test('rejects encrypted stacks carrying a community tag', () async {
    final partial = PartialAppStack.withEncryptedApps(
      name: 'Saved Apps',
      identifier: kAppBookmarksIdentifier,
      apps: const ['32267:pubkey:app'],
    )..event.addTagValue('h', kZapstoreCommunityPubkey);

    await expectLater(
      service.saveDraftAndQueue(partial),
      throwsA(isA<DevicePrivateEventException>()),
    );
  });

  test('rejects unknown private CustomData identifiers', () async {
    await expectLater(
      service.saveDraftAndQueue(
        PartialCustomData(identifier: 'unknown', content: 'secret'),
      ),
      throwsA(isA<DevicePrivateEventException>()),
    );
  });
}
