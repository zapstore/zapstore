import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/package_manager/device_capabilities.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('android_package_manager');

  setUp(DeviceCapabilitiesCache.reset);

  tearDown(() {
    DeviceCapabilitiesCache.reset();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
  });

  group('platformTagForAbi', () {
    test('maps Android ABIs to App Catalog platform tags', () {
      expect(platformTagForAbi('arm64-v8a'), 'android-arm64-v8a');
      expect(platformTagForAbi('armeabi-v7a'), 'android-armeabi-v7a');
      expect(platformTagForAbi('x86_64'), 'android-x86_64');
    });

    test('maps unrecognized ABIs rather than dropping them', () {
      // Forward compatibility: an ABI this build has never heard of still
      // maps to the tag an indexer would publish for it.
      expect(platformTagForAbi('riscv64'), 'android-riscv64');
    });
  });

  group('resolvePlatformTag', () {
    test('uses the first ABI, which Android orders best-first', () {
      expect(
        resolvePlatformTag(['arm64-v8a', 'armeabi-v7a', 'armeabi']),
        'android-arm64-v8a',
      );
    });

    test('resolves a 32-bit-only device to its own architecture', () {
      // The Z92 terminal that motivated this: armeabi-v7a with no arm64.
      expect(
        resolvePlatformTag(['armeabi-v7a', 'armeabi']),
        'android-armeabi-v7a',
      );
    });

    test('falls back to arm64 when no ABIs are reported', () {
      expect(resolvePlatformTag([]), kDefaultPlatformTag);
    });

    test('skips blank entries before falling back', () {
      expect(resolvePlatformTag(['', '  ']), kDefaultPlatformTag);
      expect(resolvePlatformTag(['', 'armeabi-v7a']), 'android-armeabi-v7a');
    });

    test('trims whitespace around an ABI', () {
      expect(resolvePlatformTag([' arm64-v8a ']), 'android-arm64-v8a');
    });
  });

  group('DeviceCapabilitiesCache', () {
    test('resolves the platform tag from the native ABI list', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (call) async {
            expect(call.method, 'getSupportedAbis');
            return <Object?>['armeabi-v7a', 'armeabi'];
          });

      final capabilities = await DeviceCapabilitiesCache.initialize();

      expect(capabilities.platformTag, 'android-armeabi-v7a');
      expect(capabilities.supportedAbis, ['armeabi-v7a', 'armeabi']);
    });

    test('falls back to arm64 when the native call fails', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (_) async {
            throw PlatformException(code: 'PLUGIN_DETACHED');
          });

      final capabilities = await DeviceCapabilitiesCache.initialize();

      // Degrades to the behavior Zapstore had before ABI detection rather
      // than leaving the catalog unqueryable.
      expect(capabilities.platformTag, kDefaultPlatformTag);
    });

    test('falls back to arm64 when no plugin is registered', () async {
      // No mock handler installed — this is the background-isolate case where
      // the channel may be unavailable.
      final capabilities = await DeviceCapabilitiesCache.initialize();

      expect(capabilities.platformTag, kDefaultPlatformTag);
    });

    test('reports the default platform tag before initialization', () {
      expect(DeviceCapabilitiesCache.capabilities.platformTag,
          kDefaultPlatformTag);
    });

    test('coalesces overlapping initialization into one native call', () async {
      var calls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, (_) async {
            calls++;
            return <Object?>['armeabi-v7a'];
          });

      final first = DeviceCapabilitiesCache.initialize();
      final second = DeviceCapabilitiesCache.initialize();

      expect(identical(first, second), isTrue);
      await Future.wait([first, second]);

      expect(calls, 1);
    });
  });
}
