import 'dart:convert';
import 'dart:io';

import 'package:background_downloader/background_downloader.dart';
import 'package:flutter/foundation.dart';
import 'package:models/models.dart';

import 'download_info.dart';

/// Handles persistence and restoration of download state from background_downloader's database
class DownloadPersistence {
  DownloadPersistence(this._downloader, this._storage);

  final FileDownloader _downloader;
  final StorageNotifier _storage;

  /// Restore download state from background_downloader's persisted database.
  /// Returns a map of appId -> DownloadInfo for restored downloads.
  Future<Map<String, DownloadInfo>> restoreState() async {
    final restoredState = <String, DownloadInfo>{};

    try {
      final trackedRecords = await _downloader.database.allRecords(
        group: FileDownloader.defaultGroup,
      );

      for (final record in trackedRecords) {
        final task = record.task;
        if (task is! DownloadTask) continue;

        // Parse metadata JSON to get appId and metadataId
        final metaDataJson = task.metaData;
        if (metaDataJson.isEmpty) {
          await cleanupTask(task);
          continue;
        }

        final (appId, metadataId) = _parseMetadata(metaDataJson);
        if (appId == null) {
          await cleanupTask(task);
          continue;
        }

        // Check if task is stale
        final taskAge = DateTime.now().difference(record.task.creationTime);
        if (taskAge > staleDownloadThreshold) {
          await cleanupTask(task);
          continue;
        }

        // Try to load FileMetadata from purplebase
        final fileMetadata = await _loadFileMetadata(metadataId, task.filename);
        if (fileMetadata == null) {
          await cleanupTask(task);
          continue;
        }

        // Check if file exists for completed tasks
        if (record.status == TaskStatus.complete) {
          if (!await _fileExists(task)) {
            await _downloader.database.deleteRecordWithId(task.taskId);
            continue;
          }
        }

        // Create DownloadInfo
        restoredState[appId] = DownloadInfo(
          appId: appId,
          task: task,
          fileMetadata: fileMetadata,
          status: record.status,
          progress: record.progress,
          isReadyToInstall: record.status == TaskStatus.complete,
        );
      }
    } catch (e) {
      debugPrint('Failed to restore download state: $e');
    }

    return restoredState;
  }

  /// Parse metadata JSON, handling both legacy and new formats
  (String? appId, String? metadataId) _parseMetadata(String metaDataJson) {
    try {
      final decoded = jsonDecode(metaDataJson) as Map<String, dynamic>;
      return (decoded['appId'] as String?, decoded['metadataId'] as String?);
    } catch (_) {
      // Legacy format - metaData is just the appId
      return (metaDataJson, null);
    }
  }

  /// Load FileMetadata from purplebase by ID or hash fallback
  Future<FileMetadata?> _loadFileMetadata(
    String? metadataId,
    String filename,
  ) async {
    // Try by metadataId first
    if (metadataId != null) {
      try {
        final results = _storage.querySync(
          RequestFilter<FileMetadata>(ids: {metadataId}).toRequest(),
        );
        if (results.isNotEmpty) return results.first;
      } catch (_) {}
    }

    // Fallback: extract hash from filename
    final hash = extractHashFromFilename(filename);
    if (hash != null) {
      try {
        final results = _storage.querySync(
          RequestFilter<FileMetadata>(search: hash).toRequest(),
        );
        if (results.isNotEmpty) return results.first;
      } catch (_) {}
    }

    return null;
  }

  /// Check if the downloaded file exists
  Future<bool> _fileExists(DownloadTask task) async {
    try {
      final filePath = await task.filePath();
      return await File(filePath).exists();
    } catch (_) {
      return false;
    }
  }

  /// Clean up a stale or invalid task
  Future<void> cleanupTask(DownloadTask task) async {
    try {
      await _downloader.cancelTaskWithId(task.taskId);
    } catch (_) {}

    try {
      final filePath = await task.filePath();
      final file = File(filePath);
      if (await file.exists()) {
        await file.delete();
      }
    } catch (_) {}

    try {
      await _downloader.database.deleteRecordWithId(task.taskId);
    } catch (_) {}
  }

  /// Extract hash from filename (format: {hash}.apk)
  static String? extractHashFromFilename(String filename) {
    final dotIndex = filename.lastIndexOf('.');
    if (dotIndex > 0) {
      return filename.substring(0, dotIndex);
    }
    return filename.isNotEmpty ? filename : null;
  }

  /// Encode metadata for storage in DownloadTask
  static String encodeMetadata(String appId, String metadataId) {
    return jsonEncode({'appId': appId, 'metadataId': metadataId});
  }
}

