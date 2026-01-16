import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

/// Maximum number of crash reports to store locally.
const _maxCrashReports = 99;

/// Directory name for crash reports.
const _crashReportDir = 'crash_reports';

/// Model representing a cached crash report.
class CrashReport {
  CrashReport({
    required this.id,
    required this.timestamp,
    required this.exceptionType,
    required this.message,
    this.stackTrace,
    required this.platform,
    required this.osVersion,
    this.appVersion,
    this.fatal = false,
  });

  factory CrashReport.fromJson(Map<String, dynamic> json) {
    return CrashReport(
      id: json['id'] as String,
      timestamp: DateTime.parse(json['timestamp'] as String),
      exceptionType: json['exceptionType'] as String,
      message: json['message'] as String,
      stackTrace: json['stackTrace'] as String?,
      platform: json['platform'] as String,
      osVersion: json['osVersion'] as String,
      appVersion: json['appVersion'] as String?,
      fatal: json['fatal'] as bool? ?? false,
    );
  }

  factory CrashReport.fromError(
    Object exception,
    StackTrace? stackTrace, {
    String? appVersion,
    bool fatal = false,
  }) {
    return CrashReport(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      timestamp: DateTime.now(),
      exceptionType: exception.runtimeType.toString(),
      message: exception.toString(),
      stackTrace: stackTrace?.toString(),
      platform: Platform.operatingSystem,
      osVersion: Platform.operatingSystemVersion,
      appVersion: appVersion,
      fatal: fatal,
    );
  }

  final String id;
  final DateTime timestamp;
  final String exceptionType;
  final String message;
  final String? stackTrace;
  final String platform;
  final String osVersion;
  final String? appVersion;
  final bool fatal;

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'timestamp': timestamp.toIso8601String(),
      'exceptionType': exceptionType,
      'message': message,
      'stackTrace': stackTrace,
      'platform': platform,
      'osVersion': osVersion,
      'appVersion': appVersion,
      'fatal': fatal,
    };
  }

  /// Format the crash report for sending, with optional user comment.
  String toReportString({String? userComment}) {
    final buffer = StringBuffer();
    buffer.writeln('=== ZAPSTORE CRASH REPORT ===');
    buffer.writeln('Timestamp: ${timestamp.toUtc().toIso8601String()}');
    buffer.writeln('Platform: $platform');
    buffer.writeln('OS Version: $osVersion');
    if (appVersion != null) buffer.writeln('App Version: $appVersion');
    if (fatal) buffer.writeln('Fatal: yes');
    buffer.writeln();

    if (userComment != null && userComment.trim().isNotEmpty) {
      buffer.writeln('User Comment:');
      buffer.writeln(userComment.trim());
      buffer.writeln();
    }

    buffer.writeln('Exception: $exceptionType');
    buffer.writeln(message);
    buffer.writeln();

    if (stackTrace != null) {
      buffer.writeln('Stack Trace:');
      final stackLines = stackTrace!.split('\n');
      final limitedStack = stackLines.take(50).join('\n');
      buffer.writeln(limitedStack);
      if (stackLines.length > 50) {
        buffer.writeln('... (${stackLines.length - 50} more lines)');
      }
    }

    return buffer.toString();
  }
}

/// Service for caching crash reports locally until user consents to send them.
class CrashReportCacheService {
  CrashReportCacheService();

  int _nextSlot = 0;

  Future<Directory> _getCrashDir() async {
    final dir = await getApplicationDocumentsDirectory();
    final crashDir = Directory('${dir.path}/$_crashReportDir');
    if (!await crashDir.exists()) {
      await crashDir.create(recursive: true);
    }
    return crashDir;
  }

  /// Cache a crash report to local storage.
  Future<void> cacheCrash(CrashReport report) async {
    try {
      final crashDir = await _getCrashDir();
      final slot = _nextSlot;
      _nextSlot = (_nextSlot + 1) % _maxCrashReports;

      final file = File('${crashDir.path}/crash_$slot.json');
      await file.writeAsString(jsonEncode(report.toJson()));
    } catch (_) {
      // Silently fail - we don't want caching to cause more errors
    }
  }

  /// Get all pending crash reports, sorted by timestamp (newest first).
  Future<List<CrashReport>> getPendingCrashes() async {
    try {
      final crashDir = await _getCrashDir();
      if (!await crashDir.exists()) {
        return [];
      }

      final files = await crashDir
          .list()
          .where((entity) =>
              entity is File && entity.path.endsWith('.json'))
          .cast<File>()
          .toList();

      final crashes = <CrashReport>[];
      for (final file in files) {
        try {
          final content = await file.readAsString();
          final json = jsonDecode(content) as Map<String, dynamic>;
          crashes.add(CrashReport.fromJson(json));
        } catch (_) {
          // Skip corrupted files
        }
      }

      // Sort by timestamp, newest first
      crashes.sort((a, b) => b.timestamp.compareTo(a.timestamp));
      return crashes;
    } catch (_) {
      return [];
    }
  }

  /// Clear a specific crash report by ID.
  Future<void> clearCrash(String crashId) async {
    try {
      final crashDir = await _getCrashDir();
      if (!await crashDir.exists()) return;

      final files = await crashDir
          .list()
          .where((entity) =>
              entity is File && entity.path.endsWith('.json'))
          .cast<File>()
          .toList();

      for (final file in files) {
        try {
          final content = await file.readAsString();
          final json = jsonDecode(content) as Map<String, dynamic>;
          if (json['id'] == crashId) {
            await file.delete();
            return;
          }
        } catch (_) {
          // Continue to next file
        }
      }
    } catch (_) {
      // Silently fail
    }
  }

  /// Clear all cached crash reports.
  Future<void> clearAllCrashes() async {
    try {
      final crashDir = await _getCrashDir();
      if (!await crashDir.exists()) return;

      final files = await crashDir
          .list()
          .where((entity) =>
              entity is File && entity.path.endsWith('.json'))
          .cast<File>()
          .toList();

      for (final file in files) {
        try {
          await file.delete();
        } catch (_) {
          // Continue to next file
        }
      }
    } catch (_) {
      // Silently fail
    }
  }
}

final crashReportCacheServiceProvider = Provider<CrashReportCacheService>(
  (ref) => CrashReportCacheService(),
);

/// Provider for pending crash reports.
/// Use `ref.invalidate(pendingCrashesProvider)` after clearing crashes.
final pendingCrashesProvider = FutureProvider<List<CrashReport>>((ref) async {
  final cacheService = ref.watch(crashReportCacheServiceProvider);
  return cacheService.getPendingCrashes();
});
