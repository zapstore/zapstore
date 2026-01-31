import 'dart:io' show Directory, File, Platform;
import 'dart:ui' as ui;

import 'package:background_downloader/background_downloader.dart' hide Request;
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter/widgets.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:purplebase/purplebase.dart';
import 'package:workmanager/workmanager.dart';
import 'package:zapstore/services/package_manager/background_package_manager.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/utils/extensions.dart';

/// Unique task name for background update checking
const kBackgroundUpdateTaskName = 'dev.zapstore.backgroundUpdateCheck';

/// Unique task name for weekly cleanup
const kWeeklyCleanupTaskName = 'dev.zapstore.weeklyCleanup';

/// Unique task identifier
const kBackgroundUpdateTaskId = 'backgroundUpdateCheck';

/// Unique task identifier for weekly cleanup
const kWeeklyCleanupTaskId = 'weeklyCleanup';

/// Notification channel for update notifications
const kUpdateNotificationChannelId = 'zapstore_updates';
const kUpdateNotificationChannelName = 'App Updates';
const kUpdateNotificationChannelDescription =
    'Notifications for available app updates';

/// Stale download threshold for cleanup
const _staleDownloadThreshold = Duration(days: 7);

/// Threshold for re-notifying about the same updates (72 hours)
const _notificationReminderThreshold = Duration(hours: 72);

/// Input data key for AppCatalog relay URLs
const kAppCatalogRelaysKey = 'appCatalogRelays';

/// The entry point for WorkManager background tasks.
/// This MUST be a top-level function (not a class method).
@pragma('vm:entry-point')
void callbackDispatcher() {
  WidgetsFlutterBinding.ensureInitialized();
  ui.DartPluginRegistrant.ensureInitialized();
  Workmanager().executeTask((task, inputData) async {
    switch (task) {
      case kBackgroundUpdateTaskName:
        final relayUrls = (inputData?[kAppCatalogRelaysKey] as List<dynamic>?)
            ?.cast<String>()
            .toSet();
        return await _checkForUpdatesInBackground(relayUrls);
      case kWeeklyCleanupTaskName:
        return await _performWeeklyCleanup();
      default:
        return Future.value(false);
    }
  });
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

/// Background update check logic - runs in a separate isolate.
/// Note: Cannot directly reuse [CategorizedAppsNotifier] since this runs
/// in an isolated WorkManager context without the main app's Riverpod setup.
///
/// [appCatalogRelays] - Relay URLs resolved from main isolate. Falls back to
/// default relay if not provided.
Future<bool> _checkForUpdatesInBackground(Set<String>? appCatalogRelays) async {
  try {
    // Use provided relays or fall back to default
    final relays = appCatalogRelays ?? {'wss://relay.zapstore.dev'};

    // Create a fresh provider container for background work
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
      // Initialize Purplebase with same DB path as main app
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

      // Get installed packages directly from Android system
      final packageManager = container.read(packageManagerProvider.notifier);
      await packageManager.syncInstalledPackages();

      final pmState = container.read(packageManagerProvider);
      if (pmState.installed.isEmpty) {
        return true; // No installed apps to check
      }

      final installedIds = pmState.installed.keys.toSet();

      // Query for apps with updates from relay
      final storage = container.read(storageNotifierProvider.notifier);
      final apps = await storage.query(
        RequestFilter<App>(
          tags: {
            '#d': installedIds,
            '#f': {packageManager.platform},
          },
        ).toRequest(),
        source: const RemoteSource(relays: 'AppCatalog', stream: false),
      );

      // Load releases and their metadata/assets (required for hasUpdate)
      if (apps.isNotEmpty) {
        final releaseFilters = apps
            .map((app) => app.latestRelease.req?.filters.firstOrNull)
            .nonNulls
            .toList();
        if (releaseFilters.isNotEmpty) {
          final List<Release> releases = await storage.query(
            Request<Release>(releaseFilters),
            source: const RemoteSource(relays: 'AppCatalog', stream: false),
          );

          final metadataFilters = releases
              .map((r) => r.latestMetadata.req?.filters.firstOrNull)
              .nonNulls
              .toList();
          if (metadataFilters.isNotEmpty) {
            await storage.query(
              Request<FileMetadata>(metadataFilters),
              source: const RemoteSource(relays: 'AppCatalog', stream: false),
            );
          }

          final assetFilters = releases
              .map((r) => r.latestAsset.req?.filters.firstOrNull)
              .nonNulls
              .toList();
          if (assetFilters.isNotEmpty) {
            await storage.query(
              Request<SoftwareAsset>(assetFilters),
              source: const RemoteSource(relays: 'AppCatalog', stream: false),
            );
          }
        }
      }

      // Re-query apps from local to ensure relationships are loaded
      final appsWithRelations = await storage.query(
        RequestFilter<App>(
          tags: {
            '#d': installedIds,
            '#f': {packageManager.platform},
          },
        ).toRequest(),
        source: const LocalSource(),
      );

      // Collect apps with updates
      final updatableApps = appsWithRelations.where((app) => app.hasUpdate).toList();

      // Show notification if updates found (throttled to once per 72h)
      if (updatableApps.isNotEmpty) {
        await _showUpdateNotificationIfNeeded(updatableApps);
      }

      return true;
    } finally {
      container.dispose();
    }
  } catch (e) {
    // Background task failed - return false to indicate failure
    // WorkManager may retry based on configuration
    return false;
  }
}

