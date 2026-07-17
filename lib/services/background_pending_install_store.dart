import 'dart:convert';
import 'dart:io';

import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/utils/extensions.dart';

/// A manual update downloaded and verified in the background, awaiting user
/// confirmation via the Android system install dialog.
class PendingBackgroundInstall {
  const PendingBackgroundInstall({
    required this.appId,
    required this.displayName,
    required this.filePath,
    required this.hash,
    required this.size,
    required this.version,
    required this.versionCode,
    required this.certificateHashes,
    required this.installableId,
  });

  final String appId;
  final String displayName;
  final String filePath;
  final String hash;
  final int size;
  final String version;
  final int? versionCode;
  final List<String> certificateHashes;
  final String installableId;

  Map<String, dynamic> toJson() => {
    'appId': appId,
    'displayName': displayName,
    'filePath': filePath,
    'hash': hash,
    'size': size,
    'version': version,
    if (versionCode != null) 'versionCode': versionCode,
    'certificateHashes': certificateHashes,
    'installableId': installableId,
  };

  factory PendingBackgroundInstall.fromJson(Map<String, dynamic> json) {
    return PendingBackgroundInstall(
      appId: json['appId'] as String,
      displayName: json['displayName'] as String? ?? json['appId'] as String,
      filePath: json['filePath'] as String,
      hash: json['hash'] as String,
      size: (json['size'] as num?)?.toInt() ?? 0,
      version: json['version'] as String? ?? '0.0.0',
      versionCode: (json['versionCode'] as num?)?.toInt(),
      certificateHashes:
          (json['certificateHashes'] as List?)?.cast<String>() ?? const [],
      installableId: json['installableId'] as String? ?? json['hash'] as String,
    );
  }

  static PendingBackgroundInstall fromInstallable({
    required String appId,
    required String displayName,
    required String filePath,
    required Installable target,
  }) {
    return PendingBackgroundInstall(
      appId: appId,
      displayName: displayName,
      filePath: filePath,
      hash: target.hash,
      size: target.size ?? 0,
      version: target.version,
      versionCode: target.versionCode,
      certificateHashes: target.certificateHashes.toList(),
      installableId: target.id,
    );
  }
}

/// Persists manual background installs until the user installs from Updates.
class BackgroundPendingInstallStore {
  static const _fileName = 'background_pending_installs.json';

  static Future<File> _file() async {
    final dir = await getApplicationSupportDirectory();
    return File(path.join(dir.path, _fileName));
  }

  static Future<Map<String, PendingBackgroundInstall>> loadAll() async {
    try {
      final file = await _file();
      if (!await file.exists()) return {};
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map) return {};
      final entries = decoded['pending'];
      if (entries is! Map) return {};
      final result = <String, PendingBackgroundInstall>{};
      for (final entry in entries.entries) {
        if (entry.value is! Map) continue;
        try {
          final pending = PendingBackgroundInstall.fromJson(
            Map<String, dynamic>.from(entry.value as Map),
          );
          if (await File(pending.filePath).exists()) {
            result[pending.appId] = pending;
          }
        } catch (_) {}
      }
      return result;
    } catch (e, st) {
      LogService.I.warn(
        'failed to load pending background installs',
        tag: 'background_updates',
        err: e,
        stack: st,
      );
      return {};
    }
  }

  static Future<bool> save(PendingBackgroundInstall pending) async {
    final all = await loadAll();
    final previous = all[pending.appId];
    all[pending.appId] = pending;
    final saved = await _writeAll(all);

    if (saved && previous != null && previous.filePath != pending.filePath) {
      try {
        final oldFile = File(previous.filePath);
        if (await oldFile.exists()) await oldFile.delete();
      } catch (e, st) {
        LogService.I.warn(
          'failed to delete superseded background update',
          tag: 'background_updates',
          fields: {'appId': pending.appId},
          err: e,
          stack: st,
        );
      }
    }
    return saved;
  }

  static Future<void> remove(String appId) async {
    final all = await loadAll();
    if (all.remove(appId) == null) return;
    await _writeAll(all);
  }

  static Future<void> removeMany(Iterable<String> appIds) async {
    final all = await loadAll();
    var changed = false;
    for (final appId in appIds) {
      if (all.remove(appId) != null) changed = true;
    }
    if (!changed) return;
    await _writeAll(all);
  }

  static Future<bool> _writeAll(
    Map<String, PendingBackgroundInstall> all,
  ) async {
    try {
      final file = await _file();
      final tmp = File('${file.path}.tmp');
      final payload = jsonEncode({
        'v': 1,
        'pending': {for (final e in all.entries) e.key: e.value.toJson()},
      });
      await tmp.writeAsString(payload, flush: true);
      if (await file.exists()) await file.delete();
      await tmp.rename(file.path);
      return true;
    } catch (e, st) {
      LogService.I.warn(
        'failed to save pending background installs',
        tag: 'background_updates',
        err: e,
        stack: st,
      );
      return false;
    }
  }
}
