import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:zapstore/services/log_service.dart';

/// Nostr `f` tag for 64-bit ARM — Zapstore's historical hardcoded target, and
/// the fallback used whenever the device's ABIs cannot be determined.
const kDefaultPlatformTag = 'android-arm64-v8a';

/// Maps an Android ABI (a `Build.SUPPORTED_ABIS` entry such as `armeabi-v7a`)
/// to the Nostr `f` tag the App Catalog indexes it under.
///
/// Deliberately unvalidated: the tag is always `android-<abi>`, so an ABI this
/// build has never heard of still maps to the tag an indexer would publish.
String platformTagForAbi(String abi) => 'android-$abi';

/// Resolves the platform tag for a device from its supported ABIs.
///
/// Android orders `Build.SUPPORTED_ABIS` best-first, so the first usable entry
/// is the device's primary ABI. Falls back to [kDefaultPlatformTag] when the
/// list is empty or holds nothing usable.
String resolvePlatformTag(List<String> supportedAbis) {
  for (final abi in supportedAbis) {
    final trimmed = abi.trim();
    if (trimmed.isNotEmpty) return platformTagForAbi(trimmed);
  }
  return kDefaultPlatformTag;
}

/// Device capability information for adaptive behavior.
/// Cached at startup since these values don't change during session.
class DeviceCapabilities {
  const DeviceCapabilities({
    required this.totalRamMB,
    required this.maxConcurrentDownloads,
    this.platformTag = kDefaultPlatformTag,
    this.supportedAbis = const [],
  });

  /// Total device RAM in megabytes
  final int totalRamMB;

  /// Recommended concurrent downloads based on device capability
  final int maxConcurrentDownloads;

  /// Nostr `f` tag for this device's primary ABI. Catalog queries filter on
  /// exactly one tag, so a 32-bit device asks for 32-bit apps at the same
  /// query cost a 64-bit device pays today.
  final String platformTag;

  /// Device ABIs, best-first, as reported by `Build.SUPPORTED_ABIS`.
  final List<String> supportedAbis;

  /// Default capabilities for fallback (conservative)
  static const fallback = DeviceCapabilities(
    totalRamMB: 0,
    maxConcurrentDownloads: 2,
  );

  @override
  String toString() =>
      'DeviceCapabilities(ram: ${totalRamMB}MB, maxDownloads: $maxConcurrentDownloads, '
      'platform: $platformTag)';
}

/// Singleton cache for device capabilities.
/// Call [DeviceCapabilitiesCache.initialize] once at app startup.
class DeviceCapabilitiesCache {
  DeviceCapabilitiesCache._();

  /// Shared with the native package manager plugin, which is reachable from
  /// both the UI engine and the WorkManager background isolate.
  static const _methodChannel = MethodChannel('android_package_manager');

  static DeviceCapabilities? _cached;
  static Future<DeviceCapabilities>? _initInFlight;

  /// Get cached capabilities, or fallback if not initialized.
  static DeviceCapabilities get capabilities =>
      _cached ?? DeviceCapabilities.fallback;

  /// Initialize device capabilities. Safe to call multiple times; overlapping
  /// callers share a single native round-trip.
  static Future<DeviceCapabilities> initialize() {
    final cached = _cached;
    if (cached != null) return Future.value(cached);

    final inFlight = _initInFlight;
    if (inFlight != null) return inFlight;

    late final Future<DeviceCapabilities> operation;
    operation = _initialize().whenComplete(() {
      if (identical(_initInFlight, operation)) {
        _initInFlight = null;
      }
    });
    _initInFlight = operation;
    return operation;
  }

  static Future<DeviceCapabilities> _initialize() async {
    // Read outside the try below so a channel failure degrades to the default
    // platform tag without also discarding the RAM reading.
    final supportedAbis = await _readSupportedAbis();

    try {
      final totalRamMB = _readTotalRamMB();
      final maxDownloads = _calculateMaxConcurrentDownloads(totalRamMB);
      final platformTag = resolvePlatformTag(supportedAbis);

      _cached = DeviceCapabilities(
        totalRamMB: totalRamMB,
        maxConcurrentDownloads: maxDownloads,
        platformTag: platformTag,
        supportedAbis: List.unmodifiable(supportedAbis),
      );

      LogService.I.debug(
        'device capabilities initialised',
        tag: 'device',
        fields: {
          'totalRamMB': totalRamMB,
          'maxConcurrentDownloads': maxDownloads,
          'platformTag': platformTag,
          'supportedAbis': supportedAbis,
        },
      );
      return _cached!;
    } catch (e, st) {
      LogService.I.warn(
        'device capabilities detection failed, using fallback',
        tag: 'device',
        err: e,
        stack: st,
      );
      _cached = DeviceCapabilities.fallback;
      return _cached!;
    }
  }

  /// Read supported ABIs from the native plugin.
  /// Returns an empty list on any failure, which resolves to
  /// [kDefaultPlatformTag] — the behavior Zapstore had before ABI detection.
  static Future<List<String>> _readSupportedAbis() async {
    try {
      final abis = await _methodChannel
          .invokeMethod<List<Object?>>('getSupportedAbis')
          .timeout(const Duration(seconds: 5));
      return abis?.whereType<String>().toList() ?? const [];
    } catch (e) {
      LogService.I.warn(
        'supported ABI detection failed, defaulting to $kDefaultPlatformTag',
        tag: 'device',
        err: e,
      );
      return const [];
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
    _initInFlight = null;
  }
}
