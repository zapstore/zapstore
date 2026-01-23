import 'dart:async';
import 'dart:io' show Platform;

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:purplebase/purplebase.dart';
import 'package:amber_signer/amber_signer.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:zapstore/services/background_update_service.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/services/package_manager/android_package_manager.dart';
import 'package:zapstore/services/package_manager/dummy_package_manager.dart';
import 'package:zapstore/services/market_intent_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/breathing_logo.dart';

/// Global provider container for error reporting (accessible outside widget tree)
late final ProviderContainer _providerContainer;

void main() {
  // Create provider container with overrides
  _providerContainer = ProviderContainer(
    overrides: [
      storageNotifierProvider.overrideWith(PurplebaseStorageNotifier.new),
      packageManagerProvider.overrideWith(
        (ref) => Platform.isAndroid
            ? AndroidPackageManager(ref)
            : DummyPackageManager(ref),
      ),
    ],
  );

  runZonedGuarded(() {
    runApp(
      UncontrolledProviderScope(
        container: _providerContainer,
        child: const ZapstoreApp(),
      ),
    );
  }, _errorHandler);

  FlutterError.onError = (details) {
    // Prevents debugger stopping multiple times
    FlutterError.dumpErrorToConsole(details);
    _errorHandler(details.exception, details.stack);
  };
}

/// Global error handler that reports errors via NIP-44 encrypted DMs
void _errorHandler(Object exception, StackTrace? stack) {
  // Report error asynchronously (fire and forget)
  // TODO: Disabled until careful review
  // unawaited(
  //   _providerContainer
  //       .read(errorReportingServiceProvider)
  //       .reportError(exception, stack),
  // );
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

final appInitializationProvider = FutureProvider<void>((ref) async {
  final dir = await getApplicationSupportDirectory();
  final dbPath = path.join(dir.path, 'zapstore.db');

  // Clear storage if requested from a clear all operation
  await maybeClearStorage(dbPath);

  // Initialize storage
  await ref.read(
    initializationProvider(
      StorageConfiguration(
        databasePath: dbPath,
        defaultQuerySource: LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        defaultRelays: {
          'default': {'wss://relay.zapstore.dev'},
          'bootstrap': {'wss://purplepag.es', 'wss://relay.zapstore.dev'},
          'AppCatalog': {'wss://relay.zapstore.dev'},
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

  // These run in background - don't block UI
  final packageManager = ref.read(packageManagerProvider.notifier);
  unawaited(packageManager.syncInstalledPackages());

  final backgroundService = ref.read(backgroundUpdateServiceProvider);
  unawaited(backgroundService.initialize());

  unawaited(ref.read(marketIntentServiceProvider).initialize());

  unawaited(_attemptAutoSignIn(ref));
});

// AmberSigner provider for Nostr authentication
// Uses SecureStoragePubkeyPersistence to survive database clears
final amberSignerProvider = Provider<AmberSigner>(
  (ref) => AmberSigner(ref, persistence: SecureStoragePubkeyPersistence()),
);

Future<void> _attemptAutoSignIn(Ref ref) async {
  try {
    await ref.read(amberSignerProvider).attemptAutoSignIn();
    await onSignInSuccess(ref);
  } catch (e) {
    // Auto sign-in fails on first install — that's fine, just continue
  }
}

/// Query AppCatalogRelayList and ContactList after successful sign-in
Future<void> onSignInSuccess(Ref ref) async {
  final pubkey = ref.read(Signer.activePubkeyProvider);
  if (pubkey == null) return;

  final storage =
      ref.read(storageNotifierProvider.notifier) as PurplebaseStorageNotifier;

  unawaited(
    storage.query(
      RequestFilter<AppCatalogRelayList>(authors: {pubkey}).toRequest(),
      source: const RemoteSource(relays: 'bootstrap', stream: false),
    ),
  );

  // Fetch contact list for stack sorting (await to ensure it's cached)
  await storage.query(
    RequestFilter<ContactList>(authors: {pubkey}).toRequest(),
    source: const RemoteSource(relays: 'social', stream: false),
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
      // Sync installed packages to detect installs that completed while backgrounded
      unawaited(packageManager.syncInstalledPackages());

      // Check for permission grants that happened in settings
      unawaited(_checkPermissionGrants());

      // Reconnect storage/relay connections
      notifier.connect();
    } else if (state == AppLifecycleState.paused) {
      notifier.disconnect();
    }
  }
}
