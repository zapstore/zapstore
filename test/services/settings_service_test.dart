import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/settings_service.dart';

void main() {
  group('LocalSettings', () {
    test('backgroundAutoUpdatesEnabled defaults to false', () {
      const settings = LocalSettings();
      expect(settings.backgroundAutoUpdatesEnabled, isFalse);
    });

    test('round-trips backgroundAutoUpdatesEnabled in JSON', () {
      const settings = LocalSettings(backgroundAutoUpdatesEnabled: true);
      final restored = LocalSettings.fromJson(settings.toJson());
      expect(restored.backgroundAutoUpdatesEnabled, isTrue);
    });

    test('copyWith toggles backgroundAutoUpdatesEnabled', () {
      const settings = LocalSettings();
      final updated = settings.copyWith(backgroundAutoUpdatesEnabled: true);
      expect(updated.backgroundAutoUpdatesEnabled, isTrue);
    });
  });
}
