import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

class InstalledPackagesSnapshot {
  static const _fileName = 'installed_packages_snapshot.json';

  static Future<File> _file() async {
    final dir = await getApplicationSupportDirectory();
    return File(path.join(dir.path, _fileName));
  }

  static Future<void> save(Map<String, PackageInfo> installed) async {
    try {
      final file = await _file();
      final tmp = File('${file.path}.tmp');
      final list = installed.values
          .map(
            (p) => <String, dynamic>{
              'appId': p.appId,
              'name': p.name,
              'version': p.version,
              'versionCode': p.versionCode,
              'signatureHash': p.signatureHash,
              'canInstallSilently': p.canInstallSilently,
            },
          )
          .toList(growable: false);
      final payload = jsonEncode({
        'v': 1,
        'savedAt': DateTime.now().millisecondsSinceEpoch,
        'installed': list,
      });
      await tmp.writeAsString(payload, flush: true);
      if (await file.exists()) {
        await file.delete();
      }
      await tmp.rename(file.path);
    } catch (e) {
      // Best-effort snapshot only.
      if (kDebugMode) {
        debugPrint('[InstalledPackagesSnapshot] Save failed: $e');
      }
    }
  }

  static Future<Map<String, PackageInfo>> load() async {
    try {
      final file = await _file();
      if (!await file.exists()) return {};

      final raw = await file.readAsString();
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return {};
      final version = decoded['v'];
      if (version != null && version is! int && kDebugMode) {
        debugPrint('[InstalledPackagesSnapshot] Unknown schema: $version');
      }
      final installed = decoded['installed'];
      if (installed is! List) return {};

      final result = <String, PackageInfo>{};
      for (final item in installed) {
        if (item is! Map) continue;
        final map = Map<String, dynamic>.from(item);
        final appId = map['appId'] as String?;
        if (appId == null || appId.isEmpty) continue;
        result[appId] = PackageInfo(
          appId: appId,
          name: map['name'] as String?,
          version: (map['version'] as String?) ?? '0.0.0',
          versionCode: map['versionCode'] as int?,
          signatureHash: (map['signatureHash'] as String?) ?? '',
          installTime: null,
          canInstallSilently: (map['canInstallSilently'] as bool?) ?? false,
        );
      }
      return result;
    } catch (e) {
      if (kDebugMode) {
        debugPrint('[InstalledPackagesSnapshot] Load failed: $e');
      }
      return {};
    }
  }
}
