import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/catalog_fetcher.dart';

void main() {
  group('supportsPlatform', () {
    test('accepts an asset built for the device architecture', () {
      expect(
        supportsPlatform({'android-armeabi-v7a'}, 'android-armeabi-v7a'),
        isTrue,
      );
    });

    test('rejects an asset built for another architecture', () {
      // The regression this guards: a 32-bit device was offered Zapstore's own
      // arm64 APK (versionCode 3007) as an "update" to the installed
      // armeabi-v7a build (2007). --split-per-abi offsets versionCode by ABI,
      // so arm64 always outranks armeabi-v7a numerically. Compatibility has to
      // be decided before any versionCode comparison, or the higher number
      // always wins and the install is impossible.
      expect(
        supportsPlatform({'android-arm64-v8a'}, 'android-armeabi-v7a'),
        isFalse,
      );
    });

    test('accepts a multi-architecture asset that lists the device', () {
      expect(
        supportsPlatform(
          {'android-arm64-v8a', 'android-armeabi-v7a', 'android-x86_64'},
          'android-armeabi-v7a',
        ),
        isTrue,
      );
    });

    test('accepts untagged assets so legacy apps keep installing', () {
      // Older 1063 events predate per-architecture tagging. Excluding them
      // would hide apps that install fine today.
      expect(supportsPlatform({}, 'android-armeabi-v7a'), isTrue);
    });

    test('does not match on substrings', () {
      // 'android-x86' must not satisfy a device on 'android-x86_64'.
      expect(supportsPlatform({'android-x86'}, 'android-x86_64'), isFalse);
    });

    test('is unchanged for arm64 devices', () {
      expect(
        supportsPlatform({'android-arm64-v8a'}, 'android-arm64-v8a'),
        isTrue,
      );
      expect(
        supportsPlatform({'android-armeabi-v7a'}, 'android-arm64-v8a'),
        isFalse,
      );
    });
  });
}
