import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/version_utils.dart';

/// How often to poll for updates from remote relays
const _pollInterval = Duration(minutes: 5);

/// Minimum time between manual refreshes
const _refreshCooldown = Duration(seconds: 30);

// ═══════════════════════════════════════════════════════════════════════════════
// UPDATE POLLER - Handles remote fetching on timer + pull-to-refresh
// ═══════════════════════════════════════════════════════════════════════════════

/// State for the update poller
class UpdatePollerState {
  const UpdatePollerState({
    this.isChecking = false,
    this.lastCheckTime,
  });

  final bool isChecking;
  final DateTime? lastCheckTime;

  UpdatePollerState copyWith({
    bool? isChecking,
    DateTime? lastCheckTime,
  }) {
    return UpdatePollerState(
      isChecking: isChecking ?? this.isChecking,
      lastCheckTime: lastCheckTime ?? this.lastCheckTime,
    );
  }
}

/// Notifier that handles polling for updates from remote relays.
/// Kept alive by MainScaffold watching categorizedUpdatesProvider.
class UpdatePollerNotifier extends Notifier<UpdatePollerState> {
  Timer? _pollTimer;
  bool _hasCompletedFirstFetch = false;

  /// Preserved state across rebuilds (build() would otherwise reset it)
  UpdatePollerState? _preservedState;

  @override
  UpdatePollerState build() {
    ref.onDispose(() {
      _pollTimer?.cancel();
      _preservedState = null;
      _hasCompletedFirstFetch = false;
    });

    // Wait for app initialization before starting timer
    final initState = ref.watch(appInitializationProvider);
    if (initState is! AsyncData) {
      return _preservedState ?? const UpdatePollerState();
    }

    // Listen (not watch) to package manager to trigger fetch when packages
    // become available, without causing state reset on every change
    ref.listen(packageManagerProvider, (prev, next) {
      final hadPackages = prev?.installed.isNotEmpty ?? false;
      final hasPackages = next.installed.isNotEmpty;

      // Trigger initial fetch when packages become available for first time
      if (!hadPackages && hasPackages && !_hasCompletedFirstFetch) {
        checkNow();
      }
    });

    // Start periodic polling timer (only once, timer persists across rebuilds)
    if (_pollTimer == null) {
      _pollTimer = Timer.periodic(_pollInterval, (_) => checkNow());

      // Check if packages are already available
      final pmState = ref.read(packageManagerProvider);
      if (pmState.installed.isNotEmpty && !_hasCompletedFirstFetch) {
        Future.microtask(() => checkNow());
      }
    }

    // Return preserved state if we have one, otherwise initial state
    return _preservedState ?? const UpdatePollerState();
  }

  /// Override state setter to preserve state across rebuilds
  @override
  set state(UpdatePollerState newState) {
    _preservedState = newState;
    super.state = newState;
  }

  /// Trigger an update check. Called by timer and pull-to-refresh.
  ///
  /// Throttling behavior (per FEAT-003):
  /// - If already checking: returns immediately (UI already showing spinner)
  /// - If checked <30s ago: shows "checking" state for 2s without network (fake fetch)
  Future<void> checkNow() async {
    // Already checking - UI is already showing spinner, just return
    if (state.isChecking) return;

    // Throttle: if checked recently, do a fake fetch (but allow first fetch always)
    if (_hasCompletedFirstFetch && state.lastCheckTime != null) {
      final timeSinceLastCheck = DateTime.now().difference(
        state.lastCheckTime!,
      );
      if (timeSinceLastCheck < _refreshCooldown) {
        // Fake fetch: show spinner for 2s without hitting network
        state = state.copyWith(isChecking: true);
        await Future.delayed(const Duration(seconds: 2));
        state = state.copyWith(isChecking: false);
        return;
      }
    }

    state = state.copyWith(isChecking: true);

    try {
      await _fetchUpdatesFromRemote();
      _hasCompletedFirstFetch = true;
      state = state.copyWith(isChecking: false, lastCheckTime: DateTime.now());
    } catch (e) {
      // Network failure - degrade gracefully, retry on next cycle
      state = state.copyWith(isChecking: false);
    }
  }

