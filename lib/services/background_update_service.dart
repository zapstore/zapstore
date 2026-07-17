import 'dart:async';
import 'dart:io' show Directory, File, Platform;
import 'dart:isolate';
import 'dart:ui' as ui;
import 'dart:ui' show PlatformDispatcher;

import 'package:background_downloader/background_downloader.dart' hide Request;
import 'package:flutter/foundation.dart' show FlutterError;
import 'package:flutter/widgets.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:purplebase/purplebase.dart';
import 'package:workmanager/workmanager.dart';
import 'package:zapstore/services/background_auto_update_executor.dart';
import 'package:zapstore/services/background_native_installer.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/package_manager/background_package_manager.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/catalog_fetcher.dart';
import 'package:zapstore/services/settings_service.dart';

/// Legacy notification-only task name (cancelled on init; no longer registered).
const kBackgroundUpdateTaskName = 'dev.zapstore.backgroundUpdateCheck';

/// Unique task name for unmetered background auto-updates
const kBackgroundAutoUpdateTaskName = 'dev.zapstore.backgroundAutoUpdate';

/// Unique task name for weekly cleanup
const kWeeklyCleanupTaskName = 'dev.zapstore.weeklyCleanup';

/// Legacy notification-only task id (cancelled on init; no longer registered).
const kBackgroundUpdateTaskId = 'backgroundUpdateCheck';

/// Unique task identifier for unmetered background auto-updates
const kBackgroundAutoUpdateTaskId = 'backgroundAutoUpdate';

/// Unique one-off task identifier for the first opt-in auto-update run
const kBackgroundAutoUpdateImmediateTaskId = 'backgroundAutoUpdateImmediate';

/// Unique task identifier for weekly cleanup
const kWeeklyCleanupTaskId = 'weeklyCleanup';

/// Stale download threshold for cleanup
const _staleDownloadThreshold = Duration(days: 7);

/// Input data key for AppCatalog relay URLs
const kAppCatalogRelaysKey = 'appCatalogRelays';

/// Holds the isolate error port for the workmanager background
/// isolate. Top-level so the GC cannot collect the port while the
/// task is running.
// ignore: unused_element
RawReceivePort? _workmanagerErrorPort;

/// The entry point for WorkManager background tasks.
/// This MUST be a top-level function (not a class method).
@pragma('vm:entry-point')
void callbackDispatcher() {
  WidgetsFlutterBinding.ensureInitialized();
  ui.DartPluginRegistrant.ensureInitialized();

  // Wire all four error sinks for this background isolate.
  // LogService.init is fire-and-forget — pre-init writes go to the
  // ring buffer and are flushed once disk is ready.
  unawaited(LogService.I.init(isolateName: 'workmanager'));

  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    LogService.I.fatal(
      'uncaught error',
      tag: 'crash',
      fields: const {'source': 'flutter'},
      err: details.exception,
      stack: details.stack,
    );
    LogService.I.flushSync();
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    LogService.I.fatal(
      'uncaught error',
      tag: 'crash',
      fields: const {'source': 'platform_dispatcher'},
      err: error,
      stack: stack,
    );
    LogService.I.flushSync();
    return true;
  };

  final port = RawReceivePort((dynamic pair) {
    if (pair is List && pair.length == 2) {
      final err = pair[0]?.toString() ?? 'unknown';
      final stack = pair[1] == null
          ? null
          : StackTrace.fromString(pair[1].toString());
      LogService.I.fatal(
        'uncaught error',
        tag: 'crash',
        fields: const {'source': 'isolate'},
        err: err,
        stack: stack,
      );
      LogService.I.flushSync();
    }
  });
  Isolate.current.addErrorListener(port.sendPort);
  _workmanagerErrorPort = port;

  runZonedGuarded(
    () {
      Workmanager().executeTask((task, inputData) async {
        try {
          switch (task) {
            case kBackgroundUpdateTaskName:
              // Legacy notification-only worker — no longer does work.
              return true;
            case kBackgroundAutoUpdateTaskName:
            case kBackgroundAutoUpdateImmediateTaskId:
              final relayUrls =
                  (inputData?[kAppCatalogRelaysKey] as List<dynamic>?)
                      ?.cast<String>()
                      .toSet();
              return await _checkForUpdatesInBackground(relayUrls);
            case kWeeklyCleanupTaskName:
              return await _performWeeklyCleanup();
            default:
              return false;
          }
        } catch (e, st) {
          LogService.I.error(
            'background task failed',
            tag: 'workmanager',
            fields: {'task': task},
            err: e,
            stack: st,
          );
          return false;
        } finally {
          // Ensure entries from this task hit disk before the isolate
          // tears down.
          await LogService.I.flush();
        }
      });
    },
    (error, stack) {
      LogService.I.fatal(
        'uncaught error',
        tag: 'crash',
        fields: const {'source': 'zone'},
        err: error,
        stack: stack,
      );
      LogService.I.flushSync();
    },
  );
}

