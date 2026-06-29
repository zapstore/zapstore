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
}
