import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';

/// Device capability information for adaptive behavior.
/// Cached at startup since these values don't change during session.
class DeviceCapabilities {
  const DeviceCapabilities({
    required this.totalRamMB,
    required this.maxConcurrentDownloads,
  });

  /// Total device RAM in megabytes
  final int totalRamMB;

  /// Recommended concurrent downloads based on device capability
  final int maxConcurrentDownloads;

  /// Default capabilities for fallback (conservative)
  static const fallback = DeviceCapabilities(
    totalRamMB: 0,
    maxConcurrentDownloads: 2,
  );

  @override
  String toString() =>
      'DeviceCapabilities(ram: ${totalRamMB}MB, maxDownloads: $maxConcurrentDownloads)';
}

/// Singleton cache for device capabilities.
/// Call [DeviceCapabilitiesCache.initialize] once at app startup.
class DeviceCapabilitiesCache {
  DeviceCapabilitiesCache._();

  static DeviceCapabilities? _cached;

  /// Get cached capabilities, or fallback if not initialized.
  static DeviceCapabilities get capabilities =>
      _cached ?? DeviceCapabilities.fallback;

  /// Initialize device capabilities. Safe to call multiple times.
  static Future<DeviceCapabilities> initialize() async {
    if (_cached != null) return _cached!;

    try {
      final deviceInfo = DeviceInfoPlugin();
      final info = await deviceInfo.deviceInfo;

      int totalRamMB = 0;

      // Extract RAM from platform-specific info
      if (info is AndroidDeviceInfo) {
        // Android: systemFeatures doesn't have RAM directly, but we can
        // check for low RAM device flag via isPhysicalDevice and other heuristics.
        // The device_info_plus package on Android doesn't expose total RAM directly.
        // We'll use a heuristic based on SDK version and physical device status.
        totalRamMB = _estimateAndroidRam(info);
      } else {
        // Non-Android platforms: use conservative default
        totalRamMB = 4000;
      }

      final maxDownloads = _calculateMaxConcurrentDownloads(totalRamMB);

      _cached = DeviceCapabilities(
        totalRamMB: totalRamMB,
        maxConcurrentDownloads: maxDownloads,
      );

      debugPrint('[DeviceCapabilities] Initialized: $_cached');
      return _cached!;
    } catch (e) {
      debugPrint('[DeviceCapabilities] Failed to detect: $e, using fallback');
      _cached = DeviceCapabilities.fallback;
      return _cached!;
    }
  }

  /// Estimate Android RAM based on device characteristics.
  /// Since device_info_plus doesn't expose RAM directly on Android,
  /// we use heuristics based on device age and type.
  static int _estimateAndroidRam(AndroidDeviceInfo info) {
    // SDK version gives us a rough idea of device era
    final sdkInt = info.version.sdkInt;

    // Emulators and non-physical devices: assume decent RAM
    if (!info.isPhysicalDevice) {
      return 4000;
    }

    // Low-end device indicators
    final isLowEnd = info.supportedAbis.length == 1 || // Single ABI = older device
        sdkInt < 28; // Android 9 or older

    if (isLowEnd) {
      return 2000; // Assume 2GB for low-end
    }

    // Modern devices (Android 10+)
    if (sdkInt >= 29) {
      // Most devices from 2019+ have at least 4GB
      // High-end (2021+, Android 12+) typically have 6-8GB
      if (sdkInt >= 31) {
        return 6000; // Android 12+ devices
      }
      return 4000; // Android 10-11 devices
    }

    // Default for Android 9 devices
    return 3000;
  }

  /// Calculate max concurrent downloads based on RAM tier.
  static int _calculateMaxConcurrentDownloads(int totalRamMB) {
    // Conservative tiers to prevent crashes on constrained devices
    if (totalRamMB < 3000) return 1; // < 3GB: single download
    if (totalRamMB < 4000) return 2; // 3-4GB: 2 concurrent
    if (totalRamMB < 6000) return 3; // 4-6GB: 3 concurrent
    return 4; // 6GB+: 4 concurrent
  }

  /// Reset cache (for testing)
  @visibleForTesting
  static void reset() {
    _cached = null;
  }
}
