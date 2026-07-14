import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_backup_service.dart';
import 'package:zapstore/utils/debug_utils.dart';

void main() {
  setUpAll(() {
    Model.register(
      kind: 1,
      constructor: Note.fromMap,
      partialConstructor: PartialNote.fromMap,
    );
    Model.register(
      kind: 30267,
      constructor: AppStack.fromMap,
      partialConstructor: PartialAppStack.fromMap,
    );
  });

  test(
    'explicitly decrypts an imperatively loaded Amber bookmark stack',
    () async {
      final container = ProviderContainer(
        overrides: [
          storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
        ],
      );
      addTearDown(container.dispose);
      await container
          .read(storageNotifierProvider.notifier)
          .initialize(StorageConfiguration());
      final amber = Bip340PrivateKeySigner(
        '2' * 64,
        container.read(refProvider),
      );
      await amber.signIn(setAsActive: false);

      final signed = await PartialAppStack.withEncryptedApps(
        name: 'Saved Apps',
        identifier: kAppBookmarksIdentifier,
        apps: const ['32267:publisher:app-one', '32267:publisher:app-two'],
      ).signWith(amber);
      final imperativelyLoaded = AppStack.fromMap(
        signed.event.toMap(),
        container.read(refProvider),
      );

      expect(imperativelyLoaded.privateAppIds, isEmpty);
      expect(await decryptAmberStackAppIds(amber, imperativelyLoaded), [
        '32267:publisher:app-one',
        '32267:publisher:app-two',
      ]);
    },
  );

  test('requires Amber authorization for the recovered device key', () async {
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
      ],
    );
    addTearDown(container.dispose);
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());
    final amber = Bip340PrivateKeySigner('2' * 64, container.read(refProvider));
    await amber.signIn(setAsActive: false);
    final devicePubkey = Utils.derivePublicKey('3' * 64);
    final attackerPubkey = Utils.derivePublicKey('4' * 64);

    final partial = PartialNote('zapstore-device-key-authorization-v1');
    partial.event.addTagValue('device', devicePubkey);
    partial.event.addTagValue('p', amber.pubkey);
    final authorization = await partial.signWith(amber);
    final map = authorization.event.toMap();

    expect(
      validateRecoveryAuthorization(
        container.read(refProvider),
        map,
        amberPubkey: amber.pubkey,
        devicePubkey: devicePubkey,
      ),
      isTrue,
    );
    expect(
      validateRecoveryAuthorization(
        container.read(refProvider),
        map,
        amberPubkey: amber.pubkey,
        devicePubkey: attackerPubkey,
      ),
      isFalse,
    );
  });

  test('cancellation prevents recovery work from restarting', () async {
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
      ],
    );
    addTearDown(container.dispose);
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());
    final amber = Bip340PrivateKeySigner('2' * 64, container.read(refProvider));
    await amber.signIn(setAsActive: false);
    final service = DeviceBackupService()..beginWork();

    service.cancelCurrentWork(container.read(refProvider));

    await expectLater(
      service.fetchRecoveryCandidates(
        ref: container.read(refProvider),
        amberSigner: amber,
      ),
      throwsA(isA<DeviceBackupCancelled>()),
    );
  });
}
