import 'dart:async';
import 'dart:io' show File, Platform;
import 'dart:isolate';
import 'dart:ui' show PlatformDispatcher;

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:purplebase/purplebase.dart';
import 'package:amber_signer/amber_signer.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:zapstore/services/background_update_service.dart';
import 'package:zapstore/services/log_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/settings_service.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/services/package_manager/android_package_manager.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/deep_link_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/breathing_logo.dart';

/// Global provider container so handlers outside the widget tree can
/// reach Riverpod state.
late final ProviderContainer _providerContainer;

/// Receives uncaught isolate errors from the main isolate. Held at
/// top level so it survives for the lifetime of the app — without
/// this reference the port could be garbage-collected and the
/// isolate listener would silently stop firing.
// ignore: unused_element
RawReceivePort? _isolateErrorPort;

void main() {
  // Everything that touches the Flutter binding or schedules async work
  // for the app MUST run inside the same zone as `runApp`. Otherwise the
  // engine throws a "Zone mismatch" assertion because zone-specific
  // configuration (error handlers, microtask hooks) would be split
  // between the root zone and the guarded zone.
  runZonedGuarded(() {
    WidgetsFlutterBinding.ensureInitialized();

    // Install all four error sinks BEFORE runApp.
    // LogService.I works pre-init (writes go to the ring buffer until
    // disk is ready); init() is awaited just below to enable disk.
    _installErrorHandlers();

    // Bring up disk logging. Not awaited — if path_provider fails the
    // LogService falls back to ring-buffer-only and we continue.
    unawaited(LogService.I.init(isolateName: 'main').then((_) {
      LogService.I.info(
        'app starting',
        tag: 'app',
        fields: {
          'platform': Platform.operatingSystem,
          'version': Platform.operatingSystemVersion,
        },
      );
    }));

    _providerContainer = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(PurplebaseStorageNotifier.new),
        packageManagerProvider.overrideWith(
          (ref) => Platform.isAndroid
              ? AndroidPackageManager(ref)
              : DummyPackageManager(ref),
        ),
      ],
      observers: const [LoggingProviderObserver()],
    );

    runApp(
      UncontrolledProviderScope(
        container: _providerContainer,
        child: const ZapstoreApp(),
      ),
    );
  }, (error, stack) => _logUncaught(error, stack, source: 'zone'));
}

/// Wires the four error sinks documented in FEAT-005:
///   * `FlutterError.onError`              — sync framework errors
///   * `PlatformDispatcher.instance.onError` — engine / uncaught Dart
///   * `runZonedGuarded`                   — async errors in the root zone
///   * `Isolate.current.addErrorListener`  — main-isolate errors that
///                                            bypass zones
///
/// In debug builds errors continue to be dumped to the console via
/// `FlutterError.presentError` so `flutter run` still shows them.
void _installErrorHandlers() {
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    _logUncaught(details.exception, details.stack,
        source: 'flutter', library: details.library);
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    _logUncaught(error, stack, source: 'platform_dispatcher');
    // Returning true tells the engine the error is handled (we logged
    // it). Returning false would cause the engine to terminate the
    // isolate on some platforms.
    return true;
  };

  // Errors that escape to the isolate level (e.g. unhandled errors in
  // a Future created in a foreign zone) come back via this port.
  final port = RawReceivePort((dynamic pair) {
    // Isolate sends a [errorString, stackString] list.
    if (pair is List && pair.length == 2) {
      final err = pair[0]?.toString() ?? 'unknown';
      final stack = pair[1] == null
          ? null
          : StackTrace.fromString(pair[1].toString());
      _logUncaught(err, stack, source: 'isolate');
    }
  });
  Isolate.current.addErrorListener(port.sendPort);
  _isolateErrorPort = port;
}

/// Common entry point for all four sinks. Always non-blocking. On a
/// fatal sink (Flutter / PlatformDispatcher) we also flush the log
/// synchronously so the entry survives an immediate crash.
void _logUncaught(
  Object error,
  StackTrace? stack, {
  required String source,
  String? library,
}) {
  LogService.I.fatal(
    'uncaught error',
    tag: 'crash',
    fields: {
      'source': source,
      if (library != null) 'library': library,
    },
    err: error,
    stack: stack,
  );
  // Best-effort durable flush. Swallows its own errors.
  try {
    LogService.I.flushSync();
  } catch (_) {}
}

class ZapstoreApp extends HookConsumerWidget {
  const ZapstoreApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifier =
        ref.read(storageNotifierProvider.notifier) as PurplebaseStorageNotifier;
    final title = 'Zapstore';

    // Watch initialization state for error overlay display
    final initState = ref.watch(appInitializationProvider);

    // Listen to app lifecycle and check for updates when app regains focus
    useEffect(() {
      final observer = _AppLifecycleObserver(ref);
      WidgetsBinding.instance.addObserver(observer);
      return () => WidgetsBinding.instance.removeObserver(observer);
    }, []);

