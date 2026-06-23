import 'package:collection/collection.dart';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/services/c1_proof_verification.dart';
import 'package:zapstore/services/background_native_installer.dart';
import 'package:zapstore/services/background_pending_install_store.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';

/// Summary of a background auto-update run.
class BackgroundAutoUpdateResult {
  const BackgroundAutoUpdateResult({
    this.updatedAppIds = const [],
    this.readyAppIds = const [],
    this.failedAppIds = const [],
  });

  final List<String> updatedAppIds;
  final List<String> readyAppIds;
  final List<String> failedAppIds;

  bool get hasWork =>
      updatedAppIds.isNotEmpty ||
      readyAppIds.isNotEmpty ||
      failedAppIds.isNotEmpty;
}

/// Download and apply updates from the WorkManager background isolate.
class BackgroundAutoUpdateExecutor {
  static const _downloadTimeout = Duration(minutes: 15);

  static Future<BackgroundAutoUpdateResult> run({
    required Map<String, Installable> updatableInstallables,
    required Map<String, PackageInfo> installed,
    required Map<String, String> displayNames,
  }) async {
    final updatedAppIds = <String>[];
    final readyAppIds = <String>[];
    final failedAppIds = <String>[];
    final pendingInstalls = await BackgroundPendingInstallStore.loadAll();

    for (final entry in updatableInstallables.entries) {
      final appId = entry.key;
      final target = entry.value;
      final pkg = installed[appId];
      if (pkg == null) continue;

      final pending = pendingInstalls[appId];
      if (!pkg.canInstallSilently && pending?.hash == target.hash) {
        // This exact manual update is already downloaded and verified. It was
        // reported when first staged, so avoid downloading and notifying again.
        continue;
      }

      final displayName = displayNames[appId] ?? pkg.name ?? appId;

      try {
        final filePath = await _downloadApk(appId, target);
        if (filePath == null) {
          failedAppIds.add(appId);
          continue;
        }

        final verified = await BackgroundNativeInstaller.verifyApk(
          filePath: filePath,
          expectedHash: target.hash,
          expectedCertHashes: target.certificateHashes.toList(),
          c1Proof: (await c1ProofPayloadForInstallable(target))?.toMap(),
        );
        if (!verified) {
          await _deleteFile(filePath);
          failedAppIds.add(appId);
          continue;
        }

        if (pkg.canInstallSilently) {
          final result = await BackgroundNativeInstaller.installAndAwait(
            appId: appId,
            filePath: filePath,
            expectedHash: target.hash,
            expectedSize: target.size ?? 0,
            expectedCertHashes: target.certificateHashes.toList(),
            c1Proof: (await c1ProofPayloadForInstallable(target))?.toMap(),
          );
          if (result.success) {
            updatedAppIds.add(appId);
            await _deleteFile(filePath);
          } else {
            await _deleteFile(filePath);
            failedAppIds.add(appId);
          }
        } else {
          final staged = await BackgroundPendingInstallStore.save(
            PendingBackgroundInstall.fromInstallable(
              appId: appId,
              displayName: displayName,
              filePath: filePath,
              target: target,
            ),
          );
          if (staged) {
            readyAppIds.add(appId);
          } else {
            await _deleteFile(filePath);
            failedAppIds.add(appId);
          }
        }
      } catch (e, st) {
        LogService.I.warn(
          'background auto-update failed for app',
          tag: 'background_updates',
          fields: {'appId': appId},
          err: e,
          stack: st,
        );
        failedAppIds.add(appId);
      }
    }

    return BackgroundAutoUpdateResult(
      updatedAppIds: updatedAppIds,
      readyAppIds: readyAppIds,
      failedAppIds: failedAppIds,
    );
  }

  static String? resolveDownloadUrl(Installable target) {
    final first = target.urls.firstOrNull;
    if (first == null || first.isEmpty) return null;
    if (Uri.tryParse(first)?.host == 'cdn.zapstore.dev') return first;
    return 'https://cdn.zapstore.dev/${target.hash}?redirect=true';
  }

  static Future<String?> _downloadApk(String appId, Installable target) async {
    final url = resolveDownloadUrl(target);
    if (url == null) return null;

    final dir = await _downloadDir();
    final fileName = '${target.hash}.apk';
    final filePath = path.join(dir.path, '${appId}_$fileName');
    final file = File(filePath);

    if (await file.exists()) {
      await file.delete();
    }

    final client = http.Client();
    try {
      final request = http.Request('GET', Uri.parse(url));
      request.headers['X-Zapstore-Client'] = 'app';
      request.headers['X-Zapstore-Download-Type'] = 'update';

      final response = await client.send(request).timeout(_downloadTimeout);

      if (response.statusCode != 200) {
        LogService.I.warn(
          'background download failed',
          tag: 'background_updates',
          fields: {'appId': appId, 'status': response.statusCode.toString()},
        );
        return null;
      }

      final sink = file.openWrite();
      try {
        await response.stream.pipe(sink);
      } finally {
        await sink.close();
      }
      return filePath;
    } finally {
      client.close();
    }
  }

  static Future<Directory> _downloadDir() async {
    final support = await getApplicationSupportDirectory();
    final dir = Directory(path.join(support.path, 'background_updates'));
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    return dir;
  }

  static Future<void> _deleteFile(String? filePath) async {
    if (filePath == null) return;
    try {
      final file = File(filePath);
      if (await file.exists()) await file.delete();
    } catch (_) {}
  }
}
