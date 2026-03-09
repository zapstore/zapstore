import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/batch_progress_banner.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/theme.dart';

/// Shared AppBar for restore screen (empty, loading, error, content).
PreferredSizeWidget _restoreAppBar({VoidCallback? onClose}) {
  return AppBar(
    title: const Text('Restore from backup'),
    leading: onClose != null
        ? IconButton(icon: const Icon(Icons.close), onPressed: onClose)
        : null,
    automaticallyImplyLeading: onClose == null,
  );
}

/// Full-screen restore from backup. Mirrors [AppStackScreen] pattern:
/// query with nested relations, skeleton on loading, sort uninstalled first.
class RestoreInstalledAppsScreen extends HookConsumerWidget {
  const RestoreInstalledAppsScreen({
    super.key,
    required this.addressableIds,
    this.onClose,
  });

  final List<String> addressableIds;

  /// Called when user closes the modal. Required when shown as overlay.
  final VoidCallback? onClose;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appIdentifiers = addressableIds
        .where((id) => id.startsWith('32267:'))
        .map((id) => id.split(':').skip(2).join(':'))
        .toSet();

    if (appIdentifiers.isEmpty) {
      return Scaffold(
        appBar: _restoreAppBar(onClose: onClose),
        body: Center(
          child: Text('No apps to restore', style: context.textTheme.bodyLarge),
        ),
      );
    }

    final platform = ref.read(packageManagerProvider.notifier).platform;

    final appsState = ref.watch(
      query<App>(
        tags: {
          '#d': appIdentifiers,
          '#f': {platform},
        },
        and: (app) => {
          app.latestRelease.query(
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
          ),
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'restore-backup-apps',
      ),
    );

    return switch (appsState) {
      StorageLoading() => Scaffold(
        appBar: _restoreAppBar(onClose: onClose),
        body: SingleChildScrollView(
          padding: const EdgeInsets.only(top: 8, bottom: 32),
          child: _RestoreSkeleton(),
        ),
      ),
      StorageError(:final exception) => Scaffold(
        appBar: _restoreAppBar(onClose: onClose),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.error_outline, size: 64),
              const SizedBox(height: 12),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Text(exception.toString(), textAlign: TextAlign.center),
              ),
            ],
          ),
        ),
      ),
      StorageData(:final models) => _RestoreContent(
        allApps: models,
        appIdentifiers: appIdentifiers,
        onClose: onClose,
      ),
    };
  }
}

class _RestoreContent extends HookConsumerWidget {
  const _RestoreContent({
    required this.allApps,
    required this.appIdentifiers,
    this.onClose,
  });

  final List<App> allApps;
  final Set<String> appIdentifiers;
  final VoidCallback? onClose;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final installedIds = ref
        .watch(packageManagerProvider)
        .installed
        .keys
        .toSet();
    final completedOperationIds = ref.watch(
      packageManagerProvider.select(
        (s) => s.operations.entries
            .where((entry) => entry.value is Completed)
            .map((entry) => entry.key)
            .toSet(),
      ),
    );
    final effectiveInstalledIds = {...installedIds, ...completedOperationIds};

    final appsMap = {for (final app in allApps) app.identifier: app};

    // Preserve backup order for both lists
    final toInstall = appIdentifiers
        .map((id) => appsMap[id])
        .whereType<App>()
        .where((app) => !effectiveInstalledIds.contains(app.identifier))
        .toList();

    final alreadyInstalled = appIdentifiers
        .map((id) => appsMap[id])
        .whereType<App>()
        .where((app) => effectiveInstalledIds.contains(app.identifier))
        .toList();

    return Scaffold(
      appBar: _restoreAppBar(onClose: onClose),
      body: ListView(
        padding: const EdgeInsets.only(top: 8, bottom: 32),
        children: [
          // "Install All" button — same style as UpdateAllRow in updates screen
          if (toInstall.length > 1)
            UpdateAllRow(
              allUpdates: toInstall,
              label: 'Install All',
              installSource: InstallSource.restore,
            ),

          // "To install" section
          if (toInstall.isNotEmpty)
            _SectionHeader(
              icon: Icons.download_outlined,
              title: 'To install',
              count: toInstall.length,
            ),
          ...toInstall.map(
            (app) => AppCard(
              key: ValueKey('restore_${app.identifier}'),
              app: app,
              installSource: InstallSource.restore,
              showUpdateButton: true,
              showInstallWhenNotInstalled: true,
              showUpdateArrow: false,
              showSignedBy: false,
              showZapEncouragement: false,
              showDescription: false,
              onTap: () {}, // No navigation; Install button stays tappable
            ),
          ),

          // "Already installed" section
          if (alreadyInstalled.isNotEmpty) ...[
            _SectionHeader(
              icon: Icons.check_circle,
              title: 'Already installed',
              count: alreadyInstalled.length,
              iconColor: Colors.green.shade400,
            ),
            ...alreadyInstalled.map(
              (app) => AppCard(
                key: ValueKey('installed_${app.identifier}'),
                app: app,
                showUpdateButton: false,
                showUpdateArrow: app.hasUpdate,
                showSignedBy: false,
                showZapEncouragement: false,
                showDescription: false,
                ignorePointer: true, // Informational only; no tap
              ),
            ),
          ],

          // Empty state — only when backup has nothing at all
          if (toInstall.isEmpty && alreadyInstalled.isEmpty)
            Padding(
              padding: const EdgeInsets.all(32),
              child: Center(
                child: Text(
                  'No apps found in backup',
                  style: context.textTheme.bodyLarge?.copyWith(
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.6),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.icon,
    required this.title,
    required this.count,
    this.iconColor,
  });

  final IconData icon;
  final String title;
  final int count;
  final Color? iconColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Icon(icon, size: 20, color: iconColor ?? AppColors.darkActionPrimary),
          const SizedBox(width: 8),
          Text(title, style: context.textTheme.titleMedium),
          const SizedBox(width: 8),
          CountBadge(count: count, color: AppColors.darkPillBackground),
        ],
      ),
    );
  }
}

class _RestoreSkeleton extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Section header skeleton — same padding as _SectionHeader
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    height: 20,
                    width: 100,
                    decoration: BoxDecoration(
                      color: AppColors.darkSkeletonBase,
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    height: 20,
                    width: 24,
                    decoration: BoxDecoration(
                      color: AppColors.darkSkeletonBase,
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 4),
            ...List.generate(3, (_) => const AppCard(isLoading: true)),
          ],
        ),
      ),
    );
  }
}
