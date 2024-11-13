import 'package:collection/collection.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/widgets/app_curation_container.dart';

AppLifecycleListener? _lifecycleListener;
SharedPreferences? sharedPreferences;

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

  _lifecycleListener = AppLifecycleListener(
    onStateChange: (state) async {
      if (state == AppLifecycleState.resumed) {
        await ref.localApps.localAppAdapter.refreshUpdateStatus();
      }
    },
  );

  // Preload curation sets
  // Do not use ignoreReturn here
  if (ref.appCurationSets.countLocal == 0) {
    await ref.appCurationSets.findAll();
    // Take opportunity to preload updates
    await ref.apps.appAdapter.checkForUpdates();
  } else {
    ref.appCurationSets.findAll();
  }

  // Preload zapstore's nostr curation set
  await ref.read(appCurationSetProvider(kNostrCurationSet).notifier).fetch();

  // Handle deep links
  final appLinksSub = appLinks.uriLinkStream.listen((uri) async {
    if (uri.scheme == 'zapstore') {
      final adapter = ref.apps.appAdapter;
      final apps = adapter.findWhereIdentifierInLocal({uri.host});
      // Filter by signer npub, if present, otherwise pick first
      final appSignerNpub = uri.queryParameters['signer'];
      var app =
          apps.firstWhereOrNull((a) => a.signer.value?.npub == appSignerNpub);
      if (appSignerNpub == null) {
        app = apps.first;
      }
      if (app != null) {
        appRouter.go('/details', extra: app);
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
