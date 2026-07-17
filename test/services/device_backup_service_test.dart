import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_backup_service.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/utils/debug_utils.dart';

class _TrackingBackupService extends DeviceBackupService {
  var backupCalls = 0;
  var restorePromptCalls = 0;
  String? fetchedBackup;

  @override
  Future<String?> fetchAmberBackup({
    required Ref ref,
    required Signer amberSigner,
  }) async => fetchedBackup;

  @override
  Future<void> backupDeviceKey({
    required Ref ref,
    required Signer amberSigner,
  }) async {
    backupCalls++;
  }

  @override
  Future<void> promptAndRestoreBackup({
    required Ref ref,
    required String privateKeyHex,
  }) async {
    restorePromptCalls++;
  }
}

class _FakeDeviceKeyService extends DeviceKeyService {
  _FakeDeviceKeyService(this.privateKeyHex);

  final String privateKeyHex;

  @override
  Future<String> getOrCreatePrivateKey() async => privateKeyHex;
}

void main() {
  test('backup exceptions retain a user-safe message', () {
    const exception = DeviceBackupException('Amber backup was unavailable.');
    expect(exception.toString(), 'Amber backup was unavailable.');
  });

  test(
    'recognizes a signed Amber recovery record before replacement',
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
      final ref = container.read(refProvider);
      final amber = Bip340PrivateKeySigner('2' * 64, ref);
      await amber.signIn(setAsActive: false);
      final backup = await PartialCustomData(
        identifier: kDeviceKeyBackupIdentifier,
        content: 'encrypted-device-key',
      ).signWith(amber);

      expect(
        DeviceBackupService.isValidAmberBackup(
          ref: ref,
          backup: backup,
          amberSigner: amber,
        ),
        isTrue,
      );
    },
  );

  test('normal Amber sign-in offers restore when backup key differs', () async {
    final tracking = _TrackingBackupService()..fetchedBackup = '1' * 64;
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
        deviceBackupServiceProvider.overrideWithValue(tracking),
        deviceKeyServiceProvider.overrideWithValue(
          _FakeDeviceKeyService('3' * 64),
        ),
      ],
    );
    addTearDown(container.dispose);
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());
    final ref = container.read(refProvider);
    final amber = Bip340PrivateKeySigner('2' * 64, ref);
    await amber.signIn();

    await maybeOfferDeviceBackup(ref);

    expect(tracking.backupCalls, 0);
    expect(tracking.restorePromptCalls, 1);
  });

  test(
    'normal Amber sign-in does not overwrite when backup matches current key',
    () async {
      final tracking = _TrackingBackupService()..fetchedBackup = '1' * 64;
      final container = ProviderContainer(
        overrides: [
          storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
          deviceBackupServiceProvider.overrideWithValue(tracking),
          deviceKeyServiceProvider.overrideWithValue(
            _FakeDeviceKeyService('1' * 64),
          ),
        ],
      );
      addTearDown(container.dispose);
      await container
          .read(storageNotifierProvider.notifier)
          .initialize(StorageConfiguration());
      final ref = container.read(refProvider);
      final amber = Bip340PrivateKeySigner('2' * 64, ref);
      await amber.signIn();

      await maybeOfferDeviceBackup(ref);

      expect(tracking.backupCalls, 0);
      expect(tracking.restorePromptCalls, 0);
    },
  );

  test('normal Amber sign-in creates a backup when none exists', () async {
    final tracking = _TrackingBackupService()..fetchedBackup = null;
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
        deviceBackupServiceProvider.overrideWithValue(tracking),
      ],
    );
    addTearDown(() {
      // Avoid DeviceStateNotifier dispose reading providers after container
      // teardown; this test only asserts backup creation.
    });
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());
    final ref = container.read(refProvider);
    final amber = Bip340PrivateKeySigner('2' * 64, ref);
    await amber.signIn();

    await maybeOfferDeviceBackup(ref);

    expect(tracking.backupCalls, 1);
    expect(tracking.restorePromptCalls, 0);
  });
}
