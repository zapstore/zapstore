import 'package:collection/collection.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/widgets/app_curation_container.dart';

AppLifecycleListener? _lifecycleListener;

final appInitializer = FutureProvider<void>((ref) async {
  // Initialize Flutter Data
  await ref.read(initializeFlutterData(adapterProvidersMap).future);

  // Check DB version
  final userDbVersion = ref.settings.findOneLocalById('_')!.dbVersion;
  if (userDbVersion < kDbVersion) {
    await ref.read(localStorageProvider).destroy();
  }

  // TODO: Restore isEventVerified feature
  //     isEventVerified: (Map<String, dynamic> map) {
  //       // If replaceable, we check for that ID
  //       final identifier = (map['tags'] as Iterable)
  //           .firstWhereOrNull((t) => t[0] == 'd')?[1]
  //           ?.toString();
  //       final id = identifier != null
  //           ? (map['kind'] as int, map['pubkey'].toString(), identifier)
  //               .formatted
  //           : map['id'];
  //       return ref.apps.nostrAdapter.existsId(id);
  //     },
  //   ),

  //   socialRelays.initialize(
  //       isEventVerified: (map) => ref.apps.nostrAdapter.existsId(map['pubkey']))
  // ]);

  _lifecycleListener = AppLifecycleListener(
    onStateChange: (state) async {
      if (state == AppLifecycleState.resumed) {
        await ref.localApps.localAppAdapter.refreshUpdateStatus();
      }
    },
  );

  // Preload curation sets
  if (ref.appCurationSets.countLocal == 0) {
    await ref.appCurationSets.findAll();
  } else {
    ref.appCurationSets.findAll();
  }

  // Preload zapstore's nostr curation set
  await ref.read(appCurationSetProvider(kNostrCurationSet).notifier).fetch();

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
