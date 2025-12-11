import 'package:zapstore/services/package_manager/package_manager.dart';

/// Dummy implementation of PackageManager for testing
final class DummyPackageManager extends PackageManager {
  DummyPackageManager(super.ref) {
    // Initialize state with mock packages
    state = [
      PackageInfo(
        appId: 'com.example.test',
        version: '1.0.0',
        versionCode: 1,
        signatureHash: 'dummy_signature_1',
        installTime: DateTime.now().subtract(const Duration(days: 1)),
      ),
      PackageInfo(
        appId: 'dev.zapstore.alpha',
        version: '1.0.0',
        versionCode: 1,
        signatureHash: 'dummy_signature_2',
        installTime: DateTime.now().subtract(const Duration(hours: 12)),
      ),
      PackageInfo(
        appId: 'com.dummy.browser',
        version: '2.1.0',
        versionCode: 210,
        signatureHash: 'dummy_signature_3',
        installTime: DateTime.now().subtract(const Duration(days: 5)),
      ),
    ];
  }

  @override
  String get platform => 'android-arm64-v8a';

  @override
  String get packageExtension => '.apk';

  @override
  Future<void> install(
    String appId,
    String filePath, {
    required String expectedHash,
    required int expectedSize,
    bool skipVerification = false,
  }) async {
    // Mock implementation - just add to list if not already present
    if (!state.any((p) => p.appId == appId)) {
      state = [
        ...state,
        PackageInfo(
          appId: appId,
          version: '1.0.0',
          versionCode: 1,
          signatureHash: 'mock_signature',
          installTime: DateTime.now(),
        ),
      ];
    }
  }

  @override
  Future<void> uninstall(String appId) async {
    state = state.where((p) => p.appId != appId).toList();
  }

  @override
  Future<void> launchApp(String appId) async {
    // Mock implementation - just simulate launching
    if (!state.any((p) => p.appId == appId)) {
      throw Exception('App not installed: $appId');
    }
    // In a real implementation, this would launch the app
    await Future.delayed(const Duration(milliseconds: 500));
  }

  @override
  Future<void> requestPermission() async {
    // Mock implementation - always succeeds
  }

  @override
  Future<bool> hasPermission() async {
    // Mock implementation - always has permission
    return true;
  }

  @override
  bool get supportsSilentInstall => false;

  @override
  Future<bool> canInstallSilently(String appId) async {
    // Mock implementation - always returns false for dummy manager
    return false;
  }

  @override
  Future<void> syncInstalledPackages() async {
    // No-op for dummy implementation
  }
}
