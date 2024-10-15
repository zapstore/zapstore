import 'package:collection/collection.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/widgets/app_curation_container.dart';

AppLifecycleListener? _lifecycleListener;

final appInitializer = FutureProvider<void>((ref) async {
  // Initialize Flutter Data
  await ref.read(initializeFlutterData(adapterProvidersMap).future);

  // Initialize relays
  final relay = ref.read(relayMessageNotifierProvider(kAppRelays).notifier);
  final socialRelays =
      ref.read(relayMessageNotifierProvider(kSocialRelays).notifier);
  await Future.wait([
    relay.initialize(
      isEventVerified: (Map<String, dynamic> map) {
        // If replaceable, we check for that ID
        final identifier = (map['tags'] as Iterable)
            .firstWhereOrNull((t) => t[0] == 'd')?[1]
            ?.toString();
        final id = identifier != null
            ? (map['kind'] as int, map['pubkey'].toString(), identifier)
                .formatted
            : map['id'];
        return ref.apps.nostrAdapter.existsId(id);
      },
    ),
    socialRelays.initialize(
        isEventVerified: (map) => ref.apps.nostrAdapter.existsId(map['pubkey']))
  ]);

  // Trigger app install status calculations
  ref.localApps.localAppAdapter.refreshUpdateStatus(); // do not await
  _lifecycleListener = AppLifecycleListener(
    onStateChange: (state) async {
      if (state == AppLifecycleState.resumed) {
        await ref.localApps.localAppAdapter.refreshUpdateStatus();
      }
    },
  );

  // In this initial phase, load there more or less fixed curation sets here
  await ref.appCurationSets.findAll();
  // Preload zapstore's nostr curation set
  await ref.read(appCurationSetProvider(kNostrCurationSet).future);

  await ref.apps.findAll(
    params: {
      'by-release': true,
      'limit': 10,
    },
  );

  // Handle deep links
  final appLinksSub = appLinks.uriLinkStream.listen((uri) async {
    if (uri.scheme == "zapstore") {
      final adapter = ref.apps.appAdapter;
      final App? app = await adapter.findOne(uri.host);
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
