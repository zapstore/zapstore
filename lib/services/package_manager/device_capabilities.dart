import 'dart:io';

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
      final totalRamMB = _readTotalRamMB();
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

  /// Read actual total RAM from /proc/meminfo.
  /// Works on Android (and Linux) without any permissions.
  /// Returns 0 on failure (triggers fallback concurrent downloads).
  static int _readTotalRamMB() {
    try {
      final contents = File('/proc/meminfo').readAsStringSync();
      // First line is: MemTotal:       XXXXXXX kB
      final match = RegExp(r'MemTotal:\s+(\d+)\s+kB').firstMatch(contents);
      if (match != null) {
        final ramKB = int.parse(match.group(1)!);
        return ramKB ~/ 1024;
      }
    } catch (_) {
      // /proc/meminfo not available (non-Linux platform, sandbox, etc.)
    }
    return 0;
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
