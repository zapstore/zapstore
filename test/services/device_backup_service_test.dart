import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_backup_service.dart';
import 'package:zapstore/utils/debug_utils.dart';

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
}
