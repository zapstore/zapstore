import 'package:collection/collection.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/utils/signers.dart';
import 'package:zapstore/widgets/app_curation_container.dart';

AppLifecycleListener? _lifecycleListener;
SharedPreferences? sharedPreferences;
User? anonUser;
final amberSigner = AmberSigner();

final appInitializer = FutureProvider<void>((ref) async {
  sharedPreferences = await SharedPreferences.getInstance();
  final dbVersion = sharedPreferences!.getInt('dbVersion');

  // Perform migration
  if (dbVersion == null || dbVersion < kDbVersion) {
    final storage = ref.read(localStorageProvider);
    await storage.initialize();
    await storage.destroy();
    await sharedPreferences!.setInt('dbVersion', kDbVersion);
  }

  // Initialize Flutter Data
  await ref.read(initializeFlutterData(adapterProvidersMap).future);

  // NOTE: Do not use ignoreReturn here
  if (ref.appCurationSets.countLocal == 0) {
    // If we are here, local storage is empty
    // Preload curation sets
    await ref.appCurationSets.findAll(syncLocal: true);
    // Preload updates in the background (no await)
    ref.apps.appAdapter.checkForUpdates();
  } else {
    // Preload curation sets in the background (no await)
    ref.appCurationSets.findAll(syncLocal: true, background: true);
  }

  // Preload zapstore's nostr curation set
  ref.read(appCurationSetProvider(kNostrCurationSetLink).notifier).fetch();

  // Initialize signer
  await amberSigner.initialize();

  // Set up anon user (pubkey derives from pkSigner secret key)
  anonUser ??= User.fromPubkey(
          'c86eda2daae768374526bc54903f388d9a866c00740ec8db418d7ef2dca77b5b')
      .init()
      .saveLocal();

  // App-wide listeners

  // Register app lifecycle listener
  _lifecycleListener = AppLifecycleListener(
    onStateChange: (state) {
      if (state == AppLifecycleState.resumed) {
        ref.localApps.localAppAdapter.refreshUpdateStatus();
      }
    },
  );

  // Handle deep links
  final appLinksSub = appLinks.uriLinkStream.listen((uri) async {
    String? appId;
    if (uri.scheme == 'https' && uri.hasFragment) {
      appId = uri.fragment;
    } else if (uri.scheme == 'zapstore') {
      appId = uri.host;
    }
    if (appId != null) {
      final adapter = ref.apps.appAdapter;
      final apps = adapter.findWhereIdentifierInLocal({appId});
      // Filter by signer npub, if present, otherwise pick first
      final appSignerNpub = uri.queryParameters['signer'];
      var goToApp =
          apps.firstWhereOrNull((a) => a.signer.value?.npub == appSignerNpub);
      if (appSignerNpub == null) {
        goToApp = apps.first;
      }
      if (goToApp != null) {
        appRouter.go('/details', extra: goToApp);
      } else {
        appRouter.go('/');
      }
    }
  });

  ref.onDispose(() {
    _lifecycleListener?.dispose();
    appLinksSub.cancel();
  });
});
