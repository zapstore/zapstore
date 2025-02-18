import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/utils/version.dart';

void main() {
  group('version parsing', () {
    test('returns correct comparison', () {
      expect(compareVersions("1.2.3", "1.2.3"), 0);
      expect(compareVersions("1.2.3", "1.2.3-alpha"), -1);
      expect(compareVersions("1.2.3-alpha", "1.2.3-beta"), -1);
      expect(compareVersions("1.2.3-beta", "1.2.3-alpha"), 1);
      expect(compareVersions("1.9.0", "1.10.0"), 1);
      expect(compareVersions("1.26.8", "1.27.2"), 1);
      expect(compareVersions("0.2.7", "0.2.11"), 1);
      expect(compareVersions("0.2.11", "0.2.7"), -1);
      expect(compareVersions("2024.9", "2024.10-beta2"), 1);
    });
  });
}