/// Perform weekly cleanup of stale downloads
Future<bool> _performWeeklyCleanup() async {
  try {
    final downloader = FileDownloader();

    // Get all tracked records from background_downloader's database
    final trackedRecords = await downloader.database.allRecords(
      group: FileDownloader.defaultGroup,
    );

    for (final record in trackedRecords) {
      final task = record.task;
      if (task is! DownloadTask) continue;

      final taskAge = DateTime.now().difference(record.task.creationTime);
      if (taskAge > _staleDownloadThreshold) {
        try {
          // Cancel if active
          await downloader.cancelTaskWithId(task.taskId);
        } catch (_) {}

        try {
          // Delete the file if it exists
          final filePath = await task.filePath();
          final file = File(filePath);
          if (await file.exists()) {
            await file.delete();
          }
        } catch (_) {}

        try {
          // Remove from database
          await downloader.database.deleteRecordWithId(task.taskId);
        } catch (_) {}
      }
    }

    // Also clean up orphaned APK files in download directory
    try {
      final cacheDir = await getApplicationCacheDirectory();
      final downloadDir = Directory(
        path.join(cacheDir.path, 'flutter_background_downloader'),
      );

      if (await downloadDir.exists()) {
        final entities = downloadDir.listSync();
        final cutoff = DateTime.now().subtract(_staleDownloadThreshold);

        for (final entity in entities) {
          if (entity is File) {
            final stat = await entity.stat();
            if (stat.modified.isBefore(cutoff)) {
              await entity.delete();
            }
          }
        }
      }
    } catch (_) {
      // Ignore cleanup failures for orphaned files
    }

    return true;
  } catch (e) {
    // Cleanup failed - return false for retry
    return false;
  }
}

/// Background auto-update check — runs in a separate isolate via WorkManager.
///
/// [appCatalogRelays] - Relay URLs resolved from main isolate. Falls back to
/// default relay if not provided.
Future<bool> _checkForUpdatesInBackground(Set<String>? appCatalogRelays) async {
  try {
    final relays = appCatalogRelays ?? {'wss://relay.zapstore.dev'};
    final settings = await SettingsService().load();

    if (!settings.backgroundAutoUpdatesEnabled) {
      return true;
    }

    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(PurplebaseStorageNotifier.new),
        packageManagerProvider.overrideWith(
          (ref) => Platform.isAndroid
              ? BackgroundPackageManager(ref)
              : DummyPackageManager(ref),
        ),
      ],
    );

    try {
      final dir = await getApplicationSupportDirectory();
      final dbPath = path.join(dir.path, 'zapstore.db');

      await container.read(
        initializationProvider(
          StorageConfiguration(
            databasePath: dbPath,
            defaultRelays: {'AppCatalog': relays},
          ),
        ).future,
      );

      final packageManager = container.read(packageManagerProvider.notifier);
      await packageManager.syncInstalledPackages();

      final pmState = container.read(packageManagerProvider);
      if (pmState.installed.isEmpty) {
        return true;
      }

      final storage = container.read(storageNotifierProvider.notifier);
      final catalog = await fetchCatalog(
        storage: storage,
        installedIds: pmState.installed.keys.toSet(),
        platform: packageManager.platform,
        subscriptionPrefix: 'app-bg',
      );

      final updatableInstallables = <String, Installable>{};
      for (final entry in catalog.installableByApp.entries) {
        if (packageManager.hasUpdate(entry.key, entry.value)) {
          updatableInstallables[entry.key] = entry.value;
        }
      }

      if (updatableInstallables.isNotEmpty) {
        final updatableApps = await storage.query(
          RequestFilter<App>(
            tags: {
              '#d': updatableInstallables.keys.toSet(),
              '#f': {packageManager.platform},
            },
          ).toRequest(),
          source: const LocalSource(),
        );

        final displayNames = {
          for (final app in updatableApps)
            app.identifier: app.name ?? app.identifier,
        };
        final result = await BackgroundAutoUpdateExecutor.run(
          updatableInstallables: updatableInstallables,
          installed: pmState.installed,
          displayNames: displayNames,
        );
        await BackgroundNativeInstaller.notifyBackgroundUpdatesCompleted(
          result.updatedAppIds,
        );
      }

      return true;
    } finally {
      container.dispose();
    }
  } catch (e) {
    return false;
  }
}

