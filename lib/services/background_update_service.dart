import 'dart:io' show Platform;

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:purplebase/purplebase.dart';
import 'package:workmanager/workmanager.dart';
import 'package:zapstore/services/package_manager/android_package_manager.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';

/// Unique task name for background update checking
const kBackgroundUpdateTaskName = 'dev.zapstore.backgroundUpdateCheck';

/// Unique task identifier
const kBackgroundUpdateTaskId = 'backgroundUpdateCheck';

/// Notification channel for update notifications
const kUpdateNotificationChannelId = 'zapstore_updates';
const kUpdateNotificationChannelName = 'App Updates';
const kUpdateNotificationChannelDescription =
    'Notifications for available app updates';

/// The entry point for WorkManager background tasks.
/// This MUST be a top-level function (not a class method).
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    switch (task) {
      case kBackgroundUpdateTaskName:
        return await _checkForUpdatesInBackground();
      default:
        return Future.value(false);
    }
  });
}

/// Background update check logic - runs in a separate isolate
Future<bool> _checkForUpdatesInBackground() async {
  try {
    // Create a fresh provider container for background work
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(PurplebaseStorageNotifier.new),
        packageManagerProvider.overrideWith(
          (ref) => Platform.isAndroid
              ? AndroidPackageManager(ref)
              : DummyPackageManager(ref),
        ),
      ],
    );

    try {
      // Initialize Purplebase with same DB path as main app
      final dir = await getApplicationDocumentsDirectory();
      final dbPath = path.join(dir.path, 'zapstore.db');

      await container.read(
        initializationProvider(
          StorageConfiguration(
            databasePath: dbPath,
            defaultRelays: {
              'AppCatalog': {'wss://relay.zapstore.dev'},
            },
          ),
        ).future,
      );

      // Get installed packages directly from Android system
      final packageManager = container.read(packageManagerProvider.notifier);
      await packageManager.syncInstalledPackages();

      final packages = container.read(packageManagerProvider);
      if (packages.isEmpty) {
        return true; // No installed apps to check
      }

      final installedIds = packages.map((p) => p.appId).toSet();

      // Query for apps with updates from relay
      final storage = container.read(storageNotifierProvider.notifier);
      final apps = await storage.query(
        RequestFilter<App>(
          tags: {
            '#d': installedIds,
            '#f': {packageManager.platform},
          },
        ).toRequest(),
        source: const RemoteSource(
          relays: 'AppCatalog',
          stream: false,
          background: false,
        ),
      );

      // Load releases for hasUpdate check
      for (final app in apps) {
        await storage.query(
          RequestFilter<Release>(
            tags: {
              '#a': {app.event.addressableId},
            },
          ).toRequest(),
          source: const LocalAndRemoteSource(
            relays: 'AppCatalog',
            stream: false,
            background: false,
          ),
        );
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

      // Count updates
      int updateCount = 0;
      final updatableAppNames = <String>[];

      for (final app in appsWithRelations) {
        if (app.hasUpdate) {
          updateCount++;
          updatableAppNames.add(app.name ?? app.identifier);
        }
      }

      // Show notification if updates found
      if (updateCount > 0) {
        await _showUpdateNotification(updateCount, updatableAppNames);
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

/// Show a local notification for available updates
Future<void> _showUpdateNotification(
  int updateCount,
  List<String> appNames,
) async {
  final flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();

  // Initialize notifications (needed in background isolate)
  const initializationSettingsAndroid = AndroidInitializationSettings(
    '@mipmap/ic_launcher',
  );
  const initializationSettings = InitializationSettings(
    android: initializationSettingsAndroid,
  );
  await flutterLocalNotificationsPlugin.initialize(initializationSettings);

  // Build notification content
  final title = updateCount == 1
      ? '1 app update available'
      : '$updateCount app updates available';

  final body = appNames.length <= 3
      ? appNames.join(', ')
      : '${appNames.take(3).join(', ')} and ${appNames.length - 3} more';

  const androidDetails = AndroidNotificationDetails(
    kUpdateNotificationChannelId,
    kUpdateNotificationChannelName,
    channelDescription: kUpdateNotificationChannelDescription,
    importance: Importance.defaultImportance,
    priority: Priority.defaultPriority,
    showWhen: true,
    autoCancel: true,
  );

  const notificationDetails = NotificationDetails(android: androidDetails);

  await flutterLocalNotificationsPlugin.show(
    0, // Notification ID
    title,
    body,
    notificationDetails,
  );
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
    );
  }

  /// Initialize local notifications plugin
  Future<void> _initializeNotifications() async {
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

    // Create notification channel on Android
    final androidPlugin =
        flutterLocalNotificationsPlugin
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

  /// Cancel background update checks
  Future<void> cancelBackgroundChecks() async {
    await Workmanager().cancelByUniqueName(kBackgroundUpdateTaskId);
  }

  /// Trigger an immediate background check (for testing)
  Future<void> triggerImmediateCheck() async {
    await Workmanager().registerOneOffTask(
      '${kBackgroundUpdateTaskId}_immediate',
      kBackgroundUpdateTaskName,
      constraints: Constraints(networkType: NetworkType.connected),
    );
  }
}

/// Provider for the background update service
final backgroundUpdateServiceProvider = Provider<BackgroundUpdateService>(
  BackgroundUpdateService.new,
);