    // Listen to connectivity changes and trigger ensureConnected when going online
    useEffect(() {
      final connectivity = Connectivity();
      StreamSubscription<List<ConnectivityResult>>? subscription;

      // Check initial connectivity state
      connectivity.checkConnectivity().then((results) {
        notifier.connect();
      });

      // Listen to connectivity changes
      subscription = connectivity.onConnectivityChanged.listen((results) {
        notifier.connect();
      });

      return () => subscription?.cancel();
    }, []);

    // Automatically sign out when Amber is uninstalled while signed in.
    ref.listen<bool>(
      packageManagerProvider.select(
        (state) => state.installed.containsKey(kAmberPackageId),
      ),
      (previous, isAmberInstalled) {
        if (previous != true || isAmberInstalled) return;
        if (ref.read(Signer.activePubkeyProvider) == null) return;

        unawaited(() async {
          try {
            await ref.read(amberSignerProvider).signOut();

            final toastContext =
                rootNavigatorKey.currentState?.overlay?.context;
            if (toastContext != null && toastContext.mounted) {
              toastContext.showInfo('Amber was removed, you were signed out');
            }
          } catch (error, stack) {
            LogService.I.warn(
              'auto sign-out after Amber uninstall failed',
              tag: 'amber',
              err: error,
              stack: stack,
            );
          }
        }());
      },
    );

    // Always show the main app UI, even during initialization
    return MaterialApp.router(
      title: title,
      theme: darkTheme,
      routerConfig: ref.watch(routerProvider),
      debugShowCheckedModeBanner: false,
      builder: (context, child) {
        // Limit text scale factor to prevent extreme sizes on different devices
        final mediaQuery = MediaQuery.of(context);
        final constrainedTextScale = mediaQuery.textScaler
            .scale(1.0)
            .clamp(1.0, 1.2);
        final constrainedTextScaler = TextScaler.linear(constrainedTextScale);

        // Show error overlay if initialization failed (do not block UI during loading)
        if (initState is AsyncError) {
          return MediaQuery(
            data: mediaQuery.copyWith(textScaler: constrainedTextScaler),
            child: Stack(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4.0),
                  child: child!,
                ),
                // Error overlay
                Container(
                  color: Colors.black54,
                  child: Center(
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16.0),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.error_outline,
                              size: 64,
                              color: Colors.red,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              'Initialization Error',
                              style: context.textTheme.headlineSmall,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              initState.error.toString(),
                              textAlign: TextAlign.center,
                              style: context.textTheme.bodyMedium,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          );
        }

        return MediaQuery(
          data: mediaQuery.copyWith(textScaler: constrainedTextScaler),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4.0),
            child: child!,
          ),
        );
      },
    );
  }
}