/// Show a local notification for available updates, throttled to once per 72h.
Future<void> _showUpdateNotificationIfNeeded(List<App> updates) async {
  final secureStorage = SecureStorageService();

  // Skip if notified recently
  final lastNotified = await secureStorage.getLastUpdateNotificationTime();
  if (lastNotified != null &&
      DateTime.now().difference(lastNotified) < _notificationReminderThreshold) {
    return;
  }

  // Show the notification
  final plugin = FlutterLocalNotificationsPlugin();
  const initSettings = InitializationSettings(
    android: AndroidInitializationSettings('@mipmap/ic_launcher'),
  );
  await plugin.initialize(initSettings);
  await _ensureUpdateNotificationChannel(plugin);

  final appNames = updates.map((a) => a.name ?? a.identifier).toList();
  final title = updates.length == 1
      ? '1 app update available'
      : '${updates.length} app updates available';
  final body = appNames.length <= 3
      ? appNames.join(', ')
      : '${appNames.take(3).join(', ')} and ${appNames.length - 3} more';

  await plugin.show(
    0,
    title,
    body,
    const NotificationDetails(
      android: AndroidNotificationDetails(
        kUpdateNotificationChannelId,
        kUpdateNotificationChannelName,
        channelDescription: kUpdateNotificationChannelDescription,
        importance: Importance.defaultImportance,
        priority: Priority.defaultPriority,
        showWhen: true,
        autoCancel: true,
      ),
    ),
  );

  await secureStorage.setLastUpdateNotificationTime(DateTime.now());
}

/// Service for managing background update checks
class BackgroundUpdateService {
  BackgroundUpdateService(this.ref);

  final Ref ref;

  /// Initialize WorkManager and register periodic task
  Future<void> initialize() async {
    if (!Platform.isAndroid) {
      // WorkManager only works on Android/iOS, skip on other platforms
      return;
    }

    // Initialize WorkManager
    await Workmanager().initialize(callbackDispatcher);

    // Initialize local notifications
    await _initializeNotifications();

    // Resolve AppCatalog relays from main isolate to pass to background task
    final appCatalogRelays = await ref
        .read(storageNotifierProvider.notifier)
        .resolveRelays('AppCatalog');

    // Register periodic task (minimum 15 minutes on Android)
    // We use 6 hours for battery efficiency
    await Workmanager().registerPeriodicTask(
      kBackgroundUpdateTaskId,
      kBackgroundUpdateTaskName,
      frequency: const Duration(hours: 6),
      constraints: Constraints(
        networkType: NetworkType.connected,
        requiresBatteryNotLow: true,
      ),
      existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
      backoffPolicy: BackoffPolicy.exponential,
      initialDelay: const Duration(minutes: 15),
      inputData: {kAppCatalogRelaysKey: appCatalogRelays.toList()},
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

  /// Initialize local notifications plugin and request permission
  Future<void> _initializeNotifications() async {
    // Request notification permission on Android 13+ (API 33+)
    if (Platform.isAndroid) {
      final status = await Permission.notification.status;
      if (!status.isGranted) {
        await Permission.notification.request();
      }
    }

    final flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();

    const initializationSettingsAndroid = AndroidInitializationSettings(
      '@mipmap/ic_launcher',
    );
    const initializationSettings = InitializationSettings(
      android: initializationSettingsAndroid,
    );

    await flutterLocalNotificationsPlugin.initialize(
      initializationSettings,
      onDidReceiveNotificationResponse: (response) {
        // Notification tapped - app will open to main screen
        // Navigation is handled by the app's normal launch flow
      },
    );
    await _ensureUpdateNotificationChannel(flutterLocalNotificationsPlugin);
  }

  /// Cancel background update checks
  Future<void> cancelBackgroundChecks() async {
    await Workmanager().cancelByUniqueName(kBackgroundUpdateTaskId);
  }

  /// Trigger an immediate background check (for testing)
  Future<void> triggerImmediateCheck() async {
    final appCatalogRelays = await ref
        .read(storageNotifierProvider.notifier)
        .resolveRelays('AppCatalog');

    await Workmanager().registerOneOffTask(
      '${kBackgroundUpdateTaskId}_immediate',
      kBackgroundUpdateTaskName,
      constraints: Constraints(networkType: NetworkType.connected),
      inputData: {kAppCatalogRelaysKey: appCatalogRelays.toList()},
    );
  }
}

Future<void> _ensureUpdateNotificationChannel(
  FlutterLocalNotificationsPlugin plugin,
) async {
  final androidPlugin = plugin
      .resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin
      >();

  if (androidPlugin != null) {
    await androidPlugin.createNotificationChannel(
      const AndroidNotificationChannel(
        kUpdateNotificationChannelId,
        kUpdateNotificationChannelName,
        description: kUpdateNotificationChannelDescription,
        importance: Importance.defaultImportance,
      ),
    );
  }
}

/// Provider for the background update service
final backgroundUpdateServiceProvider = Provider<BackgroundUpdateService>(
  BackgroundUpdateService.new,
);
