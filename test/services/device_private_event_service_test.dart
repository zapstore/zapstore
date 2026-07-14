import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/utils/debug_utils.dart';

void main() {
  late ProviderContainer container;
  late Bip340PrivateKeySigner signer;
  late IsolateProofOfWorkExecutor executor;
  late DevicePrivateEventService service;

  setUp(() async {
    container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
      ],
    );
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());

    signer = Bip340PrivateKeySigner('1' * 64, container.read(refProvider));
    await signer.signIn(setAsActive: false);
    container.read(devicePubkeyProvider.notifier).state = signer.pubkey;
    executor = IsolateProofOfWorkExecutor();
    service = DevicePrivateEventService(
      container.read(refProvider),
      executor: executor,
      difficulty: 4,
      maxAttempts: 100000,
    );
  });

  tearDown(() {
    executor.dispose();
    container.dispose();
  });

  test('production policy requires 16 proof-of-work bits', () {
    expect(kPrivateEventPowDifficulty, 16);
  });

  test('device-signs, mines, and saves a private CustomData event', () async {
    final ciphertext = await service.encryptToDevice('{"trusted":[]}');
    final signed = await service.signAndSave(
      PartialCustomData(
        identifier: kTrustedSignersIdentifier,
        content: ciphertext,
      ),
      publish: false,
    );

    expect(signed.pubkey, signer.pubkey);
    expect(Nip13.isValid(signed.event, minimumDifficulty: 4), isTrue);
    final stored = await container
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<CustomData>(
            authors: {signer.pubkey},
            tags: {
              '#d': {kTrustedSignersIdentifier},
            },
          ).toRequest(),
          source: const LocalSource(),
        );
    expect(stored.single.event.id, signed.event.id);
  });

  test('rejects encrypted stacks carrying a community tag', () async {
    final partial = PartialAppStack.withEncryptedApps(
      name: 'Saved Apps',
      identifier: kAppBookmarksIdentifier,
      apps: const ['32267:pubkey:app'],
    )..event.addTagValue('h', kZapstoreCommunityPubkey);

    await expectLater(
      service.signAndSave(partial, publish: false),
      throwsA(isA<DevicePrivateEventException>()),
    );
  });

  test('rejects unknown private CustomData identifiers', () async {
    await expectLater(
      service.signAndSave(
        PartialCustomData(identifier: 'unknown', content: 'secret'),
        publish: false,
      ),
      throwsA(isA<DevicePrivateEventException>()),
    );
  });
}