  /// Fetch updates from remote relays and write to local DB.
  /// Similar pattern to background_update_service.dart.
  Future<void> _fetchUpdatesFromRemote() async {
    final pmState = ref.read(packageManagerProvider);
    if (pmState.installed.isEmpty) {
      return; // No installed apps to check
    }

    final installedIds = pmState.installed.keys.toSet();
    final platform = ref.read(packageManagerProvider.notifier).platform;
    final storage = ref.read(storageNotifierProvider.notifier);

    // Query for apps from remote
    final apps = await storage.query(
      RequestFilter<App>(
        tags: {
          '#d': installedIds,
          '#f': {platform},
        },
      ).toRequest(),
      source: const RemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-updates-poll',
    );

    if (apps.isEmpty) return;

    // Load releases for the apps
    final releaseFilters = apps
        .map((app) => app.latestRelease.req?.filters.firstOrNull)
        .nonNulls
        .toList();

    if (releaseFilters.isEmpty) return;

    final releases = await storage.query(
      Request<Release>(releaseFilters),
      source: const RemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'app-updates-poll-releases',
    );

    if (releases.isEmpty) return;

    // Load file metadata for the releases
    final metadataFilters = releases
        .map((r) => r.latestMetadata.req?.filters.firstOrNull)
        .nonNulls
        .toList();

    if (metadataFilters.isNotEmpty) {
      await storage.query(
        Request<FileMetadata>(metadataFilters),
        source: const RemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-updates-poll-metadata',
      );
    }

    // Load software assets for the releases
    final assetFilters = releases
        .map((r) => r.latestAsset.req?.filters.firstOrNull)
        .nonNulls
        .toList();

    if (assetFilters.isNotEmpty) {
      await storage.query(
        Request<SoftwareAsset>(assetFilters),
        source: const RemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-updates-poll-assets',
      );
    }

    // Fetch author profiles in background (fire and forget)
    final authorPubkeys = apps.map((a) => a.event.pubkey).toSet();
    if (authorPubkeys.isNotEmpty) {
      unawaited(
        storage.query(
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
}

final updatePollerProvider =
    NotifierProvider<UpdatePollerNotifier, UpdatePollerState>(
      UpdatePollerNotifier.new,
    );

// ═══════════════════════════════════════════════════════════════════════════════
// CATEGORIZED UPDATES - Reactive local-only query for UI
// ═══════════════════════════════════════════════════════════════════════════════

/// Categorized apps state for the Updates screen
class CategorizedUpdates {
  const CategorizedUpdates({
    required this.automaticUpdates,
    required this.manualUpdates,
    required this.upToDateApps,
    required this.uncatalogedApps,
    this.showSkeleton = false,
  });

  final List<App> automaticUpdates;
  final List<App> manualUpdates;
  final List<App> upToDateApps;

  /// Apps installed on device but not found in any relay catalog
  final List<PackageInfo> uncatalogedApps;

  /// Whether to show skeleton (cold start only)
  final bool showSkeleton;

  static const empty = CategorizedUpdates(
    automaticUpdates: [],
    manualUpdates: [],
    upToDateApps: [],
    uncatalogedApps: [],
    showSkeleton: true,
  );
}

class CategorizedUpdatesNotifier extends Notifier<CategorizedUpdates> {
  @override
  CategorizedUpdates build() {
    // Watch the poller to keep it alive (MainScaffold watches us)
    final pollerState = ref.watch(updatePollerProvider);
    // If poller has completed at least once, don't show skeleton for loading states
    final pollerHasCompleted = pollerState.lastCheckTime != null;

    // Wait for app initialization
    final initState = ref.watch(appInitializationProvider);
    if (initState is! AsyncData) {
      return CategorizedUpdates.empty;
    }

    // Watch installed packages from PackageManager
    final pmState = ref.watch(packageManagerProvider);
    final installedPackages = pmState.installed.values.toList();
    final installedIds = pmState.installed.keys.toSet();

    // If still scanning for installed packages, show skeleton
    if (pmState.isScanning && installedIds.isEmpty) {
      return CategorizedUpdates.empty;
    }

    // If no apps installed, show empty state (not skeleton)
    if (installedIds.isEmpty) {
      return const CategorizedUpdates(
        automaticUpdates: [],
        manualUpdates: [],
        upToDateApps: [],
        uncatalogedApps: [],
        showSkeleton: false,
      );
    }

    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Query LOCAL ONLY for apps matching installed IDs
    final appsState = ref.watch(
      query<App>(
        tags: {
          '#d': installedIds,
          '#f': {platform},
        },
        and: (app) => {
          app.latestRelease.query(
            source: const LocalSource(),
            and: (release) => {
              release.latestMetadata.query(source: const LocalSource()),
              release.latestAsset.query(source: const LocalSource()),
            },
          ),
        },
        source: const LocalSource(),
        subscriptionPrefix: 'app-updates-local',
      ),
    );

    return switch (appsState) {
      // If poller completed but query still loading, show uncataloged (not skeleton)
      StorageLoading() => pollerHasCompleted
          ? _buildUncatalogedOnly(installedPackages)
          : CategorizedUpdates.empty,
      StorageError() => const CategorizedUpdates(
        automaticUpdates: [],
        manualUpdates: [],
        upToDateApps: [],
        uncatalogedApps: [],
        showSkeleton: false,
      ),
      StorageData(:final models) => _categorize(
        models,
        installedPackages,
        installedIds,
      ),
    };
  }

  /// Build state with all packages as uncataloged (when query hasn't loaded yet
  /// but poller completed - means relay has no data for these apps)
  CategorizedUpdates _buildUncatalogedOnly(List<PackageInfo> installedPackages) {
    final sorted = installedPackages.toList()
      ..sort(
        (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
          (b.name ?? b.appId).toLowerCase(),
        ),
      );
    return CategorizedUpdates(
      automaticUpdates: const [],
      manualUpdates: const [],
      upToDateApps: const [],
      uncatalogedApps: sorted,
      showSkeleton: false,
    );
  }

  CategorizedUpdates _categorize(
    List<App> apps,
    List<PackageInfo> installedPackages,
    Set<String> installedIds,
  ) {
    final automaticUpdates = <App>[];
    final manualUpdates = <App>[];
    final upToDateApps = <App>[];

    // Build lookup map from passed-in data
    final installedMap = {for (final pkg in installedPackages) pkg.appId: pkg};

    // Track ALL apps returned from local query as "cataloged"
    final catalogedAppIds = apps.map((a) => a.identifier).toSet();

    // Check if ANY installed app has a match in local DB
    // If not, we should show skeleton (cold start state)
    final hasAnyMatch = installedIds.any(catalogedAppIds.contains);

    // Only process apps that are actually installed
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

    return CategorizedUpdates(
      automaticUpdates: automaticUpdates,
      manualUpdates: manualUpdates,
      upToDateApps: upToDateApps,
      uncatalogedApps: uncatalogedApps,
      // Show skeleton only if installed apps exist but NONE match local DB
      showSkeleton: installedIds.isNotEmpty && !hasAnyMatch,
    );
  }

  /// Check if an update is available by comparing versions
  bool _hasUpdate(PackageInfo installed, FileMetadata latest) {
    if (latest.versionCode != null && installed.versionCode != null) {
      return latest.versionCode! > installed.versionCode!;
    }
    return canUpgrade(installed.version, latest.version);
  }
}

final categorizedUpdatesProvider =
    NotifierProvider<CategorizedUpdatesNotifier, CategorizedUpdates>(
      CategorizedUpdatesNotifier.new,
    );

/// Provider that calculates the total number of apps with available updates
final updateCountProvider = Provider<int>((ref) {
  final categorized = ref.watch(categorizedUpdatesProvider);
  if (categorized.showSkeleton) return 0;
  return categorized.automaticUpdates.length + categorized.manualUpdates.length;
});