class ZapstoreHome extends StatelessWidget {
  const ZapstoreHome({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const BreathingLogo(size: 120),
            const SizedBox(height: 24),
            Text('Zapstore', style: context.textTheme.headlineMedium),
            const SizedBox(height: 8),
            Text(
              'Permissionless app store for Nostr',
              style: context.textTheme.bodyLarge,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

const _kDefaultAppCatalogRelay = 'wss://relay.zapstore.dev';

final appInitializationProvider = FutureProvider<void>((ref) async {
  final dir = await getApplicationSupportDirectory();
  final dbPath = path.join(dir.path, 'zapstore.db');

  // Clear storage if requested from a clear all operation
  await maybeClearStorage(dbPath);

  // Seed database on first launch so new users see content immediately
  await _maybeCopySeedDatabase(dbPath);

  // Load local relay config BEFORE storage init
  // This ensures custom relays work even when signed out
  final settings = await ref.read(settingsServiceProvider).load();
  final appCatalogRelays = settings.appCatalogRelays ?? {_kDefaultAppCatalogRelay};

  // Apply persisted log level (default is `debug`).
  LogService.I.level = settings.logLevel;

  // Initialize storage with local relay config
  await ref.read(
    initializationProvider(
      StorageConfiguration(
        databasePath: dbPath,
        defaultQuerySource: LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        defaultRelays: {
          'default': {_kDefaultAppCatalogRelay},
          'bootstrap': {_kDefaultAppCatalogRelay},
          'AppCatalog': appCatalogRelays,
          'social': {
            'wss://relay.damus.io',
            'wss://relay.primal.net',
            'wss://nos.lol',
          },
          'vertex': {'wss://relay.vertexlab.io'},
        },
        responseTimeout: Duration(seconds: 6),
      ),
    ).future,
  );

  // Initialize device capabilities (used for dynamic download concurrency)
  await DeviceCapabilitiesCache.initialize();

  // Record app open time for background notification throttling
  await ref.read(settingsServiceProvider).update(
        (s) => s.copyWith(lastAppOpened: DateTime.now()),
      );

  // Ensure installed packages are available before anything categorizes
  final packageManager = ref.read(packageManagerProvider.notifier);
  await packageManager.syncInstalledPackages();

  final backgroundService = ref.read(backgroundUpdateServiceProvider);
  unawaited(backgroundService.initialize());

  await ref.read(deepLinkServiceProvider).initialize();

  await _attemptAutoSignIn(ref);
});

// AmberSigner provider for Nostr authentication
// Uses SecureStoragePubkeyPersistence to survive database clears
final amberSignerProvider = Provider<AmberSigner>(
  (ref) => AmberSigner(ref, persistence: SecureStoragePubkeyPersistence()),
);

/// Copy the bundled seed database on first launch so the UI has content
/// before relay data arrives. No-op if the database already exists.
/// Skipped when the user has configured a non-default relay, since the
/// seed was built from [_kDefaultAppCatalogRelay] and would be wrong.
Future<void> _maybeCopySeedDatabase(String dbPath) async {
  final dbFile = File(dbPath);
  if (dbFile.existsSync()) return;

  final settings = await SettingsService().load();
  final isDefault = settings.appCatalogRelays == null ||
      (settings.appCatalogRelays!.length == 1 &&
          settings.appCatalogRelays!.contains(_kDefaultAppCatalogRelay));
  if (!isDefault) return;

  try {
    final seedData = await rootBundle.load('assets/seed.db');
    await dbFile.create(recursive: true);
    await dbFile.writeAsBytes(
      seedData.buffer.asUint8List(
        seedData.offsetInBytes,
        seedData.lengthInBytes,
      ),
      flush: true,
    );
  } catch (e, st) {
    // Non-fatal: the app works fine without the seed — just a cold start.
    LogService.I.warn(
      'seed database copy failed',
      tag: 'init',
      err: e,
      stack: st,
    );
  }
}

Future<void> _attemptAutoSignIn(Ref ref) async {
  try {
    await ref.read(amberSignerProvider).attemptAutoSignIn();
    await onSignInSuccess(ref);
  } catch (e, st) {
    // Auto sign-in fails on first install — that's fine, just continue.
    // Logged at debug because this is expected for new users.
    LogService.I.debug(
      'auto sign-in attempt failed',
      tag: 'amber',
      err: e,
      stack: st,
    );
  }
}

/// Query ContactList after successful sign-in
Future<void> onSignInSuccess(Ref ref) async {
  final pubkey = ref.read(Signer.activePubkeyProvider);
  if (pubkey == null) return;

  final storage =
      ref.read(storageNotifierProvider.notifier) as PurplebaseStorageNotifier;

  // Fetch contact list for stack sorting (await to ensure it's cached)
  await storage.query(
    RequestFilter<ContactList>(authors: {pubkey}).toRequest(),
    source: const RemoteSource(relays: 'social', stream: false),
    subscriptionPrefix: 'app-contact-list',
  );
}

/// Observes app lifecycle events and manages package/storage state
class _AppLifecycleObserver with WidgetsBindingObserver {
  _AppLifecycleObserver(this._ref);

  final WidgetRef _ref;

  /// Handle permission grants that happened while app was backgrounded
  Future<void> _checkPermissionGrants() async {
    final packageManager = _ref.read(packageManagerProvider.notifier);
    if (packageManager is! AndroidPackageManager) return;

    final hasPermission = await packageManager.hasPermission();
    if (!hasPermission) return;

    // Advance any operations waiting for permission
    final state = _ref.read(packageManagerProvider);
    final awaitingPermission = state.operations.entries
        .where((e) => e.value is AwaitingPermission)
        .map((e) => e.key)
        .toList(growable: false);

    for (final appId in awaitingPermission) {
      await packageManager.onPermissionGranted(appId);
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final notifier =
        _ref.read(storageNotifierProvider.notifier)
            as PurplebaseStorageNotifier;
    final packageManager = _ref.read(packageManagerProvider.notifier);

    if (state == AppLifecycleState.resumed) {
      // Record app open time for background notification throttling
      unawaited(_recordAppOpened());

      // Sync installed packages to detect installs that completed while backgrounded
      unawaited(packageManager.syncInstalledPackages());

      // Check for permission grants that happened in settings
      unawaited(_checkPermissionGrants());

      // Reconnect storage/relay connections
      notifier.connect();
    } else if (state == AppLifecycleState.paused) {
      notifier.disconnect();
      // Flush any pending log entries to disk before the OS may freeze
      // or kill us, so diagnostics survive backgrounding.
      unawaited(LogService.I.flush());
    } else if (state == AppLifecycleState.detached) {
      // Last chance before the engine tears down — sync flush.
      LogService.I.flushSync();
    }
  }

  /// Record that the user opened the app.
  /// This is used to check inactivity for background notifications.
  Future<void> _recordAppOpened() async {
    await SettingsService().update((s) => s.copyWith(lastAppOpened: DateTime.now()));
  }
}
