import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/services/catalog_fetcher.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/deletion_processor.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/unmanaged_apps_service.dart';
import 'package:zapstore/services/settings_service.dart';
import 'package:zapstore/utils/extensions.dart';

/// How often to poll for updates from remote relays
const _pollInterval = Duration(minutes: 5);

/// Minimum time between manual refreshes
const _refreshCooldown = Duration(seconds: 30);

/// Installed package IDs eligible for catalog discovery and update management.
Set<String> managedInstalledAppIds(
  Map<String, PackageInfo> installed,
  Set<String> unmanagedIds,
) {
  return installed.keys.where((id) => !unmanagedIds.contains(id)).toSet();
}

// ═══════════════════════════════════════════════════════════════════════════════
// CATEGORIZED UPDATES
// ═══════════════════════════════════════════════════════════════════════════════

class CategorizedUpdates {
  const CategorizedUpdates({
    required this.automaticUpdates,
    required this.manualUpdates,
    required this.upToDateApps,
    required this.uncatalogedApps,
    this.unmanagedApps = const [],
    this.showSkeleton = false,
  });

  final List<App> automaticUpdates;
  final List<App> manualUpdates;
  final List<App> upToDateApps;
  final List<PackageInfo> uncatalogedApps;

  /// Apps the user has explicitly marked unmanaged (excluded from all other lists).
  final List<PackageInfo> unmanagedApps;
  final bool showSkeleton;

  static const empty = CategorizedUpdates(
    automaticUpdates: [],
    manualUpdates: [],
    upToDateApps: [],
    uncatalogedApps: [],
    unmanagedApps: [],
    showSkeleton: true,
  );
}

// ═══════════════════════════════════════════════════════════════════════════════
// UPDATE POLLER STATE
// ═══════════════════════════════════════════════════════════════════════════════

class UpdatePollerState {
  const UpdatePollerState({
    this.isChecking = false,
    this.lastCheckTime,
    this.lastError,
    this.catalogedIds = const {},
    this.hasHydrated = false,
  });

  final bool isChecking;
  final DateTime? lastCheckTime;
  final String? lastError;

  /// App identifiers found in the relay catalog. The categorizer uses this
  /// to know which installed apps to query (with relationships) from local DB.
  final Set<String> catalogedIds;

  /// True once the local DB has been scanned at least once (via
  /// [UpdatePollerNotifier.refreshFromLocal] or a successful remote check).
  /// UI gates its skeleton on this — NOT on [lastCheckTime] — so the list
  /// renders from local data without waiting on the network.
  final bool hasHydrated;

