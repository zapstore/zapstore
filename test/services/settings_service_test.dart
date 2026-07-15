import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/settings_service.dart';

void main() {
  group('PortableSettings', () {
    test('backgroundAutoUpdatesEnabled defaults to false', () {
      const settings = PortableSettings();
      expect(settings.backgroundAutoUpdatesEnabled, isFalse);
    });

    test('round-trips backgroundAutoUpdatesEnabled in JSON', () {
      const settings = PortableSettings(backgroundAutoUpdatesEnabled: true);
      final restored = PortableSettings.fromJson(settings.toJson());
      expect(restored.backgroundAutoUpdatesEnabled, isTrue);
    });

    test('copyWith toggles backgroundAutoUpdatesEnabled', () {
      const settings = PortableSettings();
      final updated = settings.copyWith(backgroundAutoUpdatesEnabled: true);
      expect(updated.backgroundAutoUpdatesEnabled, isTrue);
    });

    test('uses lower camel case portable JSON keys', () {
      const settings = PortableSettings(
        installedAppsBackupEnabled: true,
        trustedSigners: {'a'},
      );

      expect(settings.toJson(), {
        'installedAppsBackupEnabled': true,
        'backgroundAutoUpdatesEnabled': false,
        'trustedSigners': ['a'],
      });
    });
  });

  test('temp settings keep log level out of portable data', () {
    const temp = TempSettings(logLevel: LogLevel.info);
    expect(temp.toJson()['logLevel'], 'info');
    expect(const PortableSettings().toJson(), isNot(contains('logLevel')));
  });
}
