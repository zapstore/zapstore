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

final appInitializer = FutureProvider<void>((ref) async {
  final prefs = await SharedPreferences.getInstance();
  final dbVersion = prefs.getInt('dbVersion');

  // Perform migration
  if (dbVersion == null || dbVersion < kDbVersion) {
    final storage = ref.read(localStorageProvider);
    await storage.initialize();
    await storage.destroy();
    await prefs.setInt('dbVersion', kDbVersion);
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
  } else {
    ref.appCurationSets.findAll();
  }

  // Preload zapstore's nostr curation set
  await ref.read(appCurationSetProvider(kNostrCurationSet).notifier).fetch();

  ref.localApps.localAppAdapter.updateNumberOfApps();

  // Handle deep links
  final appLinksSub = appLinks.uriLinkStream.listen((uri) async {
    if (uri.scheme == "zapstore") {
      final adapter = ref.apps.appAdapter;
      final app = adapter.findWhereIdentifierInLocal({uri.host}).firstOrNull;
      if (app != null) {
        appRouter.go('/details', extra: app);
      }
    }
  });

  ref.onDispose(() {
    _lifecycleListener?.dispose();
    appLinksSub.cancel();
  });
});