  UpdatePollerState copyWith({
    bool? isChecking,
    DateTime? lastCheckTime,
    String? lastError,
    bool clearError = false,
    Set<String>? catalogedIds,
    bool? hasHydrated,
  }) {
    return UpdatePollerState(
      isChecking: isChecking ?? this.isChecking,
      lastCheckTime: lastCheckTime ?? this.lastCheckTime,
      lastError: clearError ? null : (lastError ?? this.lastError),
      catalogedIds: catalogedIds ?? this.catalogedIds,
      hasHydrated: hasHydrated ?? this.hasHydrated,
    );
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UPDATE POLLER - Fetches catalog data from relays
// ═══════════════════════════════════════════════════════════════════════════════

class UpdatePollerNotifier extends StateNotifier<UpdatePollerState> {
  UpdatePollerNotifier(this.ref) : super(const UpdatePollerState()) {
    _init();
  }

  final Ref ref;
  Timer? _pollTimer;
  bool _catalogRefreshPending = false;
  bool _isRefreshingManagedCatalog = false;

  void _init() {
    // Hydrate from local storage as soon as SQLite is ready — no network.
    // This populates `catalogedIds` and flips `hasHydrated`, unblocking the
    // Updates screen UI without waiting on any remote poll.
    ref.listen<AsyncValue<void>>(storageReadyProvider, (prev, next) {
      if (prev is! AsyncData && next is AsyncData) {
        unawaited(_hydrateAndStartPolling());
      }
    }, fireImmediately: true);
    ref.listen<Set<String>>(unmanagedAppsProvider, (previous, next) {
      if (previous != next) _queueManagedCatalogRefresh();
    });
  }

  Future<void> _hydrateAndStartPolling() async {
    // Ensure we know what's installed before categorizing. `syncInstalledPackages`
    // is a local native call — safe offline.
    try {
      await ref.read(packageManagerProvider.notifier).syncInstalledPackages();
    } catch (e, st) {
      LogService.I.debug(
        'initial package sync failed',
        tag: 'updates',
        err: e,
        stack: st,
      );
    }

    // Local-only hydration. Sets `hasHydrated: true` so the UI renders.
    await refreshFromLocal();

    _startPolling();
    _drainManagedCatalogRefresh();
  }

  void _startPolling() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(_pollInterval, (_) => checkNow());
    // Fire-and-forget: a remote check blocks for tens of seconds offline,
    // but must not delay the local-first UI render that [refreshFromLocal]
    // already produced.
    unawaited(checkNow(refreshInstalledPackages: false));
  }

  /// Trigger an update check. Called by timer and pull-to-refresh.
  Future<void> checkNow({bool refreshInstalledPackages = true}) async {
    if (state.isChecking) return;

    if (state.lastCheckTime != null) {
      final elapsed = DateTime.now().difference(state.lastCheckTime!);
      if (elapsed < _refreshCooldown) return;
    }

    state = state.copyWith(isChecking: true);

    try {
      if (refreshInstalledPackages) {
        await ref.read(packageManagerProvider.notifier).syncInstalledPackages();
      }
      await _fetchCatalog();
      state = state.copyWith(
        isChecking: false,
        lastCheckTime: DateTime.now(),
        clearError: true,
        hasHydrated: true,
      );
    } catch (e, st) {
      LogService.I.warn(
        'update check failed',
        tag: 'updates',
        err: e,
        stack: st,
      );
      state = state.copyWith(
        isChecking: false,
        lastCheckTime: DateTime.now(),
        lastError: 'Update check failed — will retry',
      );
    } finally {
      _drainManagedCatalogRefresh();
    }
  }

  /// Fetches catalog data after a Manage/Unmanage transition. This is separate
  /// from [checkNow] because the transition changes which installed IDs are
  /// eligible for discovery and must not wait for its refresh cooldown.
  void _queueManagedCatalogRefresh() {
    _catalogRefreshPending = true;
    _drainManagedCatalogRefresh();
  }

  void _drainManagedCatalogRefresh() {
    if (_isRefreshingManagedCatalog ||
        state.isChecking ||
        !state.hasHydrated ||
        !_catalogRefreshPending) {
      return;
    }
    unawaited(_refreshManagedCatalog());
  }

  Future<void> _refreshManagedCatalog() async {
    _isRefreshingManagedCatalog = true;
    _catalogRefreshPending = false;
    state = state.copyWith(isChecking: true);
    try {
      await _fetchCatalog();
      state = state.copyWith(
        isChecking: false,
        lastCheckTime: DateTime.now(),
        clearError: true,
      );
    } catch (e, st) {
      LogService.I.warn(
        'managed catalog refresh failed',
        tag: 'updates',
        err: e,
        stack: st,
      );
      state = state.copyWith(
        isChecking: false,
        lastError: 'Update check failed — will retry',
      );
    } finally {
      _isRefreshingManagedCatalog = false;
      _drainManagedCatalogRefresh();
    }
  }

  /// Fetch catalog data from relays and store in local DB.
  /// The poller only retains [catalogedIds]; the categorizer reactively
  /// queries Apps with relationships from local cache.
  Future<void> _fetchCatalog() async {
    final pmState = ref.read(packageManagerProvider);
    if (pmState.installed.isEmpty) {
      state = state.copyWith(catalogedIds: const {});
      return;
    }

    final unmanagedIds = ref.read(unmanagedAppsProvider);
    final installedIds = managedInstalledAppIds(
      pmState.installed,
      unmanagedIds,
    );

    if (installedIds.isEmpty) {
      state = state.copyWith(catalogedIds: const {});
      return;
    }

    final storage =
        ref.read(storageNotifierProvider.notifier) as PurplebaseStorageNotifier;

    final results = await Future.wait([
      fetchCatalog(
        storage: storage,
        installedIds: installedIds,
        platform: ref.read(packageManagerProvider.notifier).platform,
        subscriptionPrefix: 'app-updates-poll',
      ),
      processDeletions(
        storage: storage,
        settingsService: ref.read(settingsServiceProvider),
        subscriptionPrefix: 'app-deletions-poll',
      ),
    ]);

    final result = results[0] as CatalogResult;
    state = state.copyWith(catalogedIds: result.catalogedIds);
  }

  /// Re-derive catalog IDs from local DB without hitting relays.
  /// Called on startup (offline-safe) and when returning to the updates
  /// screen so that data written by other code paths (detail screen,
  /// background service) is picked up without waiting for a poll cycle.
  ///
  /// Always sets `hasHydrated: true` — even when the installed set is empty
  /// — so the UI can exit its skeleton state.
  Future<void> refreshFromLocal() async {
    final pmState = ref.read(packageManagerProvider);

    if (pmState.installed.isEmpty) {
      state = state.copyWith(hasHydrated: true);
      return;
    }

    final unmanagedIds = ref.read(unmanagedAppsProvider);
    final installedIds = managedInstalledAppIds(
      pmState.installed,
      unmanagedIds,
    );

    if (installedIds.isEmpty) {
      state = state.copyWith(hasHydrated: true);
      return;
    }

    try {
      final result = await fetchCatalog(
        storage: ref.read(storageNotifierProvider.notifier),
        installedIds: installedIds,
        platform: ref.read(packageManagerProvider.notifier).platform,
        subscriptionPrefix: 'app-updates-local',
        localOnly: true,
      );
      state = state.copyWith(
        catalogedIds: result.catalogedIds,
        hasHydrated: true,
      );
    } catch (e, st) {
      LogService.I.warn(
        'local refresh failed',
        tag: 'updates',
        err: e,
        stack: st,
      );
      // Still flip hasHydrated so the UI doesn't stay on skeleton forever;
      // categorization falls through to "uncataloged".
      state = state.copyWith(hasHydrated: true);
    }
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }
}

final updatePollerProvider =
    StateNotifierProvider<UpdatePollerNotifier, UpdatePollerState>(
      (ref) => UpdatePollerNotifier(ref),
    );

// ═══════════════════════════════════════════════════════════════════════════════
// CATEGORIZED UPDATES PROVIDER
// ═══════════════════════════════════════════════════════════════════════════════

/// Derives update categories from a reactive local query that loads each App
/// with its installable relationships. This is the same data path used by
/// the detail screen, install button, and version pill — one source of truth.
final categorizedUpdatesProvider = Provider<CategorizedUpdates>((ref) {
  final pollerState = ref.watch(updatePollerProvider);
  final installed = ref.watch(
    packageManagerProvider.select((s) => s.installed),
  );

  if (!pollerState.hasHydrated) {
    return CategorizedUpdates.empty;
  }

  if (installed.isEmpty) {
    return const CategorizedUpdates(
      automaticUpdates: [],
      manualUpdates: [],
      upToDateApps: [],
      uncatalogedApps: [],
    );
  }

  final unmanagedIds = ref.watch(unmanagedAppsProvider);

  final catalogedIds = pollerState.catalogedIds;
  // Keep the local App query independent of the unmanaged set. Otherwise a
  // toggle changes its filter, briefly replaces the cached result with a
  // loading query, and makes the updates list visibly jump.
  final catalogedInstalledIds = installed.keys
      .where(catalogedIds.contains)
      .toSet();

  if (catalogedInstalledIds.isEmpty) {
    final unmanagedAppsEarly =
        installed.values
            .where((pkg) => unmanagedIds.contains(pkg.appId))
            .toList()
          ..sort(
            (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
              (b.name ?? b.appId).toLowerCase(),
            ),
          );
    return CategorizedUpdates(
      automaticUpdates: const [],
      manualUpdates: const [],
      upToDateApps: const [],
      uncatalogedApps:
          installed.values
              .where(
                (pkg) =>
                    !catalogedIds.contains(pkg.appId) &&
                    !unmanagedIds.contains(pkg.appId),
              )
              .toList()
            ..sort(
              (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
                (b.name ?? b.appId).toLowerCase(),
              ),
            ),
      unmanagedApps: unmanagedAppsEarly,
    );
  }

  final platform = ref.read(packageManagerProvider.notifier).platform;

  // Reactive local query: loads Apps with their installable relationships.
  // The catalog fetcher already wrote SoftwareAssets / FileMetadatas to the
  // local DB; this query picks them up via the model relationship graph —
  // the same path used by the detail screen and install button.
  final appsState = ref.watch(
    query<App>(
      tags: {
        '#d': catalogedInstalledIds,
        '#f': {platform},
      },
      and: (app) => {
        app.latestAsset.query(source: const LocalSource()),
        app.latestRelease.query(
          source: const LocalSource(),
          and: (release) => {
            release.latestMetadata.query(source: const LocalSource()),
          },
        ),
      },
      source: const LocalSource(),
      subscriptionPrefix: 'app-updates-categorize',
    ),
  );

  final apps = appsState.models;

  final automaticUpdates = <App>[];
  final manualUpdates = <App>[];
  final upToDateApps = <App>[];

  for (final app in apps) {
    final pkg = installed[app.identifier];
    if (pkg == null || unmanagedIds.contains(app.identifier)) continue;

    if (app.hasUpdate) {
      if (pkg.canInstallSilently) {
        automaticUpdates.add(app);
      } else {
        manualUpdates.add(app);
      }
    } else {
      upToDateApps.add(app);
    }
  }

  int byName(App a, App b) => (a.name ?? a.identifier).toLowerCase().compareTo(
    (b.name ?? b.identifier).toLowerCase(),
  );
  automaticUpdates.sort(byName);
  manualUpdates.sort(byName);
  upToDateApps.sort(byName);

  final uncatalogedApps =
      installed.values
          .where((pkg) => !catalogedIds.contains(pkg.appId))
          .where((pkg) => !unmanagedIds.contains(pkg.appId))
          .toList()
        ..sort(
          (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
            (b.name ?? b.appId).toLowerCase(),
          ),
        );

  final unmanagedApps =
      installed.values.where((pkg) => unmanagedIds.contains(pkg.appId)).toList()
        ..sort(
          (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
            (b.name ?? b.appId).toLowerCase(),
          ),
        );

  return CategorizedUpdates(
    automaticUpdates: automaticUpdates,
    manualUpdates: manualUpdates,
    upToDateApps: upToDateApps,
    uncatalogedApps: uncatalogedApps,
    unmanagedApps: unmanagedApps,
  );
});

/// Total number of apps with available updates
final updateCountProvider = Provider<int>((ref) {
  final categorized = ref.watch(categorizedUpdatesProvider);
  if (categorized.showSkeleton) return 0;
  return categorized.automaticUpdates.length + categorized.manualUpdates.length;
});
