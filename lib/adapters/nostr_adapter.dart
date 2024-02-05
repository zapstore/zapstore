import 'dart:async';

import 'package:dart_nostr/dart_nostr.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:zapstore/models/base_event.dart';

mixin NostrAdapter<T extends BaseEvent<T>> on RemoteAdapter<T> {
  late final Nostr nostr;

  @override
  Future<void> onInitialized() async {
    nostr = await ref.read(nostrProvider.future);
    super.onInitialized();
  }

  void update(List<NostrFilter> filters) async {
    final req = NostrRequest(filters: filters);
    await Nostr.instance.relaysService.startEventsSubscriptionAsync(
      request: req,
      timeout: Duration(seconds: 10),
    );
  }

  @override
  DataStateNotifier<List<T>> watchAllNotifier(
      {bool? remote,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      String? finder,
      DataRequestLabel? label}) {
    late final StreamSubscription _sub;
    _sub = Nostr.instance.relaysService.streamsController.events
        .listen((NostrEvent event) {
      final baseEvent = localAdapter.deserialize({
        'id': event.id,
        'kind': event.kind,
        'content': event.content,
        'sig': event.sig,
        'pubkey': event.pubkey,
        'created_at': event.createdAt.toIso8601String(),
        'tags': event.tags,
      });
      baseEvent.saveLocal();
    });

    ref.onDispose(() {
      print('disposing nostr sub');
      _sub.cancel();
    });

    return super
        .watchAllNotifier(
            remote: remote,
            params: params,
            headers: headers,
            syncLocal: syncLocal,
            finder: finder,
            label: label)
        .where((e) {
      final search = params?['search'];
      if (search != null && search.isNotEmpty) {
        return e.content.contains(search);
      }
      return true;
    });
  }
}

final nostrProvider = FutureProvider<Nostr>((Ref ref) async {
  final relays = ['wss://relay.nostr.band'];
  print('***** initing nostr ********');
  await Nostr.instance.relaysService.init(
    relaysUrl: relays,
  );
  Nostr.instance.utilsService.utils.disableLogs();
  return Nostr.instance;
});
