import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/version_utils.dart';

/// Categorized apps state
class CategorizedApps {
  const CategorizedApps({
    required this.automaticUpdates,
    required this.manualUpdates,
    required this.upToDateApps,
    required this.uncatalogedApps,
    this.isLoading = false,
  });

  final List<App> automaticUpdates;
  final List<App> manualUpdates;
  final List<App> upToDateApps;

  /// Apps installed on device but not found in any relay catalog
  final List<PackageInfo> uncatalogedApps;
  final bool isLoading;

  CategorizedApps copyWith({
    List<App>? automaticUpdates,
    List<App>? manualUpdates,
    List<App>? upToDateApps,
    List<PackageInfo>? uncatalogedApps,
    bool? isLoading,
  }) {
    return CategorizedApps(
      automaticUpdates: automaticUpdates ?? this.automaticUpdates,
      manualUpdates: manualUpdates ?? this.manualUpdates,
      upToDateApps: upToDateApps ?? this.upToDateApps,
      uncatalogedApps: uncatalogedApps ?? this.uncatalogedApps,
      isLoading: isLoading ?? this.isLoading,
    );
  }

  static const empty = CategorizedApps(
    automaticUpdates: [],
    manualUpdates: [],
    upToDateApps: [],
    uncatalogedApps: [],
    isLoading: true,
  );
}

class CategorizedAppsNotifier extends Notifier<CategorizedApps> {
  bool _hasLoadedOnce = false;

  @override
  CategorizedApps build() {
    // Wait for app initialization
    final initState = ref.watch(appInitializationProvider);
    if (initState is! AsyncData) {
      return CategorizedApps.empty;
    }

    // Watch installed packages - this is the source of truth
    final pmState = ref.watch(packageManagerProvider);
    final installedPackages = pmState.installed.values.toList();
    final installedIds = pmState.installed.keys.toSet();

    // Show loading while scanning for installed packages
    if (pmState.isScanning && installedIds.isEmpty) {
      return CategorizedApps.empty;
    }

    if (installedIds.isEmpty) {
      _hasLoadedOnce = true;
      return const CategorizedApps(
        automaticUpdates: [],
        manualUpdates: [],
        upToDateApps: [],
        uncatalogedApps: [],
        isLoading: false,
      );
    }

    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Query apps with relationships loaded via `and:`
    final appsState = ref.watch(
      query<App>(
        tags: {
          '#d': installedIds,
          '#f': {platform},
        },
        and: (app) => {
          app.latestRelease.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
          ),
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        subscriptionPrefix: 'updates',
      ),
    );

    return switch (appsState) {
      StorageLoading() => CategorizedApps.empty.copyWith(
        isLoading: !_hasLoadedOnce,
      ),
      StorageError() => CategorizedApps.empty.copyWith(isLoading: false),
      StorageData(:final models) => _categorize(models, installedPackages),
    };
  }

  CategorizedApps _categorize(
    List<App> apps,
    List<PackageInfo> installedPackages,
  ) {
    _hasLoadedOnce = true;

    final automaticUpdates = <App>[];
    final manualUpdates = <App>[];
    final upToDateApps = <App>[];

    // Build lookup map from passed-in data (avoids separate provider reads)
    final installedMap = {for (final pkg in installedPackages) pkg.appId: pkg};

    // Track ALL apps returned from relay query as "cataloged"
    final catalogedAppIds = apps.map((a) => a.identifier).toSet();

    // Only process apps that are actually installed (check against our map)
    final installedApps = apps.where(
      (a) => installedMap.containsKey(a.identifier),
    );

    for (final app in installedApps) {
      final pkg = installedMap[app.identifier]!;
      final latest = app.latestFileMetadata;

      // Determine if update available using local data
      final hasUpdate = latest != null && _hasUpdate(pkg, latest);

      if (hasUpdate) {
        if (pkg.canInstallSilently) {
          automaticUpdates.add(app);
        } else {
          manualUpdates.add(app);
        }
      } else {
        // No update available - app is up to date
        upToDateApps.add(app);
      }
    }

    // Find installed packages without catalog metadata
    final uncatalogedApps =
        installedPackages
            .where((pkg) => !catalogedAppIds.contains(pkg.appId))
            .toList()
          ..sort(
            (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
              (b.name ?? b.appId).toLowerCase(),
            ),
          );

    int byName(App a, App b) => (a.name ?? a.identifier)
        .toLowerCase()
        .compareTo((b.name ?? b.identifier).toLowerCase());

    automaticUpdates.sort(byName);
    manualUpdates.sort(byName);
    upToDateApps.sort(byName);

    // Fetch author profiles in background (fire and forget)
    _fetchAuthors(installedApps);

    return CategorizedApps(
      automaticUpdates: automaticUpdates,
      manualUpdates: manualUpdates,
      upToDateApps: upToDateApps,
      uncatalogedApps: uncatalogedApps,
      isLoading: false,
    );
  }

  /// Check if an update is available by comparing versions
  bool _hasUpdate(PackageInfo installed, FileMetadata latest) {
    if (latest.versionCode != null && installed.versionCode != null) {
      return latest.versionCode! > installed.versionCode!;
    }
    return canUpgrade(installed.version, latest.version);
  }

  void _fetchAuthors(Iterable<App> apps) {
    final authorPubkeys = apps.map((a) => a.event.pubkey).toSet();
    if (authorPubkeys.isEmpty) return;
    unawaited(
      ref.storage.query(
        RequestFilter<Profile>(authors: authorPubkeys).toRequest(),
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
          stream: false,
        ),
        subscriptionPrefix: 'updates-profiles',
      ),
    );
  }
}

final categorizedAppsProvider =
    NotifierProvider<CategorizedAppsNotifier, CategorizedApps>(
      CategorizedAppsNotifier.new,
    );

/// Provider that calculates the total number of apps with available updates
final updateCountProvider = Provider<int>((ref) {
  final categorized = ref.watch(categorizedAppsProvider);
  if (categorized.isLoading) return 0;
  return categorized.automaticUpdates.length + categorized.manualUpdates.length;
});