/// Service for managing background update checks
class BackgroundUpdateService {
  BackgroundUpdateService(this.ref);

  final Ref ref;
  Future<void>? _initializeFuture;

  /// Initialize WorkManager and register periodic task
  Future<void> initialize() => _initializeFuture ??= _initialize();

  Future<void> _initialize() async {
    if (!Platform.isAndroid) {
      // WorkManager only works on Android/iOS, skip on other platforms
      return;
    }

    // Initialize WorkManager
    await Workmanager().initialize(callbackDispatcher);

    // Drop any previously scheduled notification-only checks.
    await Workmanager().cancelByUniqueName(kBackgroundUpdateTaskId);

    // Resolve AppCatalog relays from main isolate to pass to background task
    final appCatalogRelays = await ref
        .read(storageNotifierProvider.notifier)
        .resolveRelays('AppCatalog');

    await _registerAutoUpdatePeriodicTask(
      appCatalogRelays,
      initialDelay: const Duration(hours: 1),
    );

    // Register weekly cleanup task
    await Workmanager().registerPeriodicTask(
      kWeeklyCleanupTaskId,
      kWeeklyCleanupTaskName,
      frequency: const Duration(days: 7),
      constraints: Constraints(
        requiresBatteryNotLow: true,
        requiresCharging: true, // Only run when charging for cleanup
      ),
      existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
      backoffPolicy: BackoffPolicy.exponential,
      initialDelay: const Duration(hours: 24), // Start first cleanup after 24h
    );
  }

  /// Queue the first opted-in auto-update run.
  ///
  /// WorkManager starts it immediately when its unmetered-network constraint
  /// is met. If Wi-Fi is unavailable, it remains queued instead of polling or
  /// waking the app on a timer.
  Future<void> scheduleImmediateAutoUpdate() async {
    if (!Platform.isAndroid) return;

    final settings = await ref.read(settingsServiceProvider).load();
    if (!settings.backgroundAutoUpdatesEnabled) return;

    await initialize();

    final appCatalogRelays = await ref
        .read(storageNotifierProvider.notifier)
        .resolveRelays('AppCatalog');

    await Workmanager().registerOneOffTask(
      kBackgroundAutoUpdateImmediateTaskId,
      kBackgroundAutoUpdateImmediateTaskId,
      constraints: Constraints(
        networkType: NetworkType.unmetered,
        requiresBatteryNotLow: true,
      ),
      existingWorkPolicy: ExistingWorkPolicy.replace,
      backoffPolicy: BackoffPolicy.exponential,
      inputData: {kAppCatalogRelaysKey: appCatalogRelays.toList()},
    );

    // Start the recurring 24-hour cadence after this first run instead of
    // allowing the task registered during app startup to run a duplicate
    // check shortly afterward.
    await Workmanager().cancelByUniqueName(kBackgroundAutoUpdateTaskId);
    await _registerAutoUpdatePeriodicTask(
      appCatalogRelays,
      initialDelay: const Duration(hours: 24),
    );
  }

  Future<void> _registerAutoUpdatePeriodicTask(
    Set<String> appCatalogRelays, {
    required Duration initialDelay,
  }) {
    return Workmanager().registerPeriodicTask(
      kBackgroundAutoUpdateTaskId,
      kBackgroundAutoUpdateTaskName,
      frequency: const Duration(hours: 24),
      constraints: Constraints(
        networkType: NetworkType.unmetered,
        requiresBatteryNotLow: true,
      ),
      existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
      backoffPolicy: BackoffPolicy.exponential,
      initialDelay: initialDelay,
      inputData: {kAppCatalogRelaysKey: appCatalogRelays.toList()},
    );
  }

  /// Cancel background update checks
  Future<void> cancelBackgroundChecks() async {
    await Future.wait([
      Workmanager().cancelByUniqueName(kBackgroundUpdateTaskId),
      Workmanager().cancelByUniqueName(kBackgroundAutoUpdateTaskId),
    ]);
  }
}

/// Provider for the background update service
final backgroundUpdateServiceProvider = Provider<BackgroundUpdateService>(
  BackgroundUpdateService.new,
);
