import 'dart:io' show Directory, File, Platform;
import 'dart:ui' as ui;

import 'package:background_downloader/background_downloader.dart' hide Request;
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:purplebase/purplebase.dart';
import 'package:workmanager/workmanager.dart';
import 'package:zapstore/router.dart';
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

/// How long user must be inactive before showing background notification
const _inactivityThreshold = Duration(hours: 24);

/// Notification payload for deep linking to updates screen
const _kNotificationPayload = 'updates';

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
/// Note: Cannot directly reuse [CategorizedUpdatesNotifier] since this runs
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
        ).toRequest(subscriptionPrefix: 'app-bg-updates'),
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
            Request<Release>(releaseFilters, subscriptionPrefix: 'app-bg-releases'),
            source: const RemoteSource(relays: 'AppCatalog', stream: false),
          );

          final metadataFilters = releases
              .map((r) => r.latestMetadata.req?.filters.firstOrNull)
              .nonNulls
              .toList();
          if (metadataFilters.isNotEmpty) {
            await storage.query(
              Request<FileMetadata>(metadataFilters, subscriptionPrefix: 'app-bg-metadata'),
              source: const RemoteSource(relays: 'AppCatalog', stream: false),
            );
          }

          final assetFilters = releases
              .map((r) => r.latestAsset.req?.filters.firstOrNull)
              .nonNulls
              .toList();
          if (assetFilters.isNotEmpty) {
            await storage.query(
              Request<SoftwareAsset>(assetFilters, subscriptionPrefix: 'app-bg-assets'),
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
      final updatableApps = appsWithRelations
          .where((app) => app.hasUpdate)
          .toList();

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

/// Show a local notification for available updates.
/// Only notifies if:
/// 1. User hasn't opened app in 24+ hours
/// 2. There are updates with release.createdAt > seenUntil AND > lastOpened
///    (new since both last notification AND last time user saw the app)
Future<void> _showUpdateNotificationIfNeeded(List<App> updates) async {
  final secureStorage = SecureStorageService();

  // Skip if user recently opened the app
  final lastOpened = await secureStorage.getLastAppOpenedTime();
  if (lastOpened != null &&
      DateTime.now().difference(lastOpened) < _inactivityThreshold) {
    return;
  }

  // Get the "seen until" timestamp - updates with release.createdAt > this are new
  final seenUntil = await secureStorage.getSeenUntil();

  // Filter to only updates that are genuinely new:
  // - release.createdAt > seenUntil (not already notified via background)
  // - release.createdAt > lastOpened (not already seen when user opened app)
  // This prevents nagging about updates user saw in the app but chose to ignore
  final newUpdates = updates.where((app) {
    final releaseTime = app.latestRelease.value?.event.createdAt;
    if (releaseTime == null) return false;

    // Must be newer than last notification (if any)
    if (seenUntil != null && !releaseTime.isAfter(seenUntil)) {
      return false;
    }

    // Must be newer than last app open (if any) - user may have seen it in UI
    if (lastOpened != null && !releaseTime.isAfter(lastOpened)) {
      return false;
    }

    return true;
  }).toList();

  if (newUpdates.isEmpty) {
    return; // No new updates to notify about
  }

  // Show the notification
  final plugin = FlutterLocalNotificationsPlugin();
  const initSettings = InitializationSettings(
    android: AndroidInitializationSettings('@drawable/ic_notification'),
  );
  await plugin.initialize(initSettings);
  await _ensureUpdateNotificationChannel(plugin);

  final appNames = newUpdates.map((a) => a.name ?? a.identifier).toList();
  final title = newUpdates.length == 1
      ? '1 app update available'
      : '${newUpdates.length} app updates available';
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
    payload: _kNotificationPayload,
  );

  // Update seenUntil to now - future checks will only notify about releases after this
  await secureStorage.setSeenUntil(DateTime.now());
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
    // We use 24 hours to match the inactivity threshold for notifications.
    // More frequent checks would be wasted since we only notify users
    // who haven't opened the app in 24+ hours.
    await Workmanager().registerPeriodicTask(
      kBackgroundUpdateTaskId,
      kBackgroundUpdateTaskName,
      frequency: const Duration(hours: 24),
      constraints: Constraints(
        networkType: NetworkType.connected,
        requiresBatteryNotLow: true,
      ),
      existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
      backoffPolicy: BackoffPolicy.exponential,
      initialDelay: const Duration(hours: 1),
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
      '@drawable/ic_notification',
    );
    const initializationSettings = InitializationSettings(
      android: initializationSettingsAndroid,
    );

    await flutterLocalNotificationsPlugin.initialize(
      initializationSettings,
      onDidReceiveNotificationResponse: _handleNotificationTap,
    );

    // Check if app was launched from a notification (terminated state)
    final launchDetails = await flutterLocalNotificationsPlugin
        .getNotificationAppLaunchDetails();
    if (launchDetails?.didNotificationLaunchApp == true &&
        launchDetails?.notificationResponse?.payload == _kNotificationPayload) {
      _navigateToUpdates();
    }

    await _ensureUpdateNotificationChannel(flutterLocalNotificationsPlugin);
  }

  /// Handle notification tap - navigate to updates screen
  static void _handleNotificationTap(NotificationResponse response) {
    if (response.payload == _kNotificationPayload) {
      _navigateToUpdates();
    }
  }

  /// Navigate to the updates screen
  static void _navigateToUpdates() {
    // Use the root navigator key to navigate
    final context = rootNavigatorKey.currentContext;
    if (context != null) {
      GoRouter.of(context).go('/updates');
    }
  }

  /// Cancel background update checks
  Future<void> cancelBackgroundChecks() async {
    await Workmanager().cancelByUniqueName(kBackgroundUpdateTaskId);
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
