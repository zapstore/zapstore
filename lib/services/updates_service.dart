import 'dart:async';

import 'package:collection/collection.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';

/// Categorized apps state
class CategorizedApps {
  const CategorizedApps({
    required this.automaticUpdates,
    required this.manualUpdates,
    required this.upToDateApps,
    this.isLoading = false,
  });

  final List<App> automaticUpdates;
  final List<App> manualUpdates;
  final List<App> upToDateApps;
  final bool isLoading;

  CategorizedApps copyWith({
    List<App>? automaticUpdates,
    List<App>? manualUpdates,
    List<App>? upToDateApps,
    bool? isLoading,
  }) {
    return CategorizedApps(
      automaticUpdates: automaticUpdates ?? this.automaticUpdates,
      manualUpdates: manualUpdates ?? this.manualUpdates,
      upToDateApps: upToDateApps ?? this.upToDateApps,
      isLoading: isLoading ?? this.isLoading,
    );
  }

  static const empty = CategorizedApps(
    automaticUpdates: [],
    manualUpdates: [],
    upToDateApps: [],
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
    final packages = ref.watch(packageManagerProvider);
    final installedIds = packages.map((p) => p.appId).toSet();

    if (installedIds.isEmpty) {
      _hasLoadedOnce = true;
      return const CategorizedApps(
        automaticUpdates: [],
        manualUpdates: [],
        upToDateApps: [],
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
          app.latestRelease,
          app.latestRelease.value?.latestMetadata,
          app.latestRelease.value?.latestAsset,
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: true),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        subscriptionPrefix: 'updates',
      ),
    );

    return switch (appsState) {
      StorageLoading() => CategorizedApps.empty.copyWith(
        isLoading: !_hasLoadedOnce,
      ),
      StorageError() => CategorizedApps.empty.copyWith(isLoading: false),
      StorageData(:final models) => _categorize(models, packages),
    };
  }

  CategorizedApps _categorize(List<App> apps, List<PackageInfo> packages) {
    _hasLoadedOnce = true;

    final automaticUpdates = <App>[];
    final manualUpdates = <App>[];
    final upToDateApps = <App>[];

    // Only process apps that are actually installed
    final installedApps = apps.where((a) => a.installedPackage != null);

    for (final app in installedApps) {
      if (app.hasUpdate) {
        // Look up silent install status from package info
        final pkg = packages.firstWhereOrNull((p) => p.appId == app.identifier);
        if (pkg?.canInstallSilently ?? false) {
          automaticUpdates.add(app);
        } else {
          manualUpdates.add(app);
        }
      } else if (app.isUpdated) {
        upToDateApps.add(app);
      }
    }

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
      isLoading: false,
    );
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
