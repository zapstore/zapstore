import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/catalog_fetcher.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

/// How often to poll for updates from remote relays
const _pollInterval = Duration(minutes: 5);

/// Minimum time between manual refreshes
const _refreshCooldown = Duration(seconds: 30);

// ═══════════════════════════════════════════════════════════════════════════════
// CATEGORIZED UPDATES
// ═══════════════════════════════════════════════════════════════════════════════

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
  final List<PackageInfo> uncatalogedApps;
  final bool showSkeleton;

  static const empty = CategorizedUpdates(
    automaticUpdates: [],
    manualUpdates: [],
    upToDateApps: [],
    uncatalogedApps: [],
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
    this.apps = const [],
    this.installableByApp = const {},
    this.catalogedIds = const {},
  });

  final bool isChecking;
  final DateTime? lastCheckTime;
  final String? lastError;

  /// App objects fetched from relay, used for display (name, icon, author)
  final List<App> apps;

  /// appIdentifier → Installable (3063 or 1063), used for update comparison
  final Map<String, Installable> installableByApp;

  /// All app identifiers found in relay catalog (superset of apps list,
  /// since an installable may exist without a corresponding App object)
  final Set<String> catalogedIds;

  UpdatePollerState copyWith({
    bool? isChecking,
    DateTime? lastCheckTime,
    String? lastError,
    bool clearError = false,
    List<App>? apps,
    Map<String, Installable>? installableByApp,
    Set<String>? catalogedIds,
  }) {
    return UpdatePollerState(
      isChecking: isChecking ?? this.isChecking,
      lastCheckTime: lastCheckTime ?? this.lastCheckTime,
      lastError: clearError ? null : (lastError ?? this.lastError),
      apps: apps ?? this.apps,
      installableByApp: installableByApp ?? this.installableByApp,
      catalogedIds: catalogedIds ?? this.catalogedIds,
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

  void _init() {
    ref.listen<AsyncValue<void>>(appInitializationProvider, (prev, next) {
      if (prev is! AsyncData && next is AsyncData) {
        _startPolling();
      }
    }, fireImmediately: true);
  }

  void _startPolling() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(_pollInterval, (_) => checkNow());
    checkNow();
  }

  /// Trigger an update check. Called by timer and pull-to-refresh.
  Future<void> checkNow() async {
    if (state.isChecking) return;

    if (state.lastCheckTime != null) {
      final elapsed = DateTime.now().difference(state.lastCheckTime!);
      if (elapsed < _refreshCooldown) return;
    }

    state = state.copyWith(isChecking: true);

    try {
      await ref.read(packageManagerProvider.notifier).syncInstalledPackages();
      await _fetchCatalog();
      state = state.copyWith(
        isChecking: false,
        lastCheckTime: DateTime.now(),
        clearError: true,
      );
    } catch (e) {
      debugPrint('[UpdatePoller] Check failed: $e');
      state = state.copyWith(
        isChecking: false,
        lastCheckTime: DateTime.now(),
        lastError: 'Update check failed — will retry',
      );
    }
  }

  /// Fetch catalog data from relays and store in state.
  /// Categorization is done by [categorizedUpdatesProvider].
  Future<void> _fetchCatalog() async {
    final pmState = ref.read(packageManagerProvider);
    if (pmState.installed.isEmpty) {
      state = state.copyWith(
        apps: const [],
        installableByApp: const {},
        catalogedIds: const {},
      );
      return;
    }

    final storage = ref.read(storageNotifierProvider.notifier);
    final result = await fetchCatalog(
      storage: storage,
      installedIds: pmState.installed.keys.toSet(),
      platform: ref.read(packageManagerProvider.notifier).platform,
      subscriptionPrefix: 'app-updates-poll',
    );

    final authorPubkeys = result.apps.map((a) => a.event.pubkey).toSet();
    if (authorPubkeys.isNotEmpty) {
      unawaited(
        storage.query(
          RequestFilter<Profile>(authors: authorPubkeys).toRequest(),
          source: const LocalAndRemoteSource(
            relays: {'social', 'vertex'},
            cachedFor: Duration(hours: 2),
            stream: false,
          ),
          subscriptionPrefix: 'app-updates-profiles',
        ),
      );
    }

    state = state.copyWith(
      apps: result.apps,
      installableByApp: result.installableByApp,
      catalogedIds: result.catalogedIds,
    );
  }

  /// Re-derive catalog from local DB without hitting relays.
  /// Call when returning to the updates screen so that data written by
  /// other code paths (detail screen, background service) is picked up
  /// without waiting for the next poll cycle.
  Future<void> refreshFromLocal() async {
    final pmState = ref.read(packageManagerProvider);
    if (pmState.installed.isEmpty) return;

    final storage = ref.read(storageNotifierProvider.notifier);
    final result = await fetchCatalog(
      storage: storage,
      installedIds: pmState.installed.keys.toSet(),
      platform: ref.read(packageManagerProvider.notifier).platform,
      subscriptionPrefix: 'app-updates-local',
      source: const LocalSource(),
    );

    state = state.copyWith(
      apps: result.apps,
      installableByApp: result.installableByApp,
      catalogedIds: result.catalogedIds,
    );
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

/// Pure synchronous derivation from poller catalog + installed packages.
/// Rebuilds when either changes.
final categorizedUpdatesProvider = Provider<CategorizedUpdates>((ref) {
  final pollerState = ref.watch(updatePollerProvider);
  final installed = ref.watch(
    packageManagerProvider.select((s) => s.installed),
  );

  if (pollerState.lastCheckTime == null) {
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

  final pm = ref.read(packageManagerProvider.notifier);
  final installableByApp = pollerState.installableByApp;
  final catalogedIds = pollerState.catalogedIds;

  final automaticUpdates = <App>[];
  final manualUpdates = <App>[];
  final upToDateApps = <App>[];

  for (final app in pollerState.apps) {
    final pkg = installed[app.identifier];
    if (pkg == null) continue;

    final installable = installableByApp[app.identifier];
    final hasUpdate =
        installable != null && pm.hasUpdate(app.identifier, installable);

    if (hasUpdate) {
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
          .toList()
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
  );
});

/// Total number of apps with available updates
final updateCountProvider = Provider<int>((ref) {
  final categorized = ref.watch(categorizedUpdatesProvider);
  if (categorized.showSkeleton) return 0;
  return categorized.automaticUpdates.length + categorized.manualUpdates.length;
});
