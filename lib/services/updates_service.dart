import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/main.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/services/catalog_fetcher.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/deletion_processor.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/settings_service.dart';
import 'package:zapstore/utils/extensions.dart';

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
    this.catalogedIds = const {},
  });

  final bool isChecking;
  final DateTime? lastCheckTime;
  final String? lastError;

  /// App identifiers found in the relay catalog. The categorizer uses this
  /// to know which installed apps to query (with relationships) from local DB.
  final Set<String> catalogedIds;

  UpdatePollerState copyWith({
    bool? isChecking,
    DateTime? lastCheckTime,
    String? lastError,
    bool clearError = false,
    Set<String>? catalogedIds,
  }) {
    return UpdatePollerState(
      isChecking: isChecking ?? this.isChecking,
      lastCheckTime: lastCheckTime ?? this.lastCheckTime,
      lastError: clearError ? null : (lastError ?? this.lastError),
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
  List<String> _lastBackedUpIds = [];

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
      unawaited(_backupInstalledApps());
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

    final storage =
        ref.read(storageNotifierProvider.notifier) as PurplebaseStorageNotifier;

    final results = await Future.wait([
      fetchCatalog(
        storage: storage,
        installedIds: pmState.installed.keys.toSet(),
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
  /// Call when returning to the updates screen so that data written by
  /// other code paths (detail screen, background service) is picked up
  /// without waiting for the next poll cycle.
  Future<void> refreshFromLocal() async {
    final pmState = ref.read(packageManagerProvider);
    if (pmState.installed.isEmpty) return;

    final result = await fetchCatalog(
      storage: ref.read(storageNotifierProvider.notifier),
      installedIds: pmState.installed.keys.toSet(),
      platform: ref.read(packageManagerProvider.notifier).platform,
      subscriptionPrefix: 'app-updates-local',
      localOnly: true,
    );

    state = state.copyWith(catalogedIds: result.catalogedIds);
  }

  /// Best-effort backup of installed apps as an encrypted private stack.
  /// Runs after each successful update check. Only publishes when the set
  /// of cataloged installed apps has changed since the last backup.
  Future<void> _backupInstalledApps() async {
    final pubkey = ref.read(Signer.activePubkeyProvider);
    if (pubkey == null) return;

    final settings = await ref.read(settingsServiceProvider).load();
    if (!settings.installedAppsBackupEnabled) return;

    final signer = ref.read(Signer.activeSignerProvider)!;
    final pmNotifier = ref.read(packageManagerProvider.notifier);
    final installed = ref.read(packageManagerProvider).installed;
    final platform = pmNotifier.platform;
    final storage = ref.read(storageNotifierProvider.notifier);

    try {
      final apps = await storage.query(
        RequestFilter<App>(
          tags: {
            '#d': installed.keys.toSet(),
            '#f': {platform},
          },
        ).toRequest(),
        source: const LocalSource(),
        subscriptionPrefix: 'app-backup-resolve',
      );

      final appIds =
          apps
              .map((a) => '${a.event.kind}:${a.event.pubkey}:${a.identifier}')
              .toList()
            ..sort();

      if (_listEquals(appIds, _lastBackedUpIds)) return;

      final partialStack = PartialAppStack.withEncryptedApps(
        name: 'Installed Apps',
        identifier: kInstalledAppsBackupIdentifier,
        apps: appIds,
        platform: platform,
      );

      final signed = await partialStack.signWith(signer);
      await storage.save({signed});
      await storage.publish({signed}, relays: {'AppCatalog', 'social'});
      _lastBackedUpIds = appIds;
    } catch (e, st) {
      LogService.I.warn(
        'installed apps backup failed',
        tag: 'updates',
        err: e,
        stack: st,
      );
    }
  }

  static bool _listEquals(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
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

  final catalogedIds = pollerState.catalogedIds;
  final catalogedInstalledIds = installed.keys
      .where(catalogedIds.contains)
      .toSet();

  if (catalogedInstalledIds.isEmpty) {
    return CategorizedUpdates(
      automaticUpdates: const [],
      manualUpdates: const [],
      upToDateApps: const [],
      uncatalogedApps: installed.values.toList()
        ..sort(
          (a, b) => (a.name ?? a.appId).toLowerCase().compareTo(
            (b.name ?? b.appId).toLowerCase(),
          ),
        ),
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
    if (pkg == null) continue;

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
