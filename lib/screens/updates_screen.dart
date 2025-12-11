import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/download_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/theme.dart';
import 'package:background_downloader/background_downloader.dart';

/// Screen for managing app updates
class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Watch categorized apps from StateNotifier
    final categorized = ref.watch(categorizedAppsProvider);
    final automaticUpdates = categorized.automaticUpdates;
    final manualUpdates = categorized.manualUpdates;
    final upToDateApps = categorized.upToDateApps;

    // Watch download service for installing apps
    final downloads = ref.watch(downloadServiceProvider);

    // Get installing app IDs from download service
    final installingAppIds = downloads.entries
        .where(
          (entry) =>
              entry.value.isInstalling ||
              entry.value.status == TaskStatus.running ||
              entry.value.status == TaskStatus.enqueued ||
              entry.value.status == TaskStatus.paused ||
              entry.value.status == TaskStatus.waitingToRetry,
        )
        .map((entry) => entry.key)
        .toSet();

    // Query for installing apps that might not be in the updates list
    final installingAppsState = installingAppIds.isNotEmpty
        ? ref.watch(
            query<App>(
              tags: {'#d': installingAppIds},
              and: (app) => {app.latestRelease, app.author},
              andSource: const LocalAndRemoteSource(relays: 'social'),
              subscriptionPrefix: 'installing-apps',
            ),
          )
        : StorageData<App>(const []);

    // Get installing apps from the query, excluding apps already in update sections
    final updateAppIds = {
      ...automaticUpdates.map((a) => a.identifier),
      ...manualUpdates.map((a) => a.identifier),
    };

    final installingApps = installingAppsState.models
        .where(
          (app) =>
              installingAppIds.contains(app.identifier) &&
              !updateAppIds.contains(app.identifier),
        )
        .toList();

    return Scaffold(
      body: RefreshIndicator(
        onRefresh: () => _handleRefresh(ref),
        child: ListView(
          children: [
            // Installing section
            if (installingApps.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    Icon(
                      Icons.downloading,
                      size: 20,
                      color: AppColors.darkActionPrimary,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Installing',
                      style: context.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: AppColors.darkPillBackground,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      constraints: const BoxConstraints(
                        minWidth: 18,
                        minHeight: 18,
                      ),
                      child: Text(
                        installingApps.length > 99
                            ? '99+'
                            : installingApps.length.toString(),
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ],
                ),
              ),
              ...installingApps.map(
                (app) => AppCard(
                  app: app,
                  showUpdateArrow: false,
                  showUpdateButton: true,
                  showZapEncouragement: true,
                ),
              ),
            ],

            // Automatic updates section
            if (automaticUpdates.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    Icon(
                      Icons.system_update,
                      size: 20,
                      color: AppColors.darkActionPrimary,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Ready to Update',
                      style: context.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: AppColors.darkPillBackground,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      constraints: const BoxConstraints(
                        minWidth: 18,
                        minHeight: 18,
                      ),
                      child: Text(
                        automaticUpdates.length > 99
                            ? '99+'
                            : automaticUpdates.length.toString(),
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                    const Spacer(),
                    AsyncButtonBuilder(
                      onPressed: () async {
                        final downloadService = ref.read(
                          downloadServiceProvider.notifier,
                        );
                        // Start downloads for all automatic updates
                        for (final app in automaticUpdates) {
                          final release = app.latestRelease.value;
                          if (release != null) {
                            try {
                              await downloadService.downloadApp(app, release);
                            } catch (e) {
                              // Failed to start download
                            }
                          }
                        }
                      },
                      builder: (context, child, callback, buttonState) {
                        const pillBg = AppColors.darkPillBackground;
                        const pillText = Colors.white;

                        return TextButton.icon(
                          onPressed: buttonState.maybeWhen(
                            loading: () => null,
                            orElse: () => callback,
                          ),
                          icon: buttonState.maybeWhen(
                            loading: () => SizedBox(
                              width: 12,
                              height: 12,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: pillText,
                              ),
                            ),
                            orElse: () =>
                                Icon(Icons.download, size: 14, color: pillText),
                          ),
                          label: Text(
                            'Update All',
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                              color: pillText,
                            ),
                          ),
                          style: TextButton.styleFrom(
                            backgroundColor: pillBg,
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 6,
                            ),
                            minimumSize: Size.zero,
                            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                        );
                      },
                      child: const SizedBox.shrink(),
                    ),
                  ],
                ),
              ),
              ...automaticUpdates.map(
                (app) => AppCard(
                  app: app,
                  showUpdateArrow: true,
                  showUpdateButton: true,
                  showZapEncouragement: true,
                ),
              ),
            ],

            // Manual updates section
            if (manualUpdates.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    Icon(
                      Icons.touch_app,
                      size: 20,
                      color: AppColors.darkActionPrimary,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Ready to Manually Update',
                      style: context.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: AppColors.darkPillBackground,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      constraints: const BoxConstraints(
                        minWidth: 18,
                        minHeight: 18,
                      ),
                      child: Text(
                        manualUpdates.length > 99
                            ? '99+'
                            : manualUpdates.length.toString(),
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child: Text(
                  'These updates require confirming the Android system prompt',
                  style: context.textTheme.bodyMedium?.copyWith(
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.6),
                  ),
                ),
              ),
              ...manualUpdates.map(
                (app) => AppCard(
                  app: app,
                  showUpdateArrow: true,
                  showUpdateButton: true,
                  showZapEncouragement: true,
                ),
              ),
            ],

            // Up to date section
            if (upToDateApps.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    Icon(
                      Icons.check_circle_outline,
                      size: 20,
                      color: AppColors.darkActionPrimary,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Up to Date',
                      style: context.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: AppColors.darkPillBackground,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      constraints: const BoxConstraints(
                        minWidth: 18,
                        minHeight: 18,
                      ),
                      child: Text(
                        upToDateApps.length > 99
                            ? '99+'
                            : upToDateApps.length.toString(),
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ],
                ),
              ),
              // Show informative message when no updates available
              if (automaticUpdates.isEmpty && manualUpdates.isEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                  child: Text(
                    'All apps are up to date',
                    style: context.textTheme.bodyMedium?.copyWith(
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withValues(alpha: 0.6),
                    ),
                  ),
                ),
              ...upToDateApps.map(
                (app) => AppCard(
                  app: app,
                  showUpdateArrow: false, // Up-to-date apps show single version
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// Handle pull-to-refresh action
  Future<void> _handleRefresh(WidgetRef ref) async {
    // Sync installed packages to detect uninstalled apps
    await ref.read(packageManagerProvider.notifier).syncInstalledPackages();
  }
}
