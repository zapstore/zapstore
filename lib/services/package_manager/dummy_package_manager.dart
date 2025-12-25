import 'package:models/models.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

/// Dummy implementation of PackageManager for testing and non-Android platforms
final class DummyPackageManager extends PackageManager {
  DummyPackageManager(super.ref) {
    state = PackageManagerState(
      installed: {
        'com.example.test': const PackageInfo(
          appId: 'com.example.test',
          name: 'Test App',
          version: '1.0.0',
          versionCode: 1,
          signatureHash: 'dummy_signature_1',
        ),
        'dev.zapstore.alpha': const PackageInfo(
          appId: 'dev.zapstore.alpha',
          name: 'Zapstore',
          version: '1.0.0',
          versionCode: 1,
          signatureHash: 'dummy_signature_2',
        ),
        'com.dummy.browser': const PackageInfo(
          appId: 'com.dummy.browser',
          name: 'Dummy Browser',
          version: '2.1.0',
          versionCode: 210,
          signatureHash: 'dummy_signature_3',
        ),
      },
      operations: const {},
    );
  }

  @override
  String get platform => 'android-arm64-v8a';

  @override
  String get packageExtension => '.apk';

  @override
  bool get supportsSilentInstall => false;

  @override
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    required FileMetadata target,
  }) async {
    // Mock: simulate install delay then add to installed
    await Future.delayed(const Duration(milliseconds: 500));

    final newInstalled = Map<String, PackageInfo>.from(state.installed);
    newInstalled[appId] = PackageInfo(
      appId: appId,
      version: target.version,
      versionCode: target.versionCode,
      signatureHash: 'mock_signature',
      installTime: DateTime.now(),
    );
    state = state.copyWith(installed: newInstalled);

    // Clear the operation since install completed
    clearOperation(appId);
  }

  @override
  Future<void> uninstall(String appId) async {
    await Future.delayed(const Duration(milliseconds: 300));

    final newInstalled = Map<String, PackageInfo>.from(state.installed);
    newInstalled.remove(appId);
    state = state.copyWith(installed: newInstalled);
  }

  @override
  Future<void> launchApp(String appId) async {
    if (!state.installed.containsKey(appId)) {
      throw Exception('App not installed: $appId');
    }
    await Future.delayed(const Duration(milliseconds: 500));
  }

  @override
  Future<void> requestPermission() async {
    // Mock: always succeeds
  }

  @override
  Future<bool> hasPermission() async => true;

  @override
  Future<void> syncInstalledPackages() async {
    // No-op for dummy implementation
  }
}
