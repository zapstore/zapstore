import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/package_manager/android_package_manager.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('android_package_manager');
  const eventChannel = MethodChannel('android_package_manager/events');

  tearDown(() async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(methodChannel, null);
    messenger.setMockMethodCallHandler(eventChannel, null);
  });

  test('coalesces overlapping installed-package scans', () async {
    final scanResult = Completer<List<Object?>>();
    var scanCalls = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    messenger.setMockMethodCallHandler(eventChannel, (_) async => null);
    messenger.setMockMethodCallHandler(methodChannel, (call) async {
      expect(call.method, 'getInstalledApps');
      scanCalls++;
      return scanResult.future;
    });

    final container = ProviderContainer(
      overrides: [
        packageManagerProvider.overrideWith(AndroidPackageManager.new),
      ],
    );
    addTearDown(container.dispose);

    final packageManager = container.read(packageManagerProvider.notifier);
    final first = packageManager.syncInstalledPackages();
    final second = packageManager.syncInstalledPackages();

    expect(identical(first, second), isTrue);
    expect(scanCalls, 1);

    scanResult.complete(const []);
    await Future.wait([first, second]);

    expect(scanCalls, 1);
  });

  test(
    'refreshes installed packages after background update completion',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final installedPackagesSynced = Completer<void>();

      messenger.setMockMethodCallHandler(eventChannel, (_) async => null);
      messenger.setMockMethodCallHandler(methodChannel, (call) async {
        expect(call.method, 'getInstalledApps');
        installedPackagesSynced.complete();
        return [
          {
            'packageName': 'place.poster.app',
            'name': 'Poster',
            'versionName': '2.0.0',
            'versionCode': 2,
            'signatureHashes': <String>[],
            'canInstallSilently': true,
          },
        ];
      });

      final container = ProviderContainer(
        overrides: [
          packageManagerProvider.overrideWith(AndroidPackageManager.new),
        ],
      );
      addTearDown(container.dispose);

      final packageManager =
          container.read(packageManagerProvider.notifier)
              as AndroidPackageManager;
      packageManager.handlePlatformEventForTesting({
        'type': 'backgroundUpdatesCompleted',
        'updatedAppIds': ['place.poster.app'],
      });
      await installedPackagesSynced.future;
      await Future<void>.delayed(Duration.zero);

      expect(
        container
            .read(packageManagerProvider)
            .installed['place.poster.app']
            ?.versionCode,
        2,
      );
    },
  );
}
