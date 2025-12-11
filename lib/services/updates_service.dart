import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
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

  static const empty = CategorizedApps(
    automaticUpdates: [],
    manualUpdates: [],
    upToDateApps: [],
    isLoading: true,
  );

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
}

/// Provider that maintains streaming subscription and categorizes apps
class CategorizedAppsNotifier extends StateNotifier<CategorizedApps> {
  CategorizedAppsNotifier(this._ref) : super(CategorizedApps.empty) {
    // Listen to package manager changes and recreate query subscription
    _ref.listen(packageManagerProvider, (_, packages) {
      final installedIds = packages.map((p) => p.appId).toSet();
      final platform = _ref.read(packageManagerProvider.notifier).platform;
      _recreateQuerySubscription(installedIds, platform);
    }, fireImmediately: true);
  }

  final Ref _ref;
  ProviderSubscription<StorageState<App>>? _querySub;

  void _recreateQuerySubscription(Set<String> installedIds, String platform) {
    // Close old subscription
    _querySub?.close();

    if (installedIds.isEmpty) {
      state = const CategorizedApps(
        automaticUpdates: [],
        manualUpdates: [],
        upToDateApps: [],
      );
      return;
    }

    // Create new streaming subscription
    _querySub = _ref.listen(
      query<App>(
        tags: {
          '#d': installedIds,
          '#f': {platform},
        },
        and: (app) => {
          app.latestRelease,
          if (app.latestRelease.value != null)
            app.latestRelease.value!.latestMetadata,
        },
        source: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          background: true,
          stream: true,
        ),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          background: true,
          stream: false,
        ),
        subscriptionPrefix: 'updates',
      ),
      (_, appsState) {
        // Fetch authors in background
        _fetchAuthors(appsState.models);

        // Categorize
        _categorizeApps(appsState.models);
      },
      fireImmediately: true,
    );
  }

  @override
  void dispose() {
    _querySub?.close();
    super.dispose();
  }

  void _fetchAuthors(List<App> apps) async {
    final authorPubkeys = apps.map((a) => a.event.pubkey).toSet();
    if (authorPubkeys.isNotEmpty) {
      await _ref.storage.query(
        RequestFilter<Profile>(authors: authorPubkeys).toRequest(),
        source: const RemoteSource(
          relays: 'social',
          background: false,
          stream: false,
        ),
      );
    }
  }

  void _categorizeApps(List<App> apps) async {
    final packageManager = _ref.read(packageManagerProvider.notifier);
    final automaticUpdates = <App>[];
    final manualUpdates = <App>[];
    final upToDateApps = <App>[];

    for (final app in apps) {
      if (app.hasUpdate) {
        final canSilentInstall = await packageManager.canInstallSilently(
          app.identifier,
        );
        if (canSilentInstall) {
          automaticUpdates.add(app);
        } else {
          manualUpdates.add(app);
        }
      } else if (app.isUpdated) {
        upToDateApps.add(app);
      }
    }

    // Sort alphabetically
    automaticUpdates.sort(
      (a, b) => (a.name ?? a.identifier).toLowerCase().compareTo(
        (b.name ?? b.identifier).toLowerCase(),
      ),
    );
    manualUpdates.sort(
      (a, b) => (a.name ?? a.identifier).toLowerCase().compareTo(
        (b.name ?? b.identifier).toLowerCase(),
      ),
    );
    upToDateApps.sort(
      (a, b) => (a.name ?? a.identifier).toLowerCase().compareTo(
        (b.name ?? b.identifier).toLowerCase(),
      ),
    );

    if (mounted) {
      state = CategorizedApps(
        automaticUpdates: automaticUpdates,
        manualUpdates: manualUpdates,
        upToDateApps: upToDateApps,
      );
    }
  }
}

final categorizedAppsProvider =
    StateNotifierProvider<CategorizedAppsNotifier, CategorizedApps>(
      CategorizedAppsNotifier.new,
    );

/// Provider that calculates the total number of apps with available updates
/// Uses LocalSource since categorizedAppsProvider already fetches from relays
final updateCountProvider = Provider<int>((ref) {
  // Watch package manager for installed state changes
  final packages = ref.watch(packageManagerProvider);
  final installedIds = packages.map((p) => p.appId).toSet();
  final platform = ref.read(packageManagerProvider.notifier).platform;

  if (installedIds.isEmpty) {
    return 0;
  }

  // Query apps from local storage only - data is already fetched by categorizedAppsProvider
  final state = ref.watch(
    query<App>(
      tags: {
        '#d': installedIds,
        '#f': {platform},
      },
      and: (app) => {
        app.latestRelease,
        // Load nested FileMetadata - critical for hasUpdate to work
        if (app.latestRelease.value != null)
          app.latestRelease.value!.latestMetadata,
      },
      source: const LocalSource(),
      subscriptionPrefix: 'update-count',
    ),
  );

  int updateCount = 0;

  for (final app in state.models) {
    if (app.hasUpdate) {
      updateCount++;
    }
  }

  return updateCount;
});
